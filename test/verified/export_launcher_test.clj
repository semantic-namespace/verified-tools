(ns verified.export-launcher-test
  "A Claude Code session from a profile export: atlas's rules, the user's
   server confined to the profile's partition and readable by the user only,
   and verified-tools' own additions (key files, sandbox, hook)."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [verified.export-launcher :as el]
            [verified.test-util :as u]))

(defn- prepare [surfaces]
  (let [d (u/scratch "export-launcher")
        surfaces-file (u/write-edn! (str d "/surfaces.edn") surfaces)]
    (el/prepare {:export "test/fixtures/llm-profiles/export.edn" :profile "llm-profile/demo-qa"
                 :surfaces-file surfaces-file :claude-json "test/fixtures/llm-profiles/claude.json"
                 :session-dir (str d "/session") :key-files ["Read(~/.ssh/**)"] :hook-cmd "the-hook"})))

(deftest a-session-from-an-export
  (let [{:keys [settings mcp-file settings-file missing counts]} (prepare {:env/staging "staging-mcp"})
        mcp (json/parse-string (slurp (str mcp-file)))]
    (is (empty? missing))
    (is (= {:allow 1 :ask 1 :deny 1} counts))
    (testing "atlas's exact rules, and verified-tools' own"
      (is (= ["mcp__staging-qa__read_thing"] (get-in settings [:permissions :allow])))
      (is (= ["mcp__staging-qa__write_thing" "Bash(git push *)"] (get-in settings [:permissions :ask])))
      (is (= ["mcp__staging-qa__send_thing" "Bash(psql *prod*)" "Read(~/.ssh/**)"] (get-in settings [:permissions :deny])))
      (is (= ["github.com"] (get-in settings [:sandbox :network :allowedDomains])))
      (is (false? (get-in settings [:sandbox :allowUnsandboxedCommands])))
      (is (= "the-hook" (get-in settings [:hooks :PreToolUse 0 :hooks 0 :command]))))
    (testing "the user's server, confined to the partition, only in a file only the user reads"
      (is (= ["staging-qa"] (keys (get mcp "mcpServers"))))
      (is (some #{"MCP_SERVER_URL=https://staging.example/mcp/qa"} (get-in mcp ["mcpServers" "staging-qa" "args"])))
      (is (= "rw-------" (java.nio.file.attribute.PosixFilePermissions/toString (fs/posix-file-permissions (str mcp-file)))))
      (is (not (str/includes? (slurp (str settings-file)) "FAKE")) "no credential in the settings"))))

(deftest a-server-kept-on-its-own-path
  (let [{:keys [mcp-file servers]} (prepare {:env/staging {:server "staging-mcp" :rescope false}})]
    (is (some #{"MCP_SERVER_URL=https://staging.example/mcp"}
              (get-in (json/parse-string (slurp (str mcp-file))) ["mcpServers" "staging-qa" "args"])))
    (is (str/includes? (nth (first servers) 2) "not confined to /mcp/qa") "and the summary says so")))

(deftest a-surface-with-no-server-is-reported
  (is (= [["staging-qa" {:surface/env :env/staging :surface/scope :mcp-scope/qa} "nope"]]
         (:missing (prepare {:env/staging "nope"})))))
