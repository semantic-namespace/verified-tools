(ns verified.build
  "The artifact: what the router may serve, built from what meta-source
   reads and checks. A module is in it only when it has code and no error
   is found in its declaration or its code; every other module is listed
   with why it is out. Each tool carries what the router needs to decide a
   call without reasoning: its arguments' types, its declared result, caps,
   aspects, and the sandbox and egress-proxy configuration its declaration
   compiles to. The session policy and redaction patterns come from the
   vocabulary. The artifact is EDN, written with its SHA-256, and diffed in
   git like any registry."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [meta-source.cli :as cli]
            [meta-source.hover :as hover]
            [meta-source.index :as index]))

(def root (.getCanonicalPath (io/file ".")))

(defn sha256 [^String s]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defn- file-of [p] (or (get-in p [:definition :file]) (get-in p [:impl :file]) (:file p)))

(defn sandbox
  "The bubblewrap arguments a tool runs under: the system read-only, its own
   module's code and the SDK and runner read-only, no home directory, a
   private /tmp, and no network, ever. A tool that declares egress reaches
   it through the router, over its stdio, and only on its declared routes.
   Machine paths are placeholders the router fills when it runs the tool,
   {root} {bb} {artifact-dir}, so the artifact is the same on any machine
   and two of them diff by what they allow, never by where they were built."
  [{:keys [dir]}]
  (let [sys #(when (.exists (io/file %)) ["--ro-bind" % %])
        ro (fn [p] ["--ro-bind" p p])]
    (vec (concat ["bwrap" "--die-with-parent" "--new-session"]
                 (mapcat sys ["/usr" "/bin" "/lib" "/lib64" "/etc/alternatives" "/etc/ld.so.cache"])
                 (mapcat ro ["{bb}" "{root}/sdk/src" "{root}/router/src" (str "{root}/modules/" dir) "{artifact-dir}"])
                 ["--proc" "/proc" "--dev" "/dev" "--tmpfs" "/tmp" "--setenv" "HOME" "/tmp" "--chdir" "{root}"
                  "--unshare-net"]))))

(defn proxy-allow
  "What the router lets through for a module, live: exactly its declared
   routes, method and path template, on their declared hosts, with the
   credential each names (the router holds it; the tool never does)."
  [decl]
  (vec (for [[_ {:keys [host routes auth]}] (sort-by key (:egress decl))
             [_ [method path]] (sort-by key routes)]
         (cond-> {:host host :method method :path path}
           auth (assoc :auth auth :scopes (get-in decl [:auth auth :scopes]))))))

(defn- read-edn [f] (let [f (io/file f)] (when (.exists f) (edn/read-string (slurp f)))))

