(ns verified.bundle-test
  "Bundled data: only declared names, only inside the module, under the cap."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [verified.sdk :as sdk]
            [verified.sdk.bundle :as bundle]
            [verified.test-util :as u]))

(defn- refused? [f] (try (f) false (catch clojure.lang.ExceptionInfo e (boolean (::sdk/refused (ex-data e))))))

(deftest bundled-data
  (let [d (u/scratch "bundle")]
    (fs/create-dirs (str d "/data"))
    (spit (str d "/data/model.edn") "{:threshold 0.5}")
    (spit (str d "/outside.edn") "{:secret true}")
    (binding [sdk/*module* {:bundle {:model "data/model.edn"
                                     :escape "../bundle/outside.edn"
                                     :big {:path "data/model.edn" :max-bytes 3}}}
              bundle/*dir* d]
      (is (= {:threshold 0.5} (bundle/read-edn :model)))
      (testing "refused"
        (is (refused? #(bundle/read-edn :other)) "an undeclared name")
        (is (refused? #(bundle/read-edn :escape)) "a path with ..")
        (is (refused? #(bundle/read-edn :big)) "over :max-bytes")))))
