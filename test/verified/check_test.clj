(ns verified.check-test
  "What meta-source finds: nothing in the good modules, and each planted
   mistake in the bad ones, by its own check."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [verified.test-util :as u]))

(defn- file-of [p] (or (get-in p [:definition :file]) (get-in p [:impl :file]) (:file p)))

(defn- in [module severity]
  (for [p @u/problems
        :when (and (= severity (:severity p))
                   (some-> (file-of p) (str/starts-with? (str "modules/" module "/"))))]
    (str (or (:rule p) (:problem p)))))

(deftest good-modules-are-clean
  (doseq [m ["gmail-receipts" "share-snippet" "local-notes"]]
    (is (empty? (in m :error)) m)
    (is (empty? (in m :warning)) m)))

(deftest vocabulary-is-clean
  (is (empty? (for [p @u/problems
                    :when (and (= :error (:severity p))
                               (not (some-> (file-of p) (str/starts-with? "modules/"))))]
                p))))

(deftest leaky-receipts-code-against-declaration
  (let [errors (in "leaky-receipts" :error)]
    (is (= 5 (count errors)) (str/join "\n" errors))
    (testing "each code mistake by its check"
      (doseq [check ["sdk-arg-not-literal" "call-outside-sdk" "key-not-declared" "interop-outside-sdk"]]
        (is (some #(str/includes? % check) errors) check))
      (is (= 2 (count (filter #(str/includes? % "call-outside-sdk") errors)))
          "the HTTP post and the shell call"))))

(deftest sloppy-notes-declaration-mistakes
  (let [errors (in "sloppy-notes" :error)]
    (is (= 6 (count errors)) (str/join "\n" errors))
    (doseq [check ["tool-effect-count" "aspect-not-in-vocabulary"
                   "acting-needs-confirmation" "secrets-never-leave"
                   "tools-say-what-they-return" "receipt-is-financial"]]
      (is (some #(str/includes? % check) errors) check))
    (is (= 1 (count (in "sloppy-notes" :info))) "it has no code")))
