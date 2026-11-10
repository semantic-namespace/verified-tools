(ns verified.runner
  "Runs one tool, inside its sandbox: bound to its module's declaration and
   to a transport, it calls the tool's function and prints its result. The
   router starts it under bubblewrap, with no network at all; it sees only
   its module, the SDK and the artifact.

   Its transport, by the artifact's mode:
     :replay  the module's recorded responses (fixtures.edn), by declared route
     :live    the router, over this process's stdio: a request goes out as
              {:verified/request ...} on stdout, its answer comes back as one
              EDN line on stdin. The router checks the request against the
              declared routes, adds the token, and makes the call; the token
              never enters the sandbox.
   The result goes out as {:verified/result rows :verified/more bool}:
   more is whether the tool found rows past its per-call cap."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [verified.sdk :as sdk]
            [verified.sdk.bundle :as bundle]
            [verified.sdk.files :as files]))

(defn- replay-transport [fixtures]
  (fn [{:keys [route path]}]
    (let [by-route (get fixtures route)]
      (or (get by-route :default)
          (get by-route (last (str/split path #"/")))
          (throw (ex-info (str "no recorded response for " route " " path) {}))))))

(defn- router-transport []
  (fn [request]
    (locking *out*
      (prn {:verified/request (dissoc request :auth)})
      (flush))
    (let [line (read-line)
          {:verified/keys [response refused]} (some-> line edn/read-string)]
      (cond refused (sdk/refuse (str "the router refused: " refused) {:request request})
            (nil? line) (throw (ex-info "the router closed the channel" {}))
            :else response))))

(defn -main [tool-id args-edn artifact-file]
  (let [artifact (edn/read-string (slurp artifact-file))
        tid (keyword (subs tool-id 1))
        {:keys [dir var]} (get-in artifact [:tools tid])
        decl (edn/read-string (slurp (str "modules/" dir "/module.edn")))
        transport (if (= :live (:artifact/mode artifact))
                    (router-transport)
                    ;; a module with no network has no recorded responses
                    (replay-transport (let [f (java.io.File. (str "modules/" dir "/fixtures.edn"))]
                                        (if (.exists f) (edn/read-string (slurp f)) {}))))
        f (requiring-resolve var)
        more (atom false)
        rows (binding [sdk/*more* more
                       sdk/*module* decl
                       sdk/*tool* tid
                       sdk/*transport* transport
                       ;; each declared root is mounted at /roots/<name> by the sandbox
                       files/*roots* (into {} (for [r (keys (:files decl))] [r (str "/roots/" (name r))]))
                       ;; the module's own directory, mounted read-only, for its bundled data
                       bundle/*dir* (str "modules/" dir)]
               (f (edn/read-string args-edn)))]
    (prn {:verified/result rows :verified/more @more})
    (flush)))
