(ns verified.confirm
  "The user's side of asking, when the agent's client can't ask: run in the
   user's own terminal, never by the agent (it has no terminal).

     bb confirm              what is waiting, exactly as the router would send it
     bb confirm <id> yes|no  answers one"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn -main [& [id answer]]
  (if id
    (let [f (io/file "confirm/answers" (str id ".edn"))]
      (if (.exists (io/file "confirm/pending" (str id ".edn")))
        (do (io/make-parents f)
            (spit f (pr-str {:answer (if (= "yes" answer) :yes :no)}))
            (println id "->" (if (= "yes" answer) "yes" "no")))
        (println "nothing waiting as" id)))
    (let [pending (sort (.listFiles (io/file "confirm/pending")))]
      (if (empty? pending)
        (println "nothing waiting")
        (doseq [f pending :let [{:keys [id question]} (edn/read-string (slurp f))]]
          (println (str "[" id "]\n" question "\n")))))))
