(ns verified.exposure-test
  "What can my AI see: agents' MCP servers read from their config files,
   which hold keys, and returned as names, kinds, hosts and programs only;
   the findings among them; and the session left holding agent-config, not
   secrets (a declassification the vocabulary lists)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [verified.router :as router]
            [verified.test-util :as u]))

(def dir (delay (let [d (u/scratch "exposure")] (u/build! :replay d) d)))

(defn- start [name & flags]
  (let [d (str @dir "/" name)]
    (apply u/start {:artifact (str @dir "/artifact.edn")
                    :live (u/write-edn! (str d "/live.edn")
                                        {:roots {:mod/ai-exposure
                                                 {:claude-code (str u/root "/test/fixtures/agent-configs/claude.json")
                                                  :claude-desktop (str u/root "/test/fixtures/agent-configs/claude_desktop_config.json")
                                                  :verified @dir}}})
                    :secrets (u/write-edn! (str d "/secrets.edn") {})
                    :audit (str d "/audit") :confirm (str d "/confirm")}
           flags)))

(defn- rows [res] (json/parse-string (:text res) true))

(deftest connectors-without-their-keys
  (let [s (start "connectors")
        res (try (u/call s "agent_connectors" {}) (finally (u/stop s)))
        by-name (into {} (map (juxt :connector/name identity) (rows res)))]
    (is (= {:connector/kind "remote" :connector/host "mcp.notion.com"}
           (select-keys (by-name "notion") [:connector/kind :connector/host])))
    (is (= {:connector/kind "command" :connector/program "uvx"}
           (select-keys (by-name "web-fetch") [:connector/kind :connector/program])))
    (is (= "verified" (:connector/kind (by-name "verified"))))
    (is (= "project acme" (:connector/scope (by-name "slack"))))
    (is (= "Claude Desktop" (:connector/agent (by-name "filesystem"))))
    (testing "nothing of a server's headers, env or arguments comes out"
      (doseq [secret ["SECRET" "sk-" "ghp_" "xoxb" "Bearer" "/home/me" "mcp-server-fetch"]]
        (is (not (str/includes? (:text res) secret)) secret)))))

(deftest findings
  (let [s (start "findings")
        fs (try (rows (u/call s "exposure_findings" {})) (finally (u/stop s)))
        of (fn [rule scope] (first (filter #(and (= rule (:finding/rule %)) (= scope (:finding/scope %))) fs)))]
    (is (= ["web-fetch"] (:finding/connectors (of "unverified-program" "everywhere"))) "a local program runs as you")
    (is (some #{"send:web-fetch:alone"} (:finding/legs (of "unverified-program" "everywhere"))))
    (is (some #{"untrusted:slack"} (:finding/legs (of "trifecta" "project acme")))
        "private, untrusted and send, among servers whose reach is known")
    (is (some #{"private:notion"} (:finding/legs (of "trifecta" "project acme"))))
    (is (= ["tools.unknown-vendor.example"] (:finding/unknown-hosts (of "unknown-hosts" "everywhere"))))))

(deftest the-session-holds-agent-config-not-secrets
  (let [s (start "taint" :elicitation true)]
    (try
      (u/call s "agent_connectors" {})
      (is (= "elicitation/create" (:method (u/call s "share_snippet" {"text" "my servers"})))
          "secrets would block it; agent-config is the user's call")
      (finally (u/stop s)))))

(deftest returns-are-their-declared-types
  (let [typed @#'router/typed
        types {:x/host :host :x/name :name :x/kind #{"a" "b"}}]
    (is (= {:x/host "[not a host]" :x/name "[not a name]" :x/kind "[not a declared value]"}
           (typed types {:x/host "evil.example/?k=sk-123" :x/name (apply str (repeat 81 "a")) :x/kind "c"})))
    (is (= {:x/host "mcp.notion.com" :x/name "notion" :x/kind "a" :x/other "free"}
           (typed types {:x/host "mcp.notion.com" :x/name "notion" :x/kind "a" :x/other "free"})))))
