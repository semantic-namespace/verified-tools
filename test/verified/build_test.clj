(ns verified.build-test
  "The artifact: only the runnable modules, every excluded one with its
   reasons, and per tool a sandbox with no network and no home, and an
   egress allowlist of exactly the declared routes."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [verified.test-util :as u]))

(def artifact (delay (u/build! :replay (u/scratch "build"))))

(deftest only-runnable-modules
  (is (= #{:tool/receipts-search :tool/share-snippet} (set (keys (:tools @artifact)))))
  (is (= {:mod/leaky-receipts 4 :mod/sloppy-notes 7} (update-vals (:excluded @artifact) count)))
  (is (some #(str/includes? % "no code") (get-in @artifact [:excluded :mod/sloppy-notes]))))

(deftest sandboxes
  (doseq [[tid {:keys [sandbox]}] (:tools @artifact)]
    (testing (str tid)
      (is (some #{"--unshare-net"} sandbox) "never a network")
      (is (= "/tmp" (second (drop-while #(not= "HOME" %) sandbox))) "HOME is the private /tmp")
      (let [bound (set (map second (filter #(= "--ro-bind" (first %)) (partition 2 1 sandbox))))]
        (is (not-any? #(str/starts-with? % (System/getProperty "user.home")) (remove #(str/starts-with? % u/root) bound))
            "nothing from the home directory but the project's own read-only parts")
        (is (= 1 (count (filter #(str/includes? % "/modules/") bound))) "only its own module")))))

(deftest egress-allowlists
  (is (= #{["gmail.googleapis.com" :get "/gmail/v1/users/me/messages"]
           ["gmail.googleapis.com" :get "/gmail/v1/users/me/messages/{id}"]
           ["gmail.googleapis.com" :get "/gmail/v1/users/me/messages/{id}/attachments/{att}"]}
         (set (map (juxt :host :method :path) (get-in @artifact [:tools :tool/receipts-search :proxy])))))
  (is (every? #(= :google (:auth %)) (get-in @artifact [:tools :tool/receipts-search :proxy])))
  (is (= [["paste.example" :post "/api/snippets" nil]]
         (map (juxt :host :method :path :auth) (get-in @artifact [:tools :tool/share-snippet :proxy])))))

(deftest session-policy-from-the-vocabulary
  (is (= #{:data/financial :data/secret :data/email} (get-in @artifact [:session :blocks :egress/internet])))
  (is (= [:iban :card-number] (map first (get-in @artifact [:session :redact]))) "the most specific first"))
