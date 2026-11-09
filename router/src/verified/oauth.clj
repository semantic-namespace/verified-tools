(ns verified.oauth
  "Credentials the user grants once, and the router uses: OAuth 2 for an
   installed app, with the user's own client (a Desktop-app client they
   create), never a third party's.

     bb auth google [--no-browser]

   asks for exactly the scopes the artifact's tools declare for that
   credential, on the provider's consent page, receives the answer on a
   loopback port (PKCE, a fresh state), and keeps the refresh token in
   secrets.edn, readable by the user only. The router turns it into short
   access tokens as it needs them (refresh!), and a tool never holds one.

   secrets.edn, per credential:
     {:google {:client-id \"…apps.googleusercontent.com\" :client-secret \"…\"
               :refresh-token \"1//…\" :scopes #{\"…/gmail.readonly\"}}}"
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as proc]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [org.httpkit.server :as server]))

(def providers
  "Where each credential's provider asks and answers. live.edn :oauth
   overrides them (the fake provider, in tests)."
  {:google {:auth-uri "https://accounts.google.com/o/oauth2/v2/auth"
            :token-uri "https://oauth2.googleapis.com/token"}})

(defn endpoints [live auth] (merge (get providers auth) (get-in live [:oauth auth])))

;; ---- secrets.edn

(defn read-secrets [f]
  (if (fs/exists? f) (edn/read-string (slurp (str f))) {}))

(defn write-secrets!
  "Writes secrets.edn, readable and writable by the user only."
  [f m]
  (let [f (str f)]
    (when-not (fs/exists? f) (spit f ""))
    (fs/set-posix-file-permissions f "rw-------")
    (spit f (str ";; Your credentials, read by the router only. Never in git (.gitignore);\n"
                 ";; never visible to a tool's sandbox or to the agent. bb auth <name>\n"
                 ";; writes a credential's :refresh-token; a pasted :access-token works too.\n"
                 (with-out-str (pp/pprint m))))))

;; ---- what the artifact's tools need

(defn scopes-for
  "The scopes the artifact's tools declare for credential auth."
  [artifact auth]
  (into (sorted-set) (for [[_ t] (:tools artifact) r (:proxy t) :when (= auth (:auth r)) s (:scopes r)] s)))

;; ---- tokens

(defn- form [m] (str/join "&" (for [[k v] m] (str (name k) "=" (java.net.URLEncoder/encode (str v) "UTF-8")))))

(defn- token-request [token-uri params]
  (let [resp (http/post token-uri {:headers {"content-type" "application/x-www-form-urlencoded"}
                                   :body (form params) :throw false})
        body (try (json/parse-string (:body resp) true) (catch Exception _ {}))]
    (if (= 200 (:status resp))
      body
      (throw (ex-info (str "the provider refused: " (or (:error_description body) (:error body) (:status resp))) body)))))

