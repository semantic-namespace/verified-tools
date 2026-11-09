(ns verified.install
  "Installing a module: the user's decision, made on what the module would
   be allowed to do, never on what it says it does.

     bb add <git-url> <sha> [--path dir-in-repo] [--yes | --dry-run]
     bb remove <module-dir> [--yes]

   `add` fetches the module at exactly that commit (a full SHA, never a
   branch), checks it with the vocabulary and every installed module in a
   staged copy of the project, and shows: its tools in plain words, from
   the staged artifact; why it would be excluded, if it would; and what
   changes against what is installed, each change marked ▲ when it lets a
   tool reach further. Nothing in the project changes until the user says
   yes. Then the module is copied into modules/, its source and SHA are
   recorded in modules.lock.edn, and the artifact is rebuilt."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [verified.build :as build]
            [verified.diff :as diff]))

(def ^:private tool-root
  "This checkout: its bb.edn runs the builds."
  (str (fs/canonicalize ".")))

(defn artifact-in
  "The artifact of the project at dir, built by a bb of its own whose
   working directory is dir: each project read where it stands, and a
   staged one never mixed with the project it was copied from."
  [dir mode]
  (let [{:keys [exit out err]}
        @(proc/process ["bb" "--config" (str (fs/path tool-root "bb.edn")) "-e"
                        (str "(require 'verified.build) (prn (verified.build/artifact-of \".\" " mode "))")]
                       {:dir (str dir) :out :string :err :string})]
    (when-not (zero? exit)
      (throw (ex-info (str "building the artifact of " dir " failed: "
                           (or (some->> err (re-find #"(?m)^Message:\s+(.*)$") second) err))
                      {})))
    (edn/read-string out)))

(defn- read-edn [f] (when (fs/exists? f) (edn/read-string (slurp (str f)))))

(defn- mode-of [root]
  (or (:artifact/mode (read-edn (fs/path root "artifact" "artifact.edn"))) :replay))

(defn- git [& args]
  (let [{:keys [exit out err]} @(proc/process (vec (cons "git" args)) {:out :string :err :string})]
    (when-not (zero? exit) (throw (ex-info (str "git " (first args) ": " (str/trim err)) {})))
    (str/trim out)))

(defn fetch!
  "The module at exactly sha, from url, into a fresh directory: its path."
  [url sha path into]
  (when-not (re-matches #"[0-9a-f]{40}" (str sha))
    (throw (ex-info (str "a module is pinned by its full commit SHA, 40 hex digits; " (pr-str sha) " isn't one") {})))
  (fs/delete-tree into)
  (git "clone" "--quiet" "--no-checkout" url (str into))
  (git "-C" (str into) "checkout" "--quiet" sha)
  (when (not= sha (git "-C" (str into) "rev-parse" "HEAD"))
    (throw (ex-info "the checkout isn't the commit asked for" {})))
  (let [dir (if path (fs/path into path) into)]
    (when-not (fs/exists? (fs/path dir "module.edn"))
      (throw (ex-info (str "no module.edn at " (or path "the repository's root")) {})))
    dir))

(defn- copy-module! [from to]
  (fs/delete-tree to)
  (fs/create-dirs to)
  (doseq [f (fs/list-dir from) :when (not= ".git" (str (fs/file-name f)))]
    (if (fs/directory? f) (fs/copy-tree f (fs/path to (fs/file-name f))) (fs/copy f to))))

(defn- write-lock! [root lock]
  (spit (str (fs/path root "modules.lock.edn"))
        (str ";; Where each installed module came from: bb add writes it, bb remove\n"
             ";; drops it. A module not listed here is local, part of this repository.\n"
             (with-out-str (pp/pprint (into (sorted-map) lock))))))

(defn stage!
  "A copy of the project at root with lock changed by f and, when given,
   module-dir as modules/<dir> (or with modules/<dir> gone, :remove)."
  [root stage dir module-dir f]
  (fs/delete-tree stage)
  (fs/create-dirs stage)
  (doseq [p ["vocab" "packs"]] (fs/copy-tree (fs/path root p) (fs/path stage p)))
  (fs/copy (fs/path root "meta-source.edn") stage)
  (doseq [m (fs/list-dir (fs/path root "modules")) :when (not= dir (str (fs/file-name m)))]
    (fs/copy-tree m (fs/path stage "modules" (fs/file-name m))))
  (when (and module-dir (not= :remove module-dir))
    (copy-module! module-dir (fs/path stage "modules" dir)))
  (write-lock! stage (f (or (read-edn (fs/path root "modules.lock.edn")) {})))
  stage)

(defn- ask [question]
  (print (str question " [y/N] "))
  (flush)
  (contains? #{"y" "yes"} (some-> (read-line) str/trim str/lower-case)))

(defn- install! [root stage dir mode]
  (if (fs/exists? (fs/path stage "modules" dir))
    (copy-module! (fs/path stage "modules" dir) (fs/path root "modules" dir))
    (fs/delete-tree (fs/path root "modules" dir)))
  (fs/copy (fs/path stage "modules.lock.edn") (fs/path root "modules.lock.edn") {:replace-existing true})
  (let [artifact (artifact-in root mode)
        hash (build/write! artifact (str (fs/path root "artifact" "artifact.edn")))]
    (println "installed; artifact" (subs hash 0 12) ":" (count (:tools artifact)) "tools")
    artifact))

(defn add!
  "Fetches, checks, shows, and installs on yes. Returns {:installed bool
   :excluded reasons :changes [...]}."
  [{:keys [root url sha path yes dry-run]}]
  (let [root (str (fs/canonicalize root))
        ;; a local repository is recorded by its full path, so the lock
        ;; means the same from anywhere
        url (if (fs/directory? url) (str (fs/canonicalize url)) url)
        work (fs/path root "target" "install")
        module-dir (fetch! url sha path (fs/path work "fetch"))
        decl (read-edn (fs/path module-dir "module.edn"))
        mid (or (:module/id decl) (throw (ex-info "its module.edn has no :module/id: nothing to install it as" {})))
        dir (name mid)
        mode (mode-of root)
        stage (stage! root (fs/path work "stage") dir module-dir
                      #(assoc % dir {:source {:git/url url :git/sha sha}}))
        old (artifact-in root mode)
        new (artifact-in stage mode)
        cs (diff/changes old new)
        why (or (get-in new [:excluded mid])
                (get-in new [:excluded (keyword "mod" dir)])
                (when-not (get-in new [:modules mid]) ["meta-source didn't read it as a module"]))]
    (println (str "\n" mid " " (:module/version decl) " from " url " @ " (subs sha 0 12) "\n"))
    (if why
      (do (println "It can't be installed: meta-source finds")
          (doseq [w why] (println "  -" w))
          {:installed false :excluded why :changes cs})
      (do (doseq [tid (sort (keys (:tools new))) :when (= mid (get-in new [:tools tid :module]))]
            (println (diff/describe new tid) "\n"))
          (println (str "Against what is installed (" (count (:tools old)) " tools):"))
          (println (diff/render cs) "\n")
          (cond
            dry-run {:installed false :changes cs}
            (or yes (ask (str "Install " mid " " (:module/version decl) "?")))
            (do (install! root stage dir mode) {:installed true :changes cs})
            :else (do (println "nothing installed") {:installed false :changes cs}))))))

(defn remove!
  "Shows what goes with the module, and removes it on yes."
  [{:keys [root dir yes]}]
  (let [root (str (fs/canonicalize root))
        _ (when-not (fs/exists? (fs/path root "modules" dir))
            (throw (ex-info (str "no module installed as " dir) {})))
        mode (mode-of root)
        stage (stage! root (fs/path root "target" "install" "stage") dir :remove #(dissoc % dir))
        old (artifact-in root mode)
        new (artifact-in stage mode)
        cs (diff/changes old new)]
    (println (diff/render cs) "\n")
    (if (or yes (ask (str "Remove " dir "?")))
      (do (install! root stage dir mode) {:removed true :changes cs})
      {:removed false :changes cs})))

(defn- opts [args]
  (loop [[a & more :as args] args, pos [], o {}]
    (cond (empty? args) [pos o]
          (= a "--yes") (recur more pos (assoc o :yes true))
          (= a "--dry-run") (recur more pos (assoc o :dry-run true))
          (= a "--path") (recur (rest more) pos (assoc o :path (first more)))
          :else (recur more (conj pos a) o))))

(defn add-main [& args]
  (let [[[url sha] o] (opts args)]
    (try (add! (merge o {:root "." :url url :sha sha}))
         (catch clojure.lang.ExceptionInfo e (println (ex-message e)) (System/exit 1)))))

(defn remove-main [& args]
  (let [[[dir] o] (opts args)]
    (try (remove! (merge o {:root "." :dir dir}))
         (catch clojure.lang.ExceptionInfo e (println (ex-message e)) (System/exit 1)))))
