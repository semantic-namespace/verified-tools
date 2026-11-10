(ns verified.launcher
  "A Claude Code session started under a policy made for it:

     bb claude --profile finance [--dry-run] [-- <claude args>]

   reads the profile (profiles/<name>.edn, in the vocabulary's terms) and the
   ai-exposure findings for the folder it starts in, asked of the router like
   any agent would (the launcher never reads a config itself), and writes the
   session's settings:

     permissions  deny the MCP servers the findings name as unverified
                  programs here (the profile may keep some), or every server
                  but the verified tools; deny reading files that hold keys;
                  deny the tools the profile names
     sandbox      on, required or preferred; reads of the home folder off
                  but the working directory; the network allowlist from the
                  providers holding what the profile reads, plus its extra
                  hosts; no escapes
     hooks        PreToolUse: the verified-tools policy (verified.hook) on
                  every call, built-in tools included

   then starts claude with them, the verified router as an MCP server, and
   prints what this session can and can't do."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def root (str (fs/canonicalize ".")))

(def key-files
  "Read rules for files that hold keys, whatever the profile."
  ["Read(~/.ssh/**)" "Read(~/.aws/**)" "Read(~/.gnupg/**)" "Read(~/.config/gcloud/**)" "Read(~/.kube/**)"
   "Read(~/.docker/config.json)" "Read(~/.netrc)" "Read(~/.claude.json)" "Read(**/.env)" "Read(**/.env.*)"
   "Read(**/secrets.edn)" "Read(**/*.pem)" "Read(**/id_rsa*)" "Read(**/id_ed25519*)"])

;; ---- asking the router, as an MCP client

(defn- ask-router
  "Calls tools on a router started with live-file: {tool-name rows}."
  [live-file artifact tools]
  (let [p (proc/process ["bb" "--config" (str (fs/path root "bb.edn")) "serve" "--live" (str live-file)
                         "--artifact" (str artifact) "--audit" (str (fs/path root "target" "launcher-audit"))]
                        {:dir root :err :string})
        w (io/writer (:in p)) r (io/reader (:out p))
        send! (fn [m] (.write w (str (json/generate-string m) "\n")) (.flush w)
                (json/parse-string (.readLine ^java.io.BufferedReader r) true))]
    (try
      (send! {:jsonrpc "2.0" :id 1 :method "initialize" :params {:capabilities {}}})
      (into {} (for [[i t] (map-indexed vector tools)
                     :let [res (send! {:jsonrpc "2.0" :id (+ 2 i) :method "tools/call" :params {:name t :arguments {}}})
                           {:keys [text]} (first (get-in res [:result :content]))]]
                 [t (if (get-in res [:result :isError]) {:error text} (json/parse-string text true))]))
      (finally (.close w) @p))))

;; ---- the session's settings

(defn- in-scope? [scope folder] (or (= scope "everywhere") (= scope (str "project " folder))))

(defn settings
  "The settings for a session under profile, in folder, given the
   connectors and findings ai-exposure gave, and the artifact's providers."
  [{:keys [mcp sandbox network deny-tools reads]} folder {:keys [connectors findings]} providers hook-cmd]
  (let [here (filter #(in-scope? (:connector/scope %) folder) connectors)
        unverified (set (mapcat :finding/connectors
                                (filter #(and (= "unverified-program" (:finding/rule %)) (in-scope? (:finding/scope %) folder))
                                        findings)))
        denied-servers (sort (distinct
                              (if (= :verified-only mcp)
                                (map :connector/name (remove #(= "verified" (:connector/kind %)) here))
                                (remove (set (:allow-unverified mcp)) unverified))))
        hosts (sort (distinct (concat (when (= :reads (:providers-for network))
                                        (for [[host holds] providers :when (some holds reads)] host))
                                      (:extra network))))]
    {:permissions {:deny (vec (concat (map #(str "mcp__" % "__*") denied-servers) key-files deny-tools))}
     :sandbox {:enabled true
               :failIfUnavailable (= :required sandbox)
               :allowUnsandboxedCommands false
               :filesystem {:denyRead ["~/"] :allowRead ["."]}
               :network {:allowedDomains (vec hosts)}}
     :hooks {:PreToolUse [{:matcher "*" :hooks [{:type "command" :command hook-cmd}]}]}}))

(defn- sandbox-ready?
  "Whether Claude Code's shell sandbox can run here: bubblewrap and socat on Linux."
  []
  (or (not= "Linux" (System/getProperty "os.name"))
      (every? #(fs/which %) ["bwrap" "socat"])))

(defn -main [& args]
  (let [[own claude-args] (split-with #(not= "--" %) args)
        claude-args (rest claude-args)
        opts (loop [[a & more] own o {}]
               (cond (nil? a) o
                     (= a "--dry-run") (recur more (assoc o :dry-run true))
                     :else (recur (rest more) (assoc o (keyword (subs a 2)) (first more)))))
        profile-name (or (:profile opts) "coding")
        profile (edn/read-string (slurp (str (fs/path root "profiles" (str profile-name ".edn")))))
        folder (str (fs/file-name (fs/canonicalize (or (:folder opts) (System/getProperty "user.dir")))))
        session-dir (fs/path root "target" "claude-sessions" (str profile-name "-" (System/currentTimeMillis)))
        artifact (fs/path root "artifact" "artifact.edn")
        live (fs/path session-dir "exposure-live.edn")
        _ (fs/create-dirs session-dir)
        _ (spit (str live) (pr-str {:roots {:mod/ai-exposure
                                            {:claude-code (str (fs/path (System/getProperty "user.home") ".claude.json"))
                                             :verified (str (fs/parent artifact))}}}))
        found (ask-router live artifact ["agent_connectors" "exposure_findings"])
        providers (:providers (:vocabulary (edn/read-string (slurp (str artifact)))))
        hook-cmd (str "bb --config " (fs/path root "bb.edn") " policy-hook --state-dir " (fs/path session-dir "hook"))
        s (settings profile folder {:connectors (get found "agent_connectors") :findings (get found "exposure_findings")}
                    providers hook-cmd)
        settings-file (fs/path session-dir "settings.json")
        mcp-file (fs/path session-dir "mcp.json")
        cmd (concat ["claude" "--settings" (str settings-file) "--mcp-config" (str mcp-file)]
                    (when (= :verified-only (:mcp profile)) ["--strict-mcp-config"])
                    claude-args)]
    (spit (str settings-file) (json/generate-string s {:pretty true}))
    (spit (str mcp-file) (json/generate-string {:mcpServers {:verified {:command "bash" :args ["-c" (str "cd " root " && exec bb serve")]}}}
                                               {:pretty true}))
    (println (str "profile " profile-name ": " (:doc profile)))
    (println (str "  folder        " folder))
    (println (str "  MCP servers   off: " (str/join ", " (or (seq (map #(subs % 5 (- (count %) 3))
                                                                       (filter #(str/starts-with? % "mcp__") (get-in s [:permissions :deny]))))
                                                              ["none"]))))
    (println (str "  network       " (str/join ", " (or (seq (get-in s [:sandbox :network :allowedDomains])) ["none"]))))
    (println (str "  never read    files holding keys (" (count key-files) " rules)"
                  (when (seq (:deny-tools profile)) (str "; tools off: " (str/join ", " (:deny-tools profile))))))
    (println (str "  every call    checked by the verified-tools policy (hook)"))
    (when (and (not= :verified-only (:mcp profile)) (some #(str/starts-with? % "mcp__") (get-in s [:permissions :deny])))
      (println "  note          servers turned off still load their tool definitions (deny stops calls, not loading)"))
    (println (str "  settings      " settings-file))
    (cond
      (and (= :required (:sandbox profile)) (not (sandbox-ready?)))
      (do (println "\nThis profile requires the shell sandbox, and it can't run here: Claude Code needs bubblewrap and socat on Linux (sudo apt install socat).")
          (System/exit 1))
      (:dry-run opts)
      (println (str "\n" (str/join " " cmd)))
      :else
      (do (when-not (sandbox-ready?)
            (println "\n(the shell sandbox can't run here: bubblewrap and socat are needed; the hook and permissions still apply)"))
          (proc/exec (vec cmd))))))
