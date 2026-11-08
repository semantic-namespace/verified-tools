(ns verified.sdk
  "What a running tool is bound to, by the runner: its module's declaration,
   its own tool id, and the transport its HTTP goes through (the real one,
   through the egress proxy, or a replay of recorded responses). A module
   never sets these; it only calls the SDK's functions, which read them.")

(def ^:dynamic *module* "The module's declaration, as module.edn says it." nil)
(def ^:dynamic *tool* "The id of the tool running." nil)
(def ^:dynamic *transport* "(fn [request]) → response, for http/call." nil)

(defn refuse
  "Stops a call the declaration doesn't allow: an ex-info naming why."
  [msg data]
  (throw (ex-info msg (assoc data ::refused true))))
