(ns verified.test-runner
  (:require [clojure.string :as str]
            [clojure.test :as t]))

(def namespaces '[verified.parse-test verified.check-test verified.build-test verified.router-test verified.live-test])

(defn -main [& only]
  (let [nss (filter (fn [n] (or (empty? only) (some #(str/includes? (str n) %) only))) namespaces)]
    (apply require nss)
    (let [{:keys [fail error]} (apply t/run-tests nss)]
      (System/exit (if (zero? (+ fail error)) 0 1)))))
