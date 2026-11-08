(ns verified.sdk.query
  "Search queries only from the module's declared templates, their holes
   filled with typed values: (fill :receipts {:from \"2026-10-01\" ...}).
   An agent's free text can't become a query."
  (:require [clojure.string :as str]
            [verified.sdk :as sdk]))

(defn- date [v]
  (when-not (and (string? v) (re-matches #"\d{4}-\d{2}-\d{2}" v))
    (sdk/refuse "a date is YYYY-MM-DD" {:value v}))
  (str/replace v "-" "/"))

(defn fill
  "The declared query `k`, with its holes filled from `args` as the tool's
   declared :args types say."
  [k args]
  (let [template (get-in sdk/*module* [:queries k])
        types (get-in sdk/*module* [:tools sdk/*tool* :args])]
    (when-not template (sdk/refuse (str "query " k " is not declared") {:query k}))
    (str/replace template #"\{([a-z-]+)\}"
                 (fn [[_ h]]
                   (let [hk (keyword h) v (get args hk)]
                     (case (get types hk)
                       :date (date v)
                       (sdk/refuse (str "hole {" h "} has no declared type") {:hole h})))))))
