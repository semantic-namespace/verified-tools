(ns verified.hook
  "The verified-tools policy for an agent's own tools: a Claude Code
   PreToolUse hook. Each call, its tool and input on stdin, is classified
   with the vocabulary's ideas: reading a file that holds keys taints the
   session :secret; fetching a web page taints it :untrusted, from that host.
   A call that reaches the network is then decided:

     after :secret     deny: the command could carry the keys out
     after :untrusted  ask:  the page may be steering it; the command is shown
     otherwise         no decision: Claude Code's own permissions apply

   The session's taint is kept in <state-dir>/<session_id>.edn, and every
   decision, with its reason, in <state-dir>/audit.log. Classifying shell
   commands is conservative and fails towards asking: it catches the common
   ways out, and the sandbox's network allowlist is the boundary behind it.

     bb policy-hook --state-dir <dir>     (stdin: the hook's JSON)"
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def key-path
  "Paths that hold keys: ssh, cloud and gpg credentials, agent configs, env
   files, key files, anything named for credentials or secrets."
  #"(?i)(\.ssh/|\.aws/|\.gnupg/|\.config/gcloud|\.kube/config|\.docker/config\.json|\.netrc|\.npmrc|\.pypirc|\.claude\.json|(^|[/\s])\.env\b|credentials|secret|token|id_rsa|id_ed25519|\.pem\b|\.p12\b|\.key\b|secrets\.edn)")

(def network-command
  "Commands that reach the network, by their usual shapes."
  #"(?x)(^|[\s;&|(`$])(curl|wget|nc|ncat|netcat|socat|scp|sftp|ssh|rsync|ftp|telnet|http|https|xh)\s
       |git\s+push|git\s+remote\s+add|npm\s+publish|gh\s+(api|gist|issue|pr|release)
       |/dev/(tcp|udp)/
       |python3?\s+-c\s.*(requests|urllib|http\.client|socket)
       |node\s+-e\s.*(fetch|https?\.request|net\.)")

(def reads-file
  "Shell commands that read a file's content."
  #"(?x)(^|[\s;&|(`$])(cat|less|more|head|tail|grep|rg|base64|xxd|od|cp|tar|zip|gzip|openssl|jq|bb|python3?|node)\s")

(defn- hosts [s] (sort (distinct (map second (re-seq #"(?i)\b[a-z][a-z0-9+.-]*://([^/\s'\"]+)" (str s))))))

(defn classify
  "What a call does, for the policy: {:reads-keys [paths] :untrusted [hosts]
   :network? bool :hosts [..]}."
  [tool input]
  (case tool
    ("Read" "Edit" "Write" "NotebookEdit")
    (let [p (str (or (get input "file_path") (get input "notebook_path")))]
      (cond-> {} (and (= "Read" tool) (re-find key-path p)) (assoc :reads-keys [p])))
    ("Grep" "Glob")
    (let [p (str (get input "path") " " (get input "pattern") " " (get input "glob"))]
      (cond-> {} (re-find key-path p) (assoc :reads-keys [(str/trim p)])))
    ("WebFetch" "WebSearch")
    (let [hs (hosts (get input "url"))]
      {:untrusted (if (seq hs) hs ["the web"]) :network? true :hosts hs})
    "Bash"
    (let [cmd (str (get input "command"))
          keys? (and (re-find key-path cmd) (or (re-find reads-file cmd) (re-find #"[<$]" cmd)))]
      (cond-> {}
        keys? (assoc :reads-keys [(str/trim (subs cmd 0 (min 120 (count cmd))))])
        (re-find network-command cmd) (assoc :network? true :hosts (hosts cmd))))
    ;; an MCP tool or anything else: the router and Claude Code's permissions decide
    {}))

(defn decide
  "The decision for a call, given the session's taint before it:
   {:decision \"deny\"|\"ask\" :reason ..}, or nil for no decision."
  [{:keys [secret untrusted]} tool {:keys [network? reads-keys hosts]}]
  (let [secret (concat secret reads-keys)
        where (if (seq hosts) (str/join ", " hosts) "the network")]
    (cond
      (and network? (seq secret))
      {:decision "deny"
       :reason (str "this session has read files holding keys (" (str/join "; " (take 3 secret))
                    "), and this " tool " reaches " where ": it could carry them out")}
      (and network? (seq untrusted) (= "Bash" tool))
      {:decision "ask"
       :reason (str "this session has read web content (" (str/join ", " (take 3 untrusted))
                    "), which may be steering this command to " where)})))

(defn- state-file [dir session] (io/file dir (str (str/replace (str session) #"[^A-Za-z0-9_-]" "_") ".edn")))

(defn run
  "Reads one hook call, updates the session's taint, records and returns
   the hook's output (a map), or nil when it has no decision."
  [state-dir {:strs [session_id tool_name tool_input]}]
  (let [f (state-file state-dir session_id)
        state (if (.exists f) (edn/read-string (slurp f)) {})
        c (classify tool_name tool_input)
        d (decide state tool_name c)
        state' (cond-> state
                 (seq (:reads-keys c)) (update :secret (fnil into []) (:reads-keys c))
                 (seq (:untrusted c)) (update :untrusted (fnil into []) (:untrusted c)))]
    (io/make-parents f)
    (spit f (pr-str state'))
    (when (or d (not= state state'))
      (spit (io/file state-dir "audit.log")
            (str (pr-str (cond-> {:at (str (java.time.Instant/now)) :session session_id :tool tool_name
                                  :taint (into {} (for [[k v] state'] [k (count v)]))}
                           d (assoc :decision (:decision d) :reason (:reason d))))
                 "\n")
            :append true))
    (when d
      {:hookSpecificOutput {:hookEventName "PreToolUse"
                            :permissionDecision (:decision d)
                            :permissionDecisionReason (str "verified-tools policy: " (:reason d))}})))

(defn -main [& args]
  (let [opts (into {} (for [[k v] (partition 2 args)] [(keyword (subs k 2)) v]))
        state-dir (or (:state-dir opts) "target/hook-sessions")
        out (run state-dir (json/parse-string (slurp *in*)))]
    (when out (println (json/generate-string out)))))
