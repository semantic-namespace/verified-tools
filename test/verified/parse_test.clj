(ns verified.parse-test
  "The SDK's parsers, on made-up messages shaped like real ones: nested
   multipart, HTML only, and totals written the Spanish and English ways."
  (:require [clojure.test :refer [deftest is are]]
            [verified.sdk.parse :as parse]))

(defn- b64 [s] (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) (.getBytes ^String s "UTF-8")))

(defn- msg [from payload]
  {:payload (assoc payload :headers [{:name "From" :value from}])})

(deftest text-from-nested-parts
  (is (= "Total: 12,30 EUR"
         (parse/text (msg "A <a@x.es>" {:mimeType "multipart/mixed"
                                       :parts [{:mimeType "multipart/alternative"
                                                :parts [{:mimeType "text/html" :body {:data (b64 "<p>html</p>")}}
                                                        {:mimeType "text/plain" :body {:data (b64 "Total: 12,30 EUR")}}]}]})))
      "the plain part, however deep")
  (is (= "Importe total 45,60€"
         (parse/text (msg "A <a@x.es>" {:mimeType "multipart/alternative"
                                       :parts [{:mimeType "text/html"
                                                :body {:data (b64 "<html><style>p{}</style><p>Importe&nbsp;total</p><td>45,60&euro;</td></html>")}}]})))
      "else the HTML part's visible text"))

(deftest amounts
  (are [s n] (= n (parse/amount s))
    "Total: 34,80 EUR" 34.8
    "Importe total: 1.234,56 €" 1234.56
    "Grand total $1,234.56" 1234.56
    "Amount due: 19.99 USD" 19.99
    "Factura n.º 2026-0042 ... Total a pagar 58,07 €" 58.07
    "Su pedido: 2 artículos, 23,50 €" 23.5
    "EUR 9.99 cargado" 9.99
    "No amount here, order 123456" nil))

(deftest sender-names
  (are [from nm] (= nm (parse/sender-name (msg from {})))
    "\"Renfe\" <billetes@renfe.com>" "Renfe"
    "donotreply@sixt.com" "sixt.com"
    "<facturas@endesa.es>" "endesa.es"
    "\"info@islamagica.es\" <info@islamagica.es>" "islamagica.es"))
