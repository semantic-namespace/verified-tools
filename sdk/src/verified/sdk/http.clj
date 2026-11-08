(ns verified.sdk.http
  "HTTP only through routes the module declares: (call :gmail/get-message
   {:id id}). The route is a literal keyword, :egress-key/route-name; its
   method and path template come from the declaration, path holes from the
   params, the rest of the params go as the query. The token is the
   transport's: a module never sees it."
  (:require [clojure.string :as str]
            [verified.sdk :as sdk]))

(defn- fill-path [template params]
  (let [holes (map second (re-seq #"\{([a-z-]+)\}" template))
        path (reduce (fn [p h]
                       (let [v (get params (keyword h))]
                         (when-not (and (string? v) (re-matches #"[A-Za-z0-9_-]+" v))
                           (sdk/refuse (str "path hole {" h "} needs a plain id") {:hole h}))
                         (str/replace p (str "{" h "}") v)))
                     template holes)]
    [path (apply dissoc params (map keyword holes))]))

(defn call
  "The response of a declared route."
  [route params]
  (when-not (qualified-keyword? route)
    (sdk/refuse "a route is a qualified keyword, :egress/route" {:route route}))
  (let [egress (get-in sdk/*module* [:egress (keyword (namespace route))])
        [method template] (get-in egress [:routes (keyword (name route))])]
    (when-not template
      (sdk/refuse (str "route " route " is not declared") {:route route}))
    (let [[path query] (fill-path template params)]
      (sdk/*transport* {:route route :host (:host egress) :method method :path path
                        :query query :auth (:auth egress)}))))
