(ns verified.sdk.parse
  "Pure helpers for what a provider returns: a Gmail message's headers and
   text, an amount and a currency in it. No I/O."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(defn header [msg n]
  (some #(when (= (str/lower-case n) (str/lower-case (:name %))) (:value %)) (get-in msg [:payload :headers])))

(defn- b64url [s]
  (String. (.decode (java.util.Base64/getUrlDecoder) ^String s) "UTF-8"))

(defn- parts
  "Every part of a message, depth first, the payload itself included."
  [p]
  (cons p (mapcat parts (:parts p))))

(def ^:private entities
  {"&nbsp;" " " "&euro;" "€" "&amp;" "&" "&lt;" "<" "&gt;" ">" "&quot;" "\"" "&#39;" "'" "&#8364;" "€"})

(defn html->text
  "Visible text of an HTML body: no styles, scripts or tags; the common
   entities decoded; whitespace collapsed."
  [html]
  (-> html
      (str/replace #"(?is)<(style|script|head)[^>]*>.*?</\1>" " ")
      (str/replace #"(?s)<[^>]+>" " ")
      (str/replace #"&[a-z]+;|&#[0-9]+;" #(get entities % " "))
      (str/replace #"\s+" " ")
      str/trim))

(defn text
  "The message's text: its first text/plain part anywhere in it, else its
   first text/html part as visible text."
  [msg]
  (let [ps (filter #(get-in % [:body :data]) (parts (:payload msg)))
        of-type (fn [t] (some #(when (= t (:mimeType %)) (b64url (get-in % [:body :data]))) ps))]
    (or (of-type "text/plain")
        (some-> (of-type "text/html") html->text)
        (some-> (first ps) (get-in [:body :data]) b64url))))

(defn sender-name
  "The From header's display name, else its address's domain."
  [msg]
  (when-let [from (header msg "From")]
    (let [nm (str/trim (str/replace (str/replace from #"<[^>]*>" "") "\"" ""))]
      (if (or (str/blank? nm) (str/includes? nm "@"))
        (some->> (re-find #"@([A-Za-z0-9.-]+)" from) second)
        nm))))

(defn- number
  "1.234,56 · 1,234.56 · 34,80 · 34.80 · 1234 as a number: the last
   separator followed by two digits is the decimal one."
  [s]
  (let [s (str/replace s #"\s" "")
        [_ int-part dec-part] (re-matches #"(.*?)(?:[.,](\d{2}))?" s)]
    (some-> (str/replace int-part #"[.,]" "") not-empty
            (str (when dec-part (str "." dec-part)))
            parse-double)))

(def ^:private num-re "(\\d{1,3}(?:[.,\\s]\\d{3})*(?:[.,]\\d{2})?|\\d+(?:[.,]\\d{2})?)")

(def ^:private total-words
  "total|importe|amount due|amount paid|a pagar|grand total|cargo|charged|paid")

(defn amount
  "The total in `s`, as a number: the first amount after a total-like word
   (total, importe, amount due, a pagar…), else the first amount written
   next to a currency (€ 12,34 · 12,34 € · EUR 12.34 · $12.34)."
  [s]
  (when s
    (let [after-word (re-find (re-pattern (str "(?i)(?:" total-words ")[^0-9]{0,40}?" num-re)) s)
          by-currency (or (re-find (re-pattern (str "(?:€|EUR|USD|\\$|£)\\s?" num-re)) s)
                          (re-find (re-pattern (str num-re "\\s?(?:€|EUR|USD|£)")) s))]
      (some-> (second (or after-word by-currency)) number))))

(defn currency [s]
  (cond (nil? s) nil (re-find #"€|EUR" s) "EUR" (re-find #"\$|USD" s) "USD" (re-find #"£|GBP" s) "GBP"))

(defn json
  "JSON text as data, keys as strings (a file's keys are the file's, never
   keywords minted from it); nil when it isn't JSON."
  [s]
  (try (json/parse-string s) (catch Exception _ nil)))
