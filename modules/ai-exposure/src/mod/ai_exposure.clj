(ns mod.ai-exposure
  "Your agents' MCP servers, from their own configuration files, and the
   risky combinations among them. The files hold keys; nothing of a
   server's env, headers or arguments is returned: its name, kind, host
   or program name only."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [verified.sdk.bundle :as bundle]
            [verified.sdk.files :as files]
            [verified.sdk.out :as out]
            [verified.sdk.parse :as parse]))

;; ---- reading the configs

(defn- host-of [url] (second (re-find #"^[a-z]+://([^/:?#]+)" (str url))))

(defn- program-of
  "A local server's program, by name: bb, npx, uvx, a script's file name."
  [cmd]
  (last (str/split (str cmd) #"/")))

(defn- verified-router?
  "A server that is the verified-tools router: its command line runs `bb serve`."
  [{:strs [command args]}]
  (boolean (re-find #"\bbb\s+serve\b|verified\.router" (str/join " " (cons command args)))))

(defn- server [agent scope [n s]]
  (let [url (get s "url")]
    {:agent agent :scope scope :name n
     :kind (cond url "remote" (verified-router? s) "verified" :else "command")
     :host (host-of url)
     :program (when-not url (program-of (get s "command")))}))

(defn- claude-code []
  (when (files/bound? :claude-code)
    (let [m (parse/json (files/read :claude-code))]
      (concat
       (map #(server "Claude Code" "everywhere" %) (get m "mcpServers"))
       (for [[path p] (get m "projects") s (get p "mcpServers")]
         (server "Claude Code" (str "project " (last (str/split path #"/"))) s))))))

(defn- everywhere [agent text]
  (map #(server agent "everywhere" %) (get (parse/json text) "mcpServers")))

(defn- servers []
  (concat (claude-code)
          (when (files/bound? :claude-desktop) (everywhere "Claude Desktop" (files/read :claude-desktop)))
          (when (files/bound? :cursor) (everywhere "Cursor" (files/read :cursor)))))

;; ---- what each server reaches

(defn- host-entry [hosts host]
  (or (get hosts host)
      (some (fn [[k v]] (when (and (str/starts-with? k ".") (str/ends-with? (str host) k)) v)) hosts)))

(defn- verified-reach
  "What the verified tool set reaches, from its artifact, when the folder is bound."
  []
  (if-let [f (when (files/bound? :verified) (first (filter #(= "artifact.edn" %) (files/list :verified))))]
    (let [tools (vals (:tools (edn/read-string (files/read :verified f))))]
      (cond-> #{}
        (some #(some (fn [c] (= "data" (namespace c))) (:reads %)) tools) (conj :private)
        (some #(some #{:data/email :data/note} (:reads %)) tools) (conj :untrusted)
        (some #(and (contains? (set (:egress %)) :egress/internet) (#{:effect/send :effect/write} (:effect %))) tools) (conj :send)))
    #{:private :untrusted :send}))

(defn- reach [hosts s]
  (case (:kind s)
    "command" #{:private :untrusted :send}
    "verified" (verified-reach)
    "remote" (or (:reach (host-entry hosts (:host s))) #{})))

;; ---- the tools

(defn ^{:module/tool :tool/agent-connectors} agent-connectors [_]
  (out/rows :tool/agent-connectors
            (for [s (sort-by (juxt :agent :scope :name) (servers))]
              {:connector/agent (:agent s) :connector/scope (:scope s) :connector/name (:name s)
               :connector/kind (:kind s) :connector/host (:host s) :connector/program (:program s)})))

(defn- effective
  "The servers in effect for each agent and scope: a project's own, with
   the ones that apply everywhere."
  [ss]
  (let [everywhere (group-by :agent (filter #(= "everywhere" (:scope %)) ss))
        scoped (group-by (juxt :agent :scope) (remove #(= "everywhere" (:scope %)) ss))]
    (concat
     (for [[agent xs] everywhere] [[agent "everywhere"] xs])
     (for [[[agent scope] xs] scoped] [[agent scope] (concat xs (get everywhere agent))]))))

(defn ^{:module/tool :tool/exposure-findings} exposure-findings [_]
  (let [hosts (bundle/read-edn :hosts)
        ss (servers)]
    (out/rows
     :tool/exposure-findings
     (concat
      (for [[[agent scope] xs] (sort-by first (effective ss))
            :let [cmds (sort (distinct (map :name (filter #(= "command" (:kind %)) xs))))]
            :when (seq cmds)]
        {:finding/rule "unverified-program" :finding/agent agent :finding/scope scope
         :finding/connectors (vec cmds)
         ;; each of these reaches all three alone: it runs as you
         :finding/legs (vec (for [c cmds leg ["private" "untrusted" "send"]] (str leg ":" c ":alone")))
         :finding/unknown-hosts []})
      ;; among the servers whose reach is known: a local program reaches all
      ;; three alone, which is its own finding above
      (for [[[agent scope] xs] (sort-by first (effective ss))
            :let [known (remove #(= "command" (:kind %)) xs)
                  legs (into {} (for [leg [:private :untrusted :send]]
                                  [leg (sort (distinct (map :name (filter #(contains? (reach hosts %) leg) known))))]))
                  unknown (sort (distinct (keep #(when (and (= "remote" (:kind %)) (not (host-entry hosts (:host %)))) (:host %)) xs)))]
            :when (every? seq (vals legs))]
        {:finding/rule "trifecta" :finding/agent agent :finding/scope scope
         :finding/connectors (vec (sort (distinct (mapcat val legs))))
         :finding/legs (vec (for [[leg names] legs n names] (str (name leg) ":" n)))
         :finding/unknown-hosts (vec unknown)})
      (for [[[agent scope] xs] (sort-by first (effective ss))
            :let [unknown (sort (distinct (keep #(when (and (= "remote" (:kind %)) (not (host-entry hosts (:host %)))) (:host %)) xs)))]
            :when (seq unknown)]
        {:finding/rule "unknown-hosts" :finding/agent agent :finding/scope scope
         :finding/connectors [] :finding/legs [] :finding/unknown-hosts (vec unknown)})))))