(defn artifact-of
  "The artifact for the project at root-dir, in mode: what meta-source reads
   and checks there, the runnable tools, the excluded modules with their
   reasons, where each module came from (modules.lock.edn), and the
   vocabulary a reviewer needs to read it."
  [root-dir mode]
  (let [cfg (cli/config {:root root-dir})
        _ ((requiring-resolve 'babashka.deps/add-deps)
           {:deps ((requiring-resolve 'meta-source.cli/under-root) (:deps cfg) root-dir)})
        idx (index/build cfg)
        problems (index/check idx)
        es (:entities idx)
        vocab (some #(when (= :verified/vocabulary (get-in % [:entry :atlas/type])) (:entry %)) (vals es))
        session (some #(when (= :verified/session-policy (get-in % [:entry :atlas/type])) (:entry %)) (vals es))
        lock (or (read-edn (io/file root-dir "modules.lock.edn")) {})
        decl-of (fn [dir] (read-edn (io/file root-dir "modules" dir "module.edn")))
        modules (for [[id e] (sort-by key es) :when (= :module (get-in e [:entry :verified/kind]))] [id (:entry e)])
        code-dirs (set (for [[_ e] es :when (= :code (get-in e [:entry :verified/kind]))] (get-in e [:entry :code/module-dir])))
        errors-in (fn [dir] (for [p problems
                                  :when (and (= :error (:severity p))
                                             (some-> (file-of p) (str/starts-with? (str "modules/" dir "/"))))]
                              (str/replace-first (hover/message idx p (first (:keys p))) #"^meta-source: " "")))
        global-errors (for [p problems :when (and (= :error (:severity p)) (not (some-> (file-of p) (str/starts-with? "modules/"))))]
                        (hover/message idx p nil))
        _ (when (seq global-errors)
            (throw (ex-info (str "the vocabulary itself has errors: no artifact\n" (str/join "\n" global-errors)) {})))
        checked-tools (set (for [[id e] es :when (= :tool (get-in e [:entry :verified/kind]))] id))
        ;; a tool runs only if meta-source read it as a tool, so the policies
        ;; saw it: one the declaration lists but the reader didn't find
        ;; (written in a shape it doesn't read) keeps its module out
        unchecked (fn [dir] (for [tid (sort (keys (:tools (decl-of dir)))) :when (not (checked-tools tid))]
                              (str tid " wasn't read as a tool, so no policy has checked it:"
                                   " write :tools as a plain map, {:tool/x {...}}, not #:tool{...}")))
        verdicts (for [[mid m] modules
                       :let [dir (:module/dir m)
                             errs (vec (errors-in dir))]]
                   [mid m (seq (concat errs (unchecked dir) (when-not (code-dirs dir) ["it has no code"])))])
        read-dirs (set (map (comp :module/dir second) modules))
        ;; a module directory meta-source couldn't read is out, saying so:
        ;; never left out of the artifact silently
        unread (for [d (sort (map #(.getName ^java.io.File %) (or (.listFiles (io/file root-dir "modules")) [])))
                     :when (and (.isFile (io/file root-dir "modules" d "module.edn")) (not (read-dirs d)))]
                 [(keyword "mod" d) [(str "its module.edn couldn't be read as a declaration (modules/" d "/module.edn):"
                                         " write it as the examples are, one key per line, no #:ns{} maps")]])
        tools (into (sorted-map)
                    (for [[mid m why] verdicts :when (not why)
                          :let [dir (:module/dir m) decl (decl-of dir)
                                code (get-in es [(keyword "module-code" dir) :entry :code/defs])]
                          [tid t] (sort-by key (:tools decl))]
                      [tid {:module mid :dir dir
                            :var (some (fn [[v tool]] (when (= tool tid) v)) code)
                            :doc (:doc t) :args (:args t) :returns (:returns t) :caps (:caps t)
                            :reads (set (:reads t)) :effect (:effect t) :egress (set (:egress t))
                            :confirm (boolean (:confirm t))
                            :aspects (get-in es [tid :entry :atlas/aspects])
                            :sandbox (sandbox {:dir dir})
                            :proxy (proxy-allow decl)}]))]
    {:artifact/mode mode
     :vocabulary {:version (:vocabulary/version vocab)
                  :terms (:vocabulary/terms vocab)
                  :rank (:vocabulary/rank vocab)}
     :modules (into (sorted-map)
                    (for [[mid m _] verdicts :let [dir (:module/dir m)]]
                      [mid {:dir dir
                            :version (:module/version (decl-of dir))
                            :source (get-in lock [dir :source] :local)}]))
     :tools tools
     :excluded (into (sorted-map) (concat (for [[mid _ why] verdicts :when why] [mid (vec why)]) unread))
     :session {:blocks (:session/blocks session)
               :redact (:redact/patterns session)}}))

(defn write!
  "Writes an artifact and its SHA-256; returns the hash."
  [artifact out-file]
  (let [text (with-out-str (pp/pprint artifact))
        f (io/file (.getAbsolutePath (io/file out-file)))]
    (io/make-parents f)
    (spit f text)
    (spit (str f ".sha256") (sha256 text))
    (sha256 text)))

(defn -main
  "bb build [replay|live] [out-file]"
  [& [mode out-file]]
  (let [mode (keyword (or mode "replay"))
        out-file (or out-file "artifact/artifact.edn")
        artifact (try (artifact-of root mode)
                      (catch clojure.lang.ExceptionInfo e
                        (binding [*out* *err*] (println (ex-message e)))
                        (throw e)))
        hash (write! artifact out-file)]
    (println "artifact" out-file (subs hash 0 12) ":" (count (:tools artifact)) "tools;"
             (count (:excluded artifact)) "modules excluded")
    (doseq [[mid why] (:excluded artifact)]
      (println "  excluded" mid (str "(" (count why) " reasons)"))
      (doseq [w why] (println "    -" w)))))
