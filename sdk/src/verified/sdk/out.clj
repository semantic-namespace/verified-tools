(ns verified.sdk.out
  "A tool's result: (rows :tool/x rows) keeps only the keys :tool/x
   declares it returns, and at most its declared per-call cap; when there
   were more, it says so (*more*), looking one row past the cap, never
   computing the rest."
  (:require [verified.sdk :as sdk]))

(defn rows
  "`rows` as the tool's declared result."
  [tool rows]
  (when (not= tool sdk/*tool*)
    (sdk/refuse (str "rows for " tool " from " sdk/*tool*) {:tool tool}))
  (let [{:keys [returns caps]} (get-in sdk/*module* [:tools tool])]
    (let [cap (or (:per-call caps) 0)
          seen (vec (take (inc cap) rows))]
      (when (and sdk/*more* (> (count seen) cap)) (reset! sdk/*more* true))
      (vec (take cap (map #(select-keys % returns) seen))))))
