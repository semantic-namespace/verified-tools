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
(defn- json-resp [status body] {:status status :headers {"content-type" "application/json"} :body (json/generate-string body)})

;; ---- OAuth, as Google's installed-app flow answers it: a consent page that
;; grants at once, and a token endpoint checking the client and PKCE
(def client {:client-id "fake-client.apps.example" :client-secret "fake-secret"})
(def oauth (atom {:codes {} :refresh {} :access #{} :n 0 :ttl 3600}))

(defn- params [s]
  (into {} (for [kv (str/split (str s) #"&") :let [[k v] (str/split kv #"=" 2)] :when (seq k)]
             [(keyword k) (java.net.URLDecoder/decode (str v) "UTF-8")])))

(defn- s256 [v]
  (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder))
                   (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes ^String v "US-ASCII"))))

(defn- issue! [scope]
  (let [n (:n (swap! oauth update :n inc))
        access (str "fake-access-" n)]
    (swap! oauth update :access conj access)
    {:access_token access :expires_in (:ttl @oauth) :scope scope :token_type "Bearer"}))

(defn- oauth-handler [uri query form]
  (case uri
    "/oauth/auth"
    (let [{:keys [redirect_uri state code_challenge scope client_id]} (params query)
          code (str "code-" (rand-int 1000000))]
      (if (not= client_id (:client-id client))
        {:status 400 :body "unknown client"}
        (do (swap! oauth assoc-in [:codes code] {:challenge code_challenge :scope scope :redirect redirect_uri})
            {:status 302 :headers {"location" (str redirect_uri "?code=" code "&state=" state)}})))
    "/oauth/token"
    (let [{:keys [grant_type code code_verifier refresh_token client_id client_secret redirect_uri]} (params form)]
      (cond
        (not= [client_id client_secret] [(:client-id client) (:client-secret client)])
        (json-resp 401 {:error "invalid_client"})
        (= "authorization_code" grant_type)
        (let [{:keys [challenge scope redirect]} (get-in @oauth [:codes code])]
          (if (and challenge (= challenge (s256 (str code_verifier))) (= redirect redirect_uri))
            (let [rt (str "fake-refresh-" (rand-int 1000000))]
              (swap! oauth #(-> % (update :codes dissoc code) (assoc-in [:refresh rt] scope)))
              (json-resp 200 (assoc (issue! scope) :refresh_token rt)))
            (json-resp 400 {:error "invalid_grant" :error_description "bad code or verifier"})))
        (= "refresh_token" grant_type)
        (if-let [scope (get-in @oauth [:refresh refresh_token])]
          (json-resp 200 (issue! scope))
          (json-resp 400 {:error "invalid_grant" :error_description "unknown refresh token"}))
        :else (json-resp 400 {:error "unsupported_grant_type"})))
    ;; for tests: every access token issued so far stops working, as when
    ;; they expire
    "/oauth/test/expire"
    (do (swap! oauth assoc :access #{}) (json-resp 200 {}))
    nil))
(def log-file (atom "audit/provider.log"))

(defn- log! [m]
  (clojure.java.io/make-parents @log-file)
  (spit @log-file (str (pr-str (assoc m :at (str (java.time.Instant/now)))) "\n") :append true))

(defn handler [{:keys [request-method uri query-string headers body]}]
  (let [bearer (some->> (get headers "authorization") (re-find #"^Bearer (.*)$") second)
        authed (or (= bearer token) (contains? (:access @oauth) bearer))
        sent (some-> body slurp)]
    (log! {:method request-method :uri uri :query query-string :authorized authed :body sent})
    (cond
      (str/starts-with? uri "/oauth/") (or (oauth-handler uri query-string sent) (json-resp 404 {:error "not found"}))
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
