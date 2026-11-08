(ns verified.runner
  "Runs one tool, inside its sandbox: bound to its module's declaration and
   to a transport, it calls the tool's function and prints its result as
   EDN. The router starts it under bubblewrap; it sees only its module, the
   SDK and the artifact. In replay mode its transport answers from the
   module's recorded responses (fixtures.edn), keyed by declared route."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [verified.sdk :as sdk]))

(defn- replay-transport [fixtures]
  (fn [{:keys [route path]}]
    (let [by-route (get fixtures route)]
      (or (get by-route :default)
          (get by-route (last (str/split path #"/")))
          (throw (ex-info (str "no recorded response for " route " " path) {}))))))

(defn -main [tool-id args-edn]
  (let [artifact (edn/read-string (slurp "artifact/artifact.edn"))
        tid (keyword (subs tool-id 1))
        {:keys [dir var]} (get-in artifact [:tools tid])
        decl (edn/read-string (slurp (str "modules/" dir "/module.edn")))
        fixtures (edn/read-string (slurp (str "modules/" dir "/fixtures.edn")))
        f (requiring-resolve var)]
    (binding [sdk/*module* decl
              sdk/*tool* tid
              sdk/*transport* (replay-transport fixtures)]
      (prn (f (edn/read-string args-edn))))))
