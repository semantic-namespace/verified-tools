(ns verified.export-launcher
  "A Claude Code session under a profile a system declares about itself: an
   :atlas/llm-profile export (atlas.ontology.llm-profile/export), from a
   file or from the CI build of the code an environment runs.

     bb claude --export <file> --profile llm-profile/yorba-qa-staging [--dry-run]
     bb claude --from-ci RucaHQ/yorba-clj@<branch-or-commit> --profile … [--dry-run]

   atlas.adapter.claude-code turns the profile into the session's rules and
   MCP servers: each tool's exact allow, ask or deny; the profile's command
   patterns and hosts; one server per surface, the user's server for that
   environment pointed at the surface's partition. To that this adds what a
   registry doesn't know: files holding keys are never read, the sandbox's
   filesystem, and the verified-tools policy hook on every call.

   surfaces.edn is the user's, not in git: which of their servers is each
   environment's, {:env/staging \"staging-yorba-mcp\"}, or {:server … :rescope
   false} for a server whose partition path can't be used. The copied
   server entries carry the user's own credentials: they're written to the
   session's folder, readable by the user only, and never printed."
  (:require [atlas.adapter.claude-code :as cc]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn claude-servers
  "Every MCP server entry in a ~/.claude.json by name: the ones that apply
   everywhere first, then each project's."
  [claude-json]
  (let [m (json/parse-string (slurp (str claude-json)))]
    (reduce (fn [acc [n s]] (if (contains? acc n) acc (assoc acc n s)))
            {}
            (concat (get m "mcpServers")
                    (mapcat (fn [[_ p]] (get p "mcpServers")) (get m "projects"))))))

(defn from-ci!
  "The export the latest successful atlas-registry build at ref uploaded
   (the llm-profiles artifact), downloaded into dir with gh: its path."
  [repo-at-ref dir]
  (let [[repo ref] (str/split repo-at-ref #"@" 2)
        sha? (re-matches #"[0-9a-f]{7,40}" (str ref))
        runs (json/parse-string
              (:out (proc/sh "gh" "run" "list" "-R" repo "--workflow" "atlas-registry.yml" "--status" "success"
                             "--limit" "50" "--json" "databaseId,headSha,headBranch")) true)
        run (first (filter #(if sha? (str/starts-with? (:headSha %) ref) (= ref (:headBranch %))) runs))]
    (when-not run
      (throw (ex-info (str "no successful atlas-registry build of " repo " at " ref) {})))
    (fs/create-dirs dir)
    (let [{:keys [exit err]} (proc/sh "gh" "run" "download" (str (:databaseId run)) "-R" repo "-n" "llm-profiles" "-D" (str dir))]
      (when-not (zero? exit) (throw (ex-info (str "downloading the llm-profiles artifact: " (str/trim err)) {}))))
    {:path (str (fs/path dir "llm-profiles-export.edn")) :run (:databaseId run) :sha (:headSha run)}))

(defn prepare
  "Writes the session's settings and MCP config under session-dir; returns
   what was written and what it holds."
  [{:keys [export profile surfaces-file claude-json session-dir key-files hook-cmd]}]
  (let [x (edn/read-string (slurp (str export)))
        pid (keyword (str/replace profile #"^:" ""))
        p (or (get x pid) (throw (ex-info (str "the export has no profile " pid ": " (str/join ", " (keys x))) {})))
        env->server (edn/read-string (slurp (str surfaces-file)))
        {:keys [mcp-config missing]} (cc/mcp-config p env->server (claude-servers claude-json))
        s (-> (cc/settings p)
              (update-in [:permissions :deny] (fnil into []) key-files)
              (update :sandbox merge {:enabled true :failIfUnavailable false :allowUnsandboxedCommands false
                                      :filesystem {:denyRead ["~/"] :allowRead ["."]}})
              (assoc :hooks {:PreToolUse [{:matcher "*" :hooks [{:type "command" :command hook-cmd}]}]}))
        settings-file (fs/path session-dir "settings.json")
        mcp-file (fs/path session-dir "mcp.json")]
    (fs/create-dirs session-dir)
    (spit (str settings-file) (json/generate-string s {:pretty true}))
    (spit (str mcp-file) "")
    (fs/set-posix-file-permissions (str mcp-file) "rw-------")
    (spit (str mcp-file) (json/generate-string mcp-config {:pretty true}))
    {:settings-file settings-file :mcp-file mcp-file :profile p :pid pid :settings s
     :servers (vec (for [sf (:surfaces p)
                         :let [spec (get env->server (:surface/env sf))]]
                     [(cc/server-name sf) (:surface/env sf)
                      (if (and (map? spec) (false? (:rescope spec)))
                        (str "its own path, from your " (:server spec) " (not confined to " (cc/scoped-path (:surface/scope sf)) ")")
                        (str (cc/scoped-path (:surface/scope sf)) ", from your " (if (map? spec) (:server spec) spec)))]))
     :counts (frequencies (mapcat (comp vals :tools) (:surfaces p)))
     :missing missing}))
