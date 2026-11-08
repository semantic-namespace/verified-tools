(ns verified.router
  "The router: an MCP server over stdio that serves only the artifact's
   tools and decides each call by looking the artifact up, never by
   reasoning. For a call it checks, in order: the tool is in the artifact;
   its arguments are the declared ones, of the declared types; the
   session's caps; the session's taint (once a session has read a data
   class, the tools whose egress the session policy blocks for it leave the
   view). It then runs the tool in its sandbox, keeps only the declared keys
   of its result, masks the redaction patterns, and records the call.

     bb serve        (one session per process)"
  (:require [babashka.process :as proc]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def artifact (delay (edn/read-string (slurp "artifact/artifact.edn"))))
(def artifact-sha (delay (str/trim (slurp "artifact/artifact.edn.sha256"))))
(def audit-file "audit/audit.log")

;; the tools the user approved in advance for this session (approvals.edn,
;; written by the user, never by a tool): the consent a :confirm tool needs
(def approvals (delay (let [f (java.io.File. "approvals.edn")]
                        (if (.exists f) (set (edn/read-string (slurp f))) #{}))))

(def session (atom {:taint #{} :calls {}}))

(defn- now [] (str (java.time.Instant/now)))

(defn- audit! [m]
  (.mkdirs (java.io.File. "audit"))
  (spit audit-file (str (pr-str (merge {:at (now) :artifact (subs @artifact-sha 0 12)} m)) "\n") :append true))

(defn- tool-name [tid] (str/replace (name tid) "-" "_"))
(defn- tool-of [name] (some (fn [[tid _]] (when (= name (tool-name tid)) tid)) (:tools @artifact)))

(defn- schema [{:keys [args]}]
  {:type "object"
   :properties (into {} (for [[k t] args]
                          [(name k) {:type "string" :description (case t :date "a date, YYYY-MM-DD" :string "text, at most 2000 characters" (name t))}]))
   :required (mapv name (keys args))})

(defn- description [{:keys [doc reads returns egress]}]
  (str doc ". Reads " (str/join ", " (map name (sort reads)))
       "; returns only " (str/join ", " (map name returns))
       "; network: " (str/join ", " (map name (sort egress))) "."))

(defn- refuse [tid reason rule]
  (audit! {:tool tid :refused reason :rule rule})
  {:content [{:type "text" :text (str "refused: " reason (when rule (str " (" rule ")")))}] :isError true})

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

(defn call [tid args]
  (let [{:keys [returns caps reads egress sandbox] :as t} (get-in @artifact [:tools tid])
        {:keys [blocks] patterns :redact} (:session @artifact)
        {:keys [taint calls]} @session
        blocked-by (some (fn [e] (seq (filter taint (get blocks e)))) egress)]
    (cond
      (nil? t) (refuse tid "no such tool in the artifact" nil)
      (not (valid-args? t args)) (refuse tid (str "arguments must be exactly " (pr-str (:args t))) nil)
      (>= (get calls tid 0) (:per-session caps 0)) (refuse tid "the session's cap for this tool is reached" :caps)
      blocked-by (refuse tid (str "the session has read " (str/join ", " blocked-by)
                                  " and this tool's egress " (str/join ", " (sort egress)) " is blocked after it")
                         :session/blocks)
      ;; this router has no channel to ask the user: a tool that must ask is
      ;; refused, saying so, rather than run unconfirmed
      (and (:confirm t) (not (@approvals tid)))
      (refuse tid "this tool asks the user first: not approved for this session (approvals.edn)" :confirm)
      :else
      (let [cmd (concat sandbox ["bb" "-cp" (str "sdk/src:router/src:modules/" (:dir t) "/src")
                                 "-m" "verified.runner" (str tid) (pr-str (update-keys args keyword))])
            {:keys [exit out err]} @(proc/process (vec cmd) {:out :string :err :string})]
        (if-not (zero? exit)
          (do (audit! {:tool tid :args args :failed (first (str/split-lines (str err)))})
              {:content [{:type "text" :text (str "the tool failed: " (first (str/split-lines (str err))))}] :isError true})
          (let [rows (mapv #(update-vals (select-keys % returns) (partial redact patterns)) (edn/read-string out))]
            (swap! session #(-> % (update :taint into reads) (update-in [:calls tid] (fnil inc 0))))
            (audit! {:tool tid :args args :rows (count rows) :keys returns :taint (:taint @session)})
            {:content [{:type "text" :text (json/generate-string rows)}]}))))))

(defn- answer [{:strs [method params]}]
  (case method
    "initialize" {:protocolVersion (get params "protocolVersion" "2024-11-05")
                  :capabilities {:tools {}}
                  :serverInfo {:name "verified-router" :version "0.1.0"}}
    "tools/list" {:tools (vec (for [[tid t] (:tools @artifact)]
                                {:name (tool-name tid) :description (description t) :inputSchema (schema t)}))}
    "tools/call" (let [tid (tool-of (get params "name"))]
                   (call (or tid (keyword "unknown" (str (get params "name")))) (get params "arguments" {})))
    nil))

(defn -main [& _]
  (audit! {:session :start :tools (vec (keys (:tools @artifact))) :approved (vec (sort @approvals))})
  (doseq [line (line-seq (java.io.BufferedReader. *in*))
          :when (not (str/blank? line))]
    (let [msg (json/parse-string line)
          id (get msg "id")
          result (try (answer msg) (catch Exception e {:content [{:type "text" :text (ex-message e)}] :isError true}))]
      (when id
        (println (json/generate-string (if (and (nil? result) (not (#{"tools/call"} (get msg "method"))))
                                         {:jsonrpc "2.0" :id id :error {:code -32601 :message "method not found"}}
                                         {:jsonrpc "2.0" :id id :result result})))
        (flush)))))
