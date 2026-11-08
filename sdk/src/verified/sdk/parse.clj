(ns verified.sdk.parse
  "Pure helpers for what a provider returns: a Gmail message's headers and
   text, an amount and a currency in it. No I/O."
  (:require [clojure.string :as str]))

(defn header [msg n]
  (some #(when (= (str/lower-case n) (str/lower-case (:name %))) (:value %)) (get-in msg [:payload :headers])))

(defn- b64url [s]
  (String. (.decode (java.util.Base64/getUrlDecoder) ^String s) "UTF-8"))

(defn text
  "The message's text: its body, or its text/plain part's."
  [msg]
  (let [p (:payload msg)
        data (or (get-in p [:body :data])
                 (some #(when (= "text/plain" (:mimeType %)) (get-in % [:body :data])) (:parts p)))]
    (some-> data b64url)))

(defn sender-name [msg]
  (when-let [from (header msg "From")]
    (str/trim (str/replace (str/replace from #"<[^>]*>" "") "\"" ""))))

(defn amount
  "The first total in `s`: 34,80 or 34.80, as a number."
  [s]
  (when-let [[_ v] (and s (re-find #"(?i)total[^0-9]*([0-9]+[.,][0-9]{2})" s))]
    (parse-double (str/replace v "," "."))))

(defn currency [s]
  (cond (nil? s) nil (re-find #"€|EUR" s) "EUR" (re-find #"\$|USD" s) "USD" (re-find #"£|GBP" s) "GBP"))
