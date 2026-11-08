(ns verified.sdk.out
  "A tool's result: (rows :tool/x rows) keeps only the keys :tool/x
   declares it returns, and at most its declared per-call cap."
  (:require [verified.sdk :as sdk]))

(defn rows
  "`rows` as the tool's declared result."
  [tool rows]
  (when (not= tool sdk/*tool*)
    (sdk/refuse (str "rows for " tool " from " sdk/*tool*) {:tool tool}))
  (let [{:keys [returns caps]} (get-in sdk/*module* [:tools tool])]
    (vec (take (or (:per-call caps) 0) (map #(select-keys % returns) rows)))))
