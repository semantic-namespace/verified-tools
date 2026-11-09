(ns verified.files-test
  "The files SDK on its own: only declared roots, bound ones, relative
   paths without .., declared extensions, and sizes under the cap."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [verified.sdk :as sdk]
            [verified.sdk.files :as files]
            [verified.test-util :as u]))

(defmacro with-root [dir & body]
  `(binding [sdk/*module* {:files {:notes {:holds :data/note :extensions #{"md"} :max-bytes 100}}}
             files/*roots* {:notes ~dir}]
     ~@body))

(defn- refused? [f] (try (f) false (catch clojure.lang.ExceptionInfo e (boolean (::sdk/refused (ex-data e))))))

(deftest roots
  (let [d (u/scratch "files")]
    (spit (str d "/a.md") "alpha")
    (spit (str d "/b.json") "{}")
    (spit (str d "/big.md") (apply str (repeat 200 "x")))
    (fs/create-dirs (str d "/sub"))
    (spit (str d "/sub/c.md") "gamma")
    (with-root d
      (is (= ["a.md" "big.md" "sub/c.md"] (files/list :notes)) "declared extensions only")
      (is (= "gamma" (files/read :notes "sub/c.md")))
      (testing "refused"
        (is (refused? #(files/read :notes "../a.md")) "..")
        (is (refused? #(files/read :notes "/etc/passwd")) "absolute")
        (is (refused? #(files/read :notes "b.json")) "an undeclared extension")
        (is (refused? #(files/read :notes "big.md")) "over :max-bytes")
        (is (refused? #(files/list :other)) "an undeclared root")))
    (binding [sdk/*module* {:files {:notes {:holds :data/note :extensions #{"md"}}}} files/*roots* {}]
      (is (refused? #(files/list :notes)) "a root not bound to a folder"))))
