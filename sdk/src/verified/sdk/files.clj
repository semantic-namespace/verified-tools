(ns verified.sdk.files
  "Files only under the module's declared roots, read-only: (read :notes
   \"2026/oct.md\"). No absolute paths, no `..`."
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str]
            [verified.sdk :as sdk]))

(defn read
  "The text of `rel` under the declared root `root`."
  [root rel]
  (let [base (get-in sdk/*module* [:files root])]
    (when-not base (sdk/refuse (str "root " root " is not declared") {:root root}))
    (when (or (str/starts-with? rel "/") (some #{".."} (str/split rel #"/")))
      (sdk/refuse "a path is relative to its root, with no .." {:path rel}))
    (slurp (str base "/" rel))))
