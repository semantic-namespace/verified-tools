(ns verified.live-test
  "Live: the tools' HTTP goes through the router to a fake provider. The
   router adds the token (the tool never has it), makes only declared
   routes, and refuses forged requests, whatever a module sends."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [fake-provider :as fp]
            [org.httpkit.server :as server]
            [verified.router :as router]
            [verified.test-util :as u]))

(def port 8766)
(def dir (delay (let [d (u/scratch "live")] (u/build! :live d) d)))
(def provider-log (delay (str @dir "/provider.log")))

(defn- provider-requests []
  (let [f (fs/file @provider-log)]
    (if (fs/exists? f) (mapv edn/read-string (str/split-lines (slurp f))) [])))

(use-fixtures :once
  (fn [t]
    (reset! fp/log-file @provider-log)
    (let [stop (server/run-server fp/handler {:port port :ip "127.0.0.1"})]
      (try (t) (finally (stop))))))

(def hosts {"gmail.googleapis.com" (str "http://127.0.0.1:" port)
            "paste.example" (str "http://127.0.0.1:" port)})

(defn- opts [name live]
  (let [d (str @dir "/" name)]
    {:artifact (str @dir "/artifact.edn")
     :live (u/write-edn! (str d "/live.edn") live)
     :audit (str d "/audit")
     :confirm (str d "/confirm")}))

(def with-token {:hosts hosts :credentials {:google {:token-file "dev/fake.token"}}})

(deftest receipts-through-the-router
  (let [before (count (provider-requests))
        s (u/start (opts "receipts" with-token))
        res (try (u/call s "receipts_search" {"from" "2026-10-01" "to" "2026-10-31"})
                 (finally (u/stop s)))
        sent (drop before (provider-requests))]
    (is (= 4 (count (json/parse-string (:text res)))))
    (is (= 5 (count sent)) "the list and four messages")
    (is (every? :authorized sent) "the router added the token")
    (is (str/includes? (:query (first sent)) "after%3A2026%2F10%2F01"))))

(deftest no-credential-no-call
  (let [before (count (provider-requests))
        s (u/start (opts "no-token" {:hosts hosts}))
        res (try (u/call s "receipts_search" {"from" "2026-10-01" "to" "2026-10-31"})
                 (finally (u/stop s)))]
    (is (:error? res))
    (is (str/includes? (:text res) "no credential"))
    (is (= before (count (provider-requests))) "nothing reached the provider")))

(deftest sending-what-the-user-allowed
  (let [before (count (provider-requests))
        s (u/start (opts "share" with-token) :elicitation true)]
    (try
      (let [req (u/call s "share_snippet" {"text" "Lunch on Thursday"})
            res (u/answer-elicitation s req {:action "accept" :content {:allow true}})
            [sent] (drop before (provider-requests))]
        (is (str/includes? (:text res) "paste.example/s/"))
        (is (= {:text "Lunch on Thursday"} (json/parse-string (:body sent) true)) "exactly the allowed text")
        (is (not (:authorized sent)) "no Gmail token to the paste service"))
      (finally (u/stop s)))))

(deftest forged-requests
  (swap! router/config merge (opts "forged" with-token))
  (let [egress! @#'router/egress!
        t (get-in (router/artifact) [:tools :tool/receipts-search])
        gmail "gmail.googleapis.com"]
    (testing "declared"
      (is (:verified/response (egress! :tool/receipts-search t {:host gmail :method :get :path "/gmail/v1/users/me/messages/m2"}))))
    (doseq [[label req] [["another host" {:host "collector.example" :method :get :path "/gmail/v1/users/me/messages"}]
                         ["an undeclared path" {:host gmail :method :get :path "/gmail/v1/users/me/settings/forwardingAddresses"}]
                         ["a traversal in a hole" {:host gmail :method :get :path "/gmail/v1/users/me/messages/../../settings"}]
                         ["an empty hole" {:host gmail :method :get :path "/gmail/v1/users/me/messages/"}]
                         ["a POST on a GET route" {:host gmail :method :post :path "/gmail/v1/users/me/messages/send"}]
                         ["a DELETE" {:host gmail :method :delete :path "/gmail/v1/users/me/messages/m2"}]
                         ["another module's route" {:host "paste.example" :method :post :path "/api/snippets" :body {:text "x"}}]
                         ["a query value that isn't text" {:host gmail :method :get :path "/gmail/v1/users/me/messages" :query {:q {:nested "x"}}}]]]
      (testing label
        (is (:verified/refused (egress! :tool/receipts-search t req)))))))