(defn- scope-set [s] (set (remove str/blank? (str/split (str s) #"\s+"))))

(defn refresh!
  "A fresh access token from a credential's refresh token:
   {:access-token :expires-at ms :scopes #{}}."
  [{:keys [client-id client-secret refresh-token]} token-uri]
  (let [{:keys [access_token expires_in scope]}
        (token-request token-uri {:grant_type "refresh_token" :refresh_token refresh-token
                                  :client_id client-id :client_secret client-secret})]
    {:access-token access_token
     :expires-at (+ (System/currentTimeMillis) (* 1000 (or expires_in 3600)))
     :scopes (scope-set scope)}))

;; ---- granting: the consent page, a loopback port, PKCE

(defn- random-b64 [n]
  (let [b (byte-array n)]
    (.nextBytes (java.security.SecureRandom.) b)
    (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) b)))

(defn- challenge [verifier]
  (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder))
                   (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes ^String verifier "US-ASCII"))))

(defn- query-params [q]
  (into {} (for [kv (str/split (str q) #"&") :let [[k v] (str/split kv #"=" 2)] :when (seq k)]
             [(keyword k) (java.net.URLDecoder/decode (str v) "UTF-8")])))

(defn login!
  "Grants credential auth for exactly scopes: opens (or prints) the consent
   page, waits for the answer on a loopback port, exchanges the code, and
   writes the refresh token to secrets-file. open is how the page is opened,
   (fn [url]). Returns {:granted #{} :requested #{}}."
  [{:keys [auth scopes secrets-file live open timeout-ms]}]
  (let [secrets (read-secrets secrets-file)
        {:keys [client-id client-secret] :as cred} (get secrets auth)
        {:keys [auth-uri token-uri]} (endpoints live auth)]
    (when-not (and client-id client-secret)
      (throw (ex-info (str "secrets.edn has no " auth " :client-id and :client-secret: create a Desktop-app OAuth client"
                           " in your own Google Cloud project, and put them under " auth) {})))
    (when (empty? scopes)
      (throw (ex-info (str "no installed tool declares a scope for " auth ": nothing to grant") {})))
    (let [verifier (random-b64 48)
          state (random-b64 16)
          answer (promise)
          srv (server/run-server
               (fn [{:keys [uri query-string]}]
                 (if (= "/callback" uri)
                   (do (deliver answer (query-params query-string))
                       {:status 200 :headers {"content-type" "text/plain"}
                        :body "Done: you can close this page and go back to the terminal."})
                   {:status 404}))
               {:port 0 :ip "127.0.0.1" :legacy-return-value? false})
          redirect (str "http://127.0.0.1:" (server/server-port srv) "/callback")
          url (str auth-uri "?" (form {:client_id client-id :redirect_uri redirect :response_type "code"
                                       :scope (str/join " " scopes) :access_type "offline" :prompt "consent"
                                       :code_challenge (challenge verifier) :code_challenge_method "S256"
                                       :state state}))]
      (try
        (open url)
        (let [{:keys [code error] got-state :state} (deref answer (or timeout-ms 300000) {:error "no answer in time"})]
          (when error (throw (ex-info (str "not granted: " error) {})))
          (when (not= state got-state) (throw (ex-info "the answer's state isn't this request's: ignored" {})))
          (let [{:keys [refresh_token scope]}
                (token-request token-uri {:grant_type "authorization_code" :code code :redirect_uri redirect
                                          :client_id client-id :client_secret client-secret :code_verifier verifier})
                granted (scope-set scope)]
            (when-not refresh_token
              (throw (ex-info "the provider gave no refresh token" {})))
            (write-secrets! secrets-file (assoc secrets auth (-> cred
                                                                 (dissoc :access-token)
                                                                 (assoc :refresh-token refresh_token :scopes granted))))
            {:granted granted :requested (set scopes)}))
        (finally (server/server-stop! srv))))))

(defn -main
  "bb auth <name> [--no-browser]"
  [& [auth-name & flags]]
  (let [auth (keyword (or auth-name "google"))
        artifact (edn/read-string (slurp "artifact/artifact.edn"))
        live (let [f (fs/file "live.edn")] (if (fs/exists? f) (edn/read-string (slurp f)) {}))
        scopes (scopes-for artifact auth)
        open (fn [url]
               (println (str "Grant " auth " for exactly:\n  " (str/join "\n  " scopes) "\n\nOpen:\n" url "\n"))
               (when-not (some #{"--no-browser"} flags)
                 (try (proc/process ["xdg-open" url] {:out :discard :err :discard}) (catch Exception _ nil))))]
    (try
      (let [{:keys [granted requested]} (login! {:auth auth :scopes scopes :secrets-file "secrets.edn" :live live :open open})]
        (println "granted:" (str/join " " (sort granted)))
        (when-let [missing (seq (remove granted requested))]
          (println "missing, the tools needing them will be refused:" (str/join " " missing)))
        (when-let [extra (seq (remove requested granted))]
          (println "more than asked (the router still makes only declared routes):" (str/join " " extra))))
      (catch clojure.lang.ExceptionInfo e (println (ex-message e)) (System/exit 1)))))
