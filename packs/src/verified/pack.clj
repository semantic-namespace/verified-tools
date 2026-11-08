(ns verified.pack
  "Modules as meta-source reads them: each modules/*/module.edn, a module
   and the tools it declares, read as data. A tool's :atlas/aspects are
   exactly what it declares (its :reads, its :effect, its :egress), nothing
   inferred, so atlas types it and the vocabulary's policies apply to it.

   What the rule language can't say is checked here: a tool declares one
   effect and at least one data class, and every aspect in a reserved
   namespace (:data/*, :effect/*, :egress/*) is a core term or the narrow
   side of a refinement. Each refinement in the vocabulary becomes a rule:
   whatever carries its narrow term carries its broad one."
  (:require [clojure.string :as str]
            [meta-source.read :as read]))

(defn- many [x] (cond (nil? x) [] (coll? x) (vec x) :else [x]))

(defn- module-results
  "The module and its tools, from one module.edn: [{:row :kind :dev-id :entry}]."
  [file text]
  (let [top (read/keyed-lines text {:inner true})
        by-key (into {} (for [[k v line inner] top] [k {:v v :line line :inner inner}]))
        mid (get-in by-key [:module/id :v])
        mline (get-in by-key [:module/id :line] 1)]
    (if-not (keyword? mid)
      [{:file file :row 1 :kind :module :skipped "a module.edn with no :module/id keyword"}]
      (cons
       {:file file :row mline :kind :module :dev-id mid
        :entry {:verified/kind :module
                :module/version (get-in by-key [:module/version :v])
                :module/tools (vec (keys (get-in by-key [:tools :v])))
                :module/egress-hosts (vec (sort (keep :host (vals (get-in by-key [:egress :v])))))
                :module/scopes (vec (sort (mapcat :scopes (vals (get-in by-key [:auth :v])))))}}
       (for [[tid t line] (get-in by-key [:tools :inner])]
         {:file file :row line :kind :module :dev-id tid
          :entry (cond-> {:verified/kind :tool
                          :tool/module mid
                          :tool/reads (vec (sort (many (:reads t))))
                          :tool/effect (vec (sort (many (:effect t))))
                          :tool/egress (vec (sort (many (:egress t))))
                          ;; what it declares, as its aspects: nothing added
                          :atlas/aspects (into #{} (concat (many (:reads t)) (many (:effect t)) (many (:egress t))))}
                   (:doc t) (assoc :tool/doc (:doc t))
                   (:args t) (assoc :tool/args (:args t))
                   (:returns t) (assoc :tool/returns (:returns t))
                   (:caps t) (assoc :tool/caps (:caps t))
                   (contains? t :confirm) (assoc :tool/confirm (:confirm t)))})))))

(defn- distance
  "Edit distance between two strings, for the nearest core term."
  [a b]
  (let [n (count b)]
    (peek (reduce (fn [prev ca]
                    (reduce (fn [row [j cb]]
                              (conj row (min (inc (peek row)) (inc (prev (inc j)))
                                             (+ (prev j) (if (= ca cb) 0 1)))))
                            [(inc (first prev))] (map-indexed vector b)))
                  (vec (range (inc n))) a))))

(defn- nearest [k terms]
  (first (sort-by #(distance (name k) (name %)) (filter #(= (namespace k) (namespace %)) terms))))

(def pack
  {:pack/id :verified-modules
   :pack/kinds #{:module}
   :pack/vocabulary #{"module" "tool"}
   :pack/data-files (fn [_] ["modules"])
   :pack/read-data (fn [files _]
                     (vec (mapcat (fn [{:keys [file text]}] (module-results file text)) files)))

   ;; each refinement as a rule, located at the refinement's own entry
   :pack/rules-of
   (fn [entries]
     (into {} (for [[id e] entries
                    :when (= :verified/refinement (:atlas/type e))
                    :let [{:refinement/keys [narrow broad]} e]
                    :when (and narrow broad)]
                [id {:doc (str "Whatever carries " narrow " carries " broad)
                     :severity :error
                     :when {:= {:atlas/aspects narrow}}
                     :require [{:= {:atlas/aspects broad}}]}])))

   :pack/checks
   (fn [{:keys [entities loc]}]
     (let [vocab (some #(when (= :verified/vocabulary (get-in % [:entry :atlas/type])) (:entry %)) (vals entities))
           terms (set (keys (:vocabulary/terms vocab)))
           reserved (set (:vocabulary/reserved vocab))
           narrows (set (keep #(when (= :verified/refinement (get-in % [:entry :atlas/type]))
                                 (get-in % [:entry :refinement/narrow]))
                              (vals entities)))
           tools (for [[id e] (sort-by key entities) :when (= :tool (get-in e [:entry :verified/kind]))] [id e])]
       (concat
        (when-not vocab
          [{:severity :error :problem :no-vocabulary
            :message "no :verified/vocabulary entity: nothing to check the modules' aspects against"}])
        (for [[id e] tools
              :let [effects (get-in e [:entry :tool/effect])]
              :when (not= 1 (count effects))]
          {:severity :error :problem :tool-effect-count :dev-id id :effects effects :definition (loc e)})
        (for [[id e] tools
              :when (not-any? #(= "data" (namespace %)) (get-in e [:entry :tool/reads]))]
          {:severity :error :problem :tool-reads-no-data :dev-id id :definition (loc e)})
        (when vocab
          (for [[id e] tools
                a (sort (get-in e [:entry :atlas/aspects]))
                :when (and (reserved (namespace a)) (not (terms a)) (not (narrows a)))]
            {:severity :error :problem :aspect-not-in-vocabulary :dev-id id :aspect a
             :nearest (nearest a (concat terms narrows)) :definition (loc e)})))))

   :pack/messages
   {:no-vocabulary (fn [p _ _] (:message p))
    :tool-effect-count (fn [p id _] (str id " declares " (count (:effects p)) " effects "
                                         (pr-str (:effects p)) "; a tool has exactly one"))
    :tool-reads-no-data (fn [_ id _] (str id " declares no :data/* class it reads"))
    :aspect-not-in-vocabulary (fn [p id _] (str id "'s " (:aspect p) " is neither a core term nor a refinement"
                                                (when (:nearest p) (str ": did you mean " (:nearest p) "?"))))}

   :pack/describe (fn [entry] (case (:verified/kind entry)
                                :tool (str "tool  " (str/join " " (map pr-str (sort (:atlas/aspects entry)))))
                                :module (str "module  " (count (:module/tools entry)) " tools")
                                nil))})
