(ns fake-provider
  "A stand-in for Gmail and the paste service, for the live path without a
   real account: it answers the declared routes from the modules' recorded
   responses, as JSON over HTTP, wants a bearer token for Gmail, and logs
   what it was sent to audit/provider.log. live.edn points the hosts here.

     bb fake-provider [port]   (default 8765; token: dev/fake.token)"
  (:require [cheshire.core :as json]
            [clojure.java.io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [org.httpkit.server :as server]))

(def gmail (edn/read-string (slurp "modules/gmail-receipts/fixtures.edn")))
(def token (str/trim (slurp "dev/fake.token")))
(def pastes (atom 0))
(def log-file (atom "audit/provider.log"))

(defn- log! [m]
  (clojure.java.io/make-parents @log-file)
  (spit @log-file (str (pr-str (assoc m :at (str (java.time.Instant/now)))) "\n") :append true))
(defn- json-resp [status body] {:status status :headers {"content-type" "application/json"} :body (json/generate-string body)})

(defn handler [{:keys [request-method uri query-string headers body]}]
  (let [authed (= (get headers "authorization") (str "Bearer " token))
        sent (some-> body slurp)]
    (log! {:method request-method :uri uri :query query-string :authorized authed :body sent})
    (cond
      (str/starts-with? uri "/gmail/")
      (cond
        (not authed) (json-resp 401 {:error "unauthorized"})
        (= uri "/gmail/v1/users/me/messages") (json-resp 200 (get-in gmail [:gmail/list-messages :default]))
        :else (if-let [m (get-in gmail [:gmail/get-message (last (str/split uri #"/"))])]
                (json-resp 200 m)
                (json-resp 404 {:error "not found"})))
      (and (= :post request-method) (= uri "/api/snippets"))
      (json-resp 201 {:url (str "https://paste.example/s/" (swap! pastes inc))})
      :else (json-resp 404 {:error "not found"}))))

(defn -main [& [port]]
  (let [port (parse-long (or port "8765"))]
    (server/run-server handler {:port port :ip "127.0.0.1"})
    (println "fake provider on" port)
    @(promise)))
