(ns verified.sdk.bundle
  "Data a module ships with its code, read-only: a scoring config, a model,
   a word list. A module declares each file by name,
     :bundle {:model \"data/model.edn\"}
   and reads it by that name, (read-edn :model). The path is the module's
   own, relative, with no `..`; at most :max-bytes (10 MB). The module's
   directory is mounted read-only in its sandbox; nothing else is."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [verified.sdk :as sdk]))

(def ^:dynamic *dir* "The module's own directory, bound by the runner." nil)

(defn read-edn
  "The EDN data of the bundled file declared as `k`."
  [k]
  (let [decl (get-in sdk/*module* [:bundle k])
        path (if (map? decl) (:path decl) decl)
        max-bytes (or (when (map? decl) (:max-bytes decl)) 10000000)]
    (when-not (string? path) (sdk/refuse (str "bundle " k " is not declared") {:bundle k}))
    (when (or (str/starts-with? path "/") (some #{".."} (str/split path #"/")))
      (sdk/refuse "a bundled file is relative to its module, with no .." {:path path}))
    (when-not *dir* (sdk/refuse "the module's directory is not bound" {}))
    (let [f (java.io.File. ^String *dir* ^String path)]
      (when-not (str/starts-with? (.getCanonicalPath f) (str (.getCanonicalPath (java.io.File. ^String *dir*)) "/"))
        (sdk/refuse "a bundled file stays in its module" {:path path}))
      (when (> (.length f) max-bytes)
        (sdk/refuse (str "bundle " k " is larger than it may be") {:bundle k}))
      (edn/read-string (slurp f)))))
