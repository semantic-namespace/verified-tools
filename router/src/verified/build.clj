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
(def out-file "artifact/artifact.edn")

(defn- sha256 [^String s]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defn- file-of [p] (or (get-in p [:definition :file]) (get-in p [:impl :file]) (:file p)))

(defn- bin [name]
  (some #(let [f (io/file % name)] (when (.canExecute f) (.getCanonicalPath f)))
        (str/split (System/getenv "PATH") #":")))

(defn sandbox
  "The bubblewrap arguments a tool runs under: the system read-only, its own
   module's code and the SDK and runner read-only, no home directory, a
   private /tmp, and no network unless it declares egress to a provider and
   runs live (in replay it never needs any)."
  [{:keys [dir egress]} mode]
  (let [ro #(when (.exists (io/file %)) ["--ro-bind" % %])
        p #(str root "/" %)]
    (vec (concat ["bwrap" "--die-with-parent" "--new-session"]
                 (mapcat ro ["/usr" "/bin" "/lib" "/lib64" "/etc/alternatives" "/etc/ld.so.cache"])
                 (ro (bin "bb"))
                 (mapcat ro [(p "sdk/src") (p "router/src") (p (str "modules/" dir)) (p "artifact")])
                 ["--proc" "/proc" "--dev" "/dev" "--tmpfs" "/tmp" "--setenv" "HOME" "/tmp" "--chdir" root]
                 (when (or (= mode :replay) (not (contains? egress :egress/provider)))
                   ["--unshare-net"])))))

(defn proxy-allow
  "What the egress proxy lets through for a module, live: exactly its
   declared routes, method and path template, on their declared hosts."
  [decl]
  (vec (for [[_ {:keys [host routes]}] (sort-by key (:egress decl))
             [_ [method path]] (sort-by key routes)]
         {:host host :method method :path path})))

(defn -main [& [mode]]
  (let [mode (keyword (or mode "replay"))
        cfg (cli/config {:root root})
        _ ((requiring-resolve 'babashka.deps/add-deps)
           {:deps ((requiring-resolve 'meta-source.cli/under-root) (:deps cfg) root)})
        idx (index/build cfg)
        problems (index/check idx)
        es (:entities idx)
        vocab (some #(when (= :verified/vocabulary (get-in % [:entry :atlas/type])) (:entry %)) (vals es))
        session (some #(when (= :verified/session-policy (get-in % [:entry :atlas/type])) (:entry %)) (vals es))
        modules (for [[id e] (sort-by key es) :when (= :module (get-in e [:entry :verified/kind]))] [id (:entry e)])
        code-dirs (set (for [[_ e] es :when (= :code (get-in e [:entry :verified/kind]))] (get-in e [:entry :code/module-dir])))
        errors-in (fn [dir] (for [p problems
                                  :when (and (= :error (:severity p))
                                             (some-> (file-of p) (str/starts-with? (str "modules/" dir "/"))))]
                              (str/replace-first (hover/message idx p (first (:keys p))) #"^meta-source: " "")))
        global-errors (for [p problems :when (and (= :error (:severity p)) (not (some-> (file-of p) (str/starts-with? "modules/"))))]
                        (hover/message idx p nil))
        _ (when (seq global-errors)
            (binding [*out* *err*] (run! println global-errors))
            (throw (ex-info "the vocabulary itself has errors: no artifact" {})))
        verdicts (for [[mid m] modules
                       :let [dir (:module/dir m)
                             errs (vec (errors-in dir))]]
                   [mid m (seq (concat errs (when-not (code-dirs dir) ["it has no code"])))])
        decl-of (fn [dir] (edn/read-string (slurp (str "modules/" dir "/module.edn"))))
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
                            :sandbox (sandbox {:dir dir :egress (set (:egress t))} mode)
                            :proxy (proxy-allow decl)}]))
        artifact {:artifact/mode mode
                  :vocabulary (:vocabulary/version vocab)
                  :tools tools
                  :excluded (into (sorted-map) (for [[mid _ why] verdicts :when why] [mid why]))
                  :session {:blocks (:session/blocks session)
                            :redact (:redact/patterns session)}}
        text (with-out-str (pp/pprint artifact))]
    (io/make-parents out-file)
    (spit out-file text)
    (spit (str out-file ".sha256") (sha256 text))
    (println "artifact" out-file (subs (sha256 text) 0 12) ":" (count tools) "tools;"
             (count (:excluded artifact)) "modules excluded")
    (doseq [[mid why] (:excluded artifact)]
      (println "  excluded" mid (str "(" (count why) " reasons)"))
      (doseq [w why] (println "    -" w)))))
