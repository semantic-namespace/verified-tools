(ns verified.router
  "The router: an MCP server over stdio that serves only the artifact's
   tools and decides each call by looking the artifact up, never by
   reasoning. For a call it checks, in order: the tool is in the artifact;
   its arguments are the declared ones, of the declared types; the
   session's caps; the session's taint (once a session has read a data
   class, the tools whose egress the session policy blocks for it are
   refused); and, for a tool that must ask, the user's answer. It then runs
   the tool in its sandbox, keeps only the declared keys of its result, up
   to its per-call cap, masks the redaction patterns, and records the call.

   It is also the tools' only way out. A sandbox has no network; live, a
   tool's HTTP requests come to the router over the tool's stdio, and the
   router makes them only when they match a route the module declares,
   adding the credential the route names. The tool never holds a token.

   Asking the user goes around the agent, never through it: by MCP
   elicitation when the client offers it, else (or when live.edn says
   :confirm {:via :terminal}) by a file the user answers with `bb confirm`
   in their own terminal.

     bb serve [--artifact f] [--live f] [--secrets f] [--audit dir] [--confirm dir]
                     (one session per process)
     secrets.edn     (the user's, not in git) {:google {:access-token \"ya29...\"}}
     live.edn        (the user's, not in git) where hosts resolve, and
                     credentials kept elsewhere than secrets.edn: {:hosts {\"gmail.googleapis.com\" \"http://127.0.0.1:8765\"}
                                       :credentials {:google {:token-file \"secrets/google.token\"}}
                                       :confirm {:timeout-s 120 :via :terminal}}"
  (:require [babashka.http-client :as http]
            [babashka.process :as proc]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; where things are; bb serve [--artifact f] [--live f] [--secrets f] [--audit dir] [--confirm dir]
(def config (atom {:artifact "artifact/artifact.edn" :live "live.edn" :secrets "secrets.edn"
                   :audit "audit" :confirm "confirm"}))

(def ^:private read-edn
  (memoize (fn [path] (let [f (io/file path)] (if (.exists f) (edn/read-string (slurp f)) {})))))
(defn artifact [] (read-edn (:artifact @config)))
(defn artifact-sha [] (str/trim (slurp (str (:artifact @config) ".sha256"))))
(defn live [] (read-edn (:live @config)))

(def session (atom {:taint #{} :calls {} :client-caps {}}))
(def mcp-in (java.io.BufferedReader. (java.io.InputStreamReader. System/in)))

(defn- now [] (str (java.time.Instant/now)))

(defn- audit! [m]
  (.mkdirs (io/file (:audit @config)))
  (spit (io/file (:audit @config) "audit.log")
        (str (pr-str (merge {:at (now) :artifact (subs (artifact-sha) 0 12)} m)) "\n") :append true))

(defn- send! [msg]
  (locking mcp-in
    (println (json/generate-string msg))
    (flush)))

(defn- tool-name [tid] (str/replace (name tid) "-" "_"))
(defn- tool-of [name] (some (fn [[tid _]] (when (= name (tool-name tid)) tid)) (:tools (artifact))))

(defn- schema [{:keys [args]}]
  {:type "object"
   :properties (into {} (for [[k t] args]
                          [(name k) {:type "string" :description (case t :date "a date, YYYY-MM-DD" :string "text, at most 2000 characters" (name t))}]))
   :required (mapv name (keys args))})

(defn- description [{:keys [doc reads returns egress confirm]}]
  (str doc ". Reads " (str/join ", " (map name (sort reads)))
       "; returns only " (str/join ", " (map name returns))
       "; network: " (str/join ", " (map name (sort egress)))
       (when confirm "; asks the user before each call") "."))

(defn- text [s] {:content [{:type "text" :text s}]})

(defn- refuse [tid reason rule]
  (audit! {:tool tid :refused reason :rule rule})
  (assoc (text (str "refused: " reason (when rule (str " (" rule ")")))) :isError true))

(defn- valid-args? [{:keys [args]} given]
  (and (= (set (map name (keys args))) (set (keys given)))
       (every? (fn [[k t]] (let [v (get given (name k))]
                              (case t
                                :date (boolean (re-matches #"\d{4}-\d{2}-\d{2}" (str v)))
                                :string (and (string? v) (<= (count v) 2000))
                                false)))
               args)))

(defn- redact [patterns v]
  (if (string? v)
    (reduce (fn [s [k re]] (str/replace s (re-pattern re) (str "[" (name k) " masked]"))) v patterns)
    v))

;; ---- egress: the tools' only way out, live

(defn- template-re [path]
  (re-pattern (str "\\Q" (str/replace path #"\{[a-z-]+\}" "\\\\E[A-Za-z0-9_-]+\\\\Q") "\\E")))

(defn- plain-values? [m]
  (and (or (nil? m) (map? m))
       (every? (fn [[k v]] (and (keyword? k) (string? v) (<= (count v) 2000))) m)))

(defn- allowed-route [{:keys [proxy egress]} {:keys [host method path query body]}]
  (when (and (some #{:egress/provider :egress/internet} egress)
             (plain-values? query) (plain-values? body))
    (some #(when (and (= host (:host %)) (= method (:method %))
                      (re-matches (template-re (:path %)) (str path)))
             %)
          proxy)))

(defn- token
  "The credential a route names: a token file live.edn points to, else the
   :access-token under its name in secrets.edn (read on each request, so a
   renewed token is used at once). The placeholder counts as none."
  [auth]
  (let [v (if-let [f (get-in (live) [:credentials auth :token-file])]
            (slurp f)
            (let [f (io/file (:secrets @config))]
              (when (.exists f) (get-in (edn/read-string (slurp f)) [auth :access-token]))))]
    (when (and (string? v) (not (str/blank? v)) (not (str/starts-with? v "PASTE")))
      (str/trim v))))

(defn- egress!
  "Makes a tool's request when it matches a route its module declares:
   {:verified/response body} or {:verified/refused why}."
  [tid t {:keys [host method path query body] :as req}]
  (if-let [route (allowed-route t req)]
    (let [base (get-in (live) [:hosts host] (str "https://" host))
          tok (when (:auth route) (token (:auth route)))]
      (if (and (:auth route) (nil? tok))
        (do (audit! {:tool tid :egress [method host path] :refused "no credential for it in live.edn"})
            {:verified/refused (str "no credential for " (:auth route) " (secrets.edn)")})
        (let [resp (http/request {:method method :uri (str base path) :throw false
                                  :query-params (update-keys (or query {}) name)
                                  :headers (cond-> {"accept" "application/json"}
                                             tok (assoc "authorization" (str "Bearer " tok))
                                             body (assoc "content-type" "application/json"))
                                  :body (some-> body json/generate-string)})]
          (audit! {:tool tid :egress [method host path] :status (:status resp)})
          (if (<= 200 (:status resp) 299)
            {:verified/response (json/parse-string (:body resp) true)}
            {:verified/refused (str host " answered " (:status resp))}))))
    (do (audit! {:tool tid :egress [method host path] :refused "not a declared route"})
        {:verified/refused (str method " " host path " is not a declared route")})))

;; ---- running a tool in its sandbox

(defn- bb-path []
  (some #(let [f (io/file % "bb")] (when (.canExecute f) (.getCanonicalPath f)))
        (str/split (System/getenv "PATH") #":")))

(defn- sandbox
  "The tool's sandbox command, its placeholders filled for this machine."
  [t]
  (let [fill {"{root}" (.getCanonicalPath (io/file "."))
              "{bb}" (bb-path)
              "{artifact-dir}" (.getCanonicalPath (.getParentFile (.getAbsoluteFile (io/file (:artifact @config)))))}]
    (mapv #(str/replace % #"\{root\}|\{bb\}|\{artifact-dir\}" fill) (:sandbox t))))

(defn- run-tool [tid t args]
  (let [cmd (concat (sandbox t) ["bb" "-cp" (str "sdk/src:router/src:modules/" (:dir t) "/src")
                                  "-m" "verified.runner" (str tid) (pr-str (update-keys args keyword))
                                  (.getCanonicalPath (io/file (:artifact @config)))])
        p (proc/process (vec cmd) {:err :string})
        to-tool (io/writer (:in p))
        result (with-open [r (io/reader (:out p))]
                 (loop []
                   (when-let [line (.readLine ^java.io.BufferedReader r)]
                     (let [msg (try (edn/read-string line) (catch Exception _ nil))]
                       (cond
                         (and (map? msg) (contains? msg :verified/result)) (:verified/result msg)
                         (and (map? msg) (:verified/request msg))
                         (do (.write to-tool (str (pr-str (egress! tid t (:verified/request msg))) "\n"))
                             (.flush to-tool)
                             (recur))
                         :else (recur))))))
        {:keys [exit err]} @p]
    (if (and (zero? exit) (sequential? result))
      {:rows result}
      {:failed (or (some->> (str err) (re-find #"(?m)^Message:\s+(.*)$") second)
                   (first (str/split-lines (str err)))
                   "no result")})))

;; ---- asking the user, around the agent

(defn- question [tid {:keys [doc egress proxy]} args]
  (str "The agent wants to run " (tool-name tid) " (" doc ").\n"
       "It would send to " (str/join ", " (distinct (map :host proxy)))
       " (" (str/join ", " (map name (sort egress))) "):\n\n"
       (str/join "\n" (for [[k v] (sort args)] (str "  " k ": " v)))
       "\n\nAllow this one call?"))

(defn- ask-by-elicitation [q]
  (let [id (str "confirm-" (System/nanoTime))]
    (send! {:jsonrpc "2.0" :id id :method "elicitation/create"
            :params {:message q
                     :requestedSchema {:type "object"
                                       :properties {:allow {:type "boolean" :title "Allow this call"}}
                                       :required ["allow"]}}})
    (loop []
      (if-let [line (.readLine mcp-in)]
        (let [msg (json/parse-string line)]
          (if (= id (get msg "id"))
            (let [{:strs [action content]} (get msg "result")]
              (if (and (= "accept" action) (true? (get content "allow")))
                {:allowed true :via :elicitation}
                {:allowed false :via :elicitation
                 :answer (cond (= "accept" action) "not allowed" action action
                               :else (get-in msg ["error" "message"] "no answer"))}))
            (recur)))
        {:allowed false :via :elicitation :answer "the client closed the channel"}))))

(defn- ask-by-file [tid q args]
  (let [id (str (name tid) "-" (System/currentTimeMillis))
        pending (io/file (:confirm @config) "pending" (str id ".edn"))
        answer (io/file (:confirm @config) "answers" (str id ".edn"))
        timeout-ms (* 1000 (get-in (live) [:confirm :timeout-s] 120))]
    (io/make-parents pending)
    (io/make-parents answer)
    (spit pending (pr-str {:id id :tool tid :args args :question q :asked (now)}))
    (binding [*out* *err*] (println (str "verified-router: waiting for `bb confirm " id " yes|no`")))
    (loop [waited 0]
      (cond
        (.exists answer)
        (let [{a :answer} (edn/read-string (slurp answer))]
          (.delete pending) (.delete answer)
          {:allowed (= :yes a) :via :terminal :answer a})
        (>= waited timeout-ms)
        (do (.delete pending) {:allowed false :via :terminal :answer :no-answer})
        :else (do (Thread/sleep 250) (recur (+ waited 250)))))))

(defn- ask-user [tid t args]
  (let [q (question tid t args)]
    (if (and (get-in @session [:client-caps "elicitation"])
             (not= :terminal (get-in (live) [:confirm :via])))
      (ask-by-elicitation q)
      (ask-by-file tid q args))))

;; ---- a call

(defn call [tid args]
  (let [{:keys [returns caps reads egress confirm] :as t} (get-in (artifact) [:tools tid])
        {:keys [blocks] patterns :redact} (:session (artifact))
        {:keys [taint calls]} @session
        blocked-by (some (fn [e] (seq (filter taint (get blocks e)))) egress)]
    (cond
      (nil? t) (refuse tid "no such tool in the artifact" nil)
      (not (valid-args? t args)) (refuse tid (str "arguments must be exactly " (pr-str (:args t))) nil)
      (>= (get calls tid 0) (:per-session caps 0)) (refuse tid "the session's cap for this tool is reached" :caps)
      blocked-by (refuse tid (str "the session has read " (str/join ", " blocked-by)
                                  " and this tool's egress " (str/join ", " (sort egress)) " is blocked after it")
                         :session/blocks)
      :else
      (let [asked (when confirm (ask-user tid t args))]
        (when asked (audit! (merge {:tool tid :args args :asked-user true} asked)))
        (if (and asked (not (:allowed asked)))
          (refuse tid (str "the user did not allow this call (" (name (:via asked)) ": " (:answer asked) ")") :confirm)
          (let [{:keys [rows failed]} (run-tool tid t args)]
            (if failed
              (do (audit! {:tool tid :args args :failed failed})
                  (assoc (text (str "the tool failed: " failed)) :isError true))
              (let [rows (->> rows
                              (take (:per-call caps 0))
                              (mapv #(update-vals (select-keys % returns) (partial redact patterns))))]
                (swap! session #(-> % (update :taint into reads) (update-in [:calls tid] (fnil inc 0))))
                (audit! {:tool tid :args args :rows (count rows) :keys returns :taint (:taint @session)})
                (text (json/generate-string rows))))))))))

(defn- answer [{:strs [method params]}]
  (case method
    "initialize" (let [caps (get params "capabilities" {})]
                   (swap! session assoc :client-caps caps)
                   (audit! {:session :client :client (get params "clientInfo") :capabilities caps})
                   {:protocolVersion (get params "protocolVersion" "2024-11-05")
                    :capabilities {:tools {}}
                    :serverInfo {:name "verified-router" :version "0.2.0"}})
    "tools/list" {:tools (vec (for [[tid t] (:tools (artifact))]
                                {:name (tool-name tid) :description (description t) :inputSchema (schema t)}))}
    "tools/call" (let [tid (tool-of (get params "name"))]
                   (call (or tid (keyword "unknown" (str (get params "name")))) (get params "arguments" {})))
    nil))

(defn -main [& args]
  (swap! config merge (into {} (for [[k v] (partition 2 args)] [(keyword (subs k 2)) v])))
  (audit! {:session :start :mode (:artifact/mode (artifact)) :tools (vec (keys (:tools (artifact))))})
  (loop []
    (when-let [line (.readLine mcp-in)]
      (when-not (str/blank? line)
        (let [msg (json/parse-string line)
              id (get msg "id")
              method (get msg "method")]
          (when (and id method)
            (let [result (try (answer msg) (catch Exception e (assoc (text (ex-message e)) :isError true)))]
              (send! (if (and (nil? result) (not= "tools/call" method))
                       {:jsonrpc "2.0" :id id :error {:code -32601 :message "method not found"}}
                       {:jsonrpc "2.0" :id id :result result}))))))
      (recur))))
