(ns verified.sdk.files
  "Files only under the module's declared roots, read-only:
   (list :notes) and (read :notes \"2026/oct.md\").

   A module declares a root by name and what it holds, never where it is:
     :files {:notes {:holds :data/note :extensions #{\"md\" \"txt\"}}}
   The user binds it to a folder (live.edn :roots); the sandbox mounts that
   folder read-only, and the runner tells the SDK where (*roots*). Paths
   are relative to the root, with no `..`; only the declared extensions;
   at most :max-bytes a file (1 MB) and :max-files a listing (2000)."
  (:refer-clojure :exclude [read list])
  (:require [clojure.string :as str]
            [verified.sdk :as sdk]))

(def ^:dynamic *roots* "root name -> the folder it is mounted at, bound by the runner." {})

(defn- root-of [root]
  (let [decl (get-in sdk/*module* [:files root])
        base (get *roots* root)]
    (when-not (map? decl) (sdk/refuse (str "root " root " is not declared") {:root root}))
    (when-not base (sdk/refuse (str "root " root " is not bound to a folder") {:root root}))
    (assoc decl :base base)))

(defn- allowed-ext? [{:keys [extensions]} name]
  (contains? (set extensions) (second (re-find #"\.([^./]+)$" name))))

(defn list
  "The files under root with a declared extension: relative paths, sorted."
  [root]
  (let [{:keys [base max-files] :as r} (root-of root)
        dir (java.io.File. ^String base)
        n (count (str (.getCanonicalPath dir) "/"))]
    (->> (file-seq dir)
         (filter #(and (.isFile ^java.io.File %) (allowed-ext? r (.getName ^java.io.File %))))
         (map #(subs (.getCanonicalPath ^java.io.File %) n))
         sort
         (take (or max-files 2000))
         vec)))

(defn read
  "The text of `rel` under the declared root `root`."
  [root rel]
  (let [{:keys [base max-bytes] :as r} (root-of root)]
    (when (or (not (string? rel)) (str/starts-with? rel "/") (some #{".."} (str/split rel #"/")))
      (sdk/refuse "a path is relative to its root, with no .." {:path rel}))
    (when-not (allowed-ext? r rel)
      (sdk/refuse (str rel " has no extension root " root " declares") {:path rel}))
    (let [f (java.io.File. ^String base ^String rel)
          canon (.getCanonicalPath f)]
      (when-not (str/starts-with? canon (str (.getCanonicalPath (java.io.File. ^String base)) "/"))
        (sdk/refuse "a path stays under its root" {:path rel}))
      (when (> (.length f) (or max-bytes 1000000))
        (sdk/refuse (str rel " is larger than root " root " allows") {:path rel}))
      (slurp f))))
