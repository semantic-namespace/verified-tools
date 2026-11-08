(ns mod.gmail-receipts
  "Receipts from Gmail: the messages a declared query finds, read through
   declared routes, parsed here; only receipt rows leave the tool."
  (:require [verified.sdk.http :as http]
            [verified.sdk.out :as out]
            [verified.sdk.parse :as parse]
            [verified.sdk.query :as query]))

(defn- receipt-row [msg]
  (let [text (parse/text msg)]
    {:receipt/merchant (parse/sender-name msg)
     :receipt/date (parse/header msg "Date")
     :receipt/amount (parse/amount text)
     :receipt/currency (parse/currency text)
     :receipt/message-id (:id msg)}))

(defn ^{:module/tool :tool/receipts-search} receipts-search
  "Receipts between two dates."
  [{:keys [from to]}]
  (let [ids (->> (http/call :gmail/list-messages {:q (query/fill :receipts {:from from :to to})})
                 :messages
                 (map :id))]
    (out/rows :tool/receipts-search
              (keep #(receipt-row (http/call :gmail/get-message {:id %})) ids))))
