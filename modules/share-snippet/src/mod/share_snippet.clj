(ns mod.share-snippet
  "Posts a text through the declared route, returns its link."
  (:require [verified.sdk.http :as http]
            [verified.sdk.out :as out]))

(defn ^{:module/tool :tool/share-snippet} share-snippet
  [{:keys [text]}]
  (out/rows :tool/share-snippet [{:share/url (:url (http/call :paste/create {:text text}))}]))
