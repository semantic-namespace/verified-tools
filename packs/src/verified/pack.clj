(ns verified.pack
  "Modules as meta-source reads them: each modules/*/module.edn, a module
   and the tools it declares, read as data. A tool's :atlas/aspects are
   exactly what it declares (its :reads, its :effect, its :egress), nothing
   inferred, so atlas types it and the vocabulary's policies apply to it.

   What the rule language can't say is checked here: a tool declares one
   effect and at least one data class, and every aspect in a reserved
   namespace (:data/*, :effect/*, :egress/*) is a core term or the narrow
   side of a refinement. Each refinement in the vocabulary becomes a rule:
   whatever carries its narrow term carries its broad one.

   A module's code (modules/<dir>/src) is read too, as clj-kondo's analysis
   gives it, and checked against its declaration: its tool functions
   (^{:module/tool id}) are the declared tools; it reaches the world only
   through the SDK (verified.sdk.http/call, query/fill, files/read,
   out/rows), each called with a literal keyword the declaration names; it
   calls nothing outside it (an HTTP client, a shell, eval); and every
   qualified key it writes in a map is one its tools declare returning."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [meta-source.read :as read]))

(defn- module-dir
  "The module directory a file is under, modules/<dir>/..., or nil."
  [f]
  (second (re-find #"(?:^|/)modules/([^/]+)/" (str f))))

(def ^:private sdk-fns
  '{verified.sdk.http #{call} verified.sdk.query #{fill} verified.sdk.files #{read list} verified.sdk.out #{rows}})

;; what a module may not call: the world outside the SDK
(def ^:private forbidden-nses
  '#{babashka.http-client babashka.curl org.httpkit.client clj-http.client clojure.java.shell
     babashka.process babashka.fs clojure.java.io babashka.pods babashka.deps})

(def ^:private forbidden-core
  '#{eval load-string load-file read-string requiring-resolve resolve intern alter-var-root slurp spit})

(defn- qualified-map-keys [form]
  (let [acc (volatile! #{})]
    (walk/postwalk (fn [x] (when (map? x) (vswap! acc into (filter qualified-keyword? (keys x)))) x) form)
    @acc))

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
                :module/dir (module-dir file)
                :module/routes (vec (sort (for [[ek e] (get-in by-key [:egress :v]) [rk _] (:routes e)]
                                            (keyword (name ek) (name rk)))))
                :module/queries (vec (sort (keys (get-in by-key [:queries :v]))))
                :module/roots (vec (sort (keys (get-in by-key [:files :v]))))
                :module/root-holds (into (sorted-map) (for [[r d] (get-in by-key [:files :v])] [r (when (map? d) (:holds d))]))
                :module/returns (vec (sort (distinct (mapcat :returns (vals (get-in by-key [:tools :v])))))) 
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
                          ;; what it declares, as its aspects, and the module it is
                          ;; declared in: two modules' tools reading the same are
                          ;; two capabilities, not one identity
                          :atlas/aspects (into #{(keyword "module" (name mid))}
                                               (concat (many (:reads t)) (many (:effect t)) (many (:egress t))))}
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
   :pack/data-files (fn [opts]
                      (let [root (java.io.File. (str (:meta-source/root opts ".")) "modules")]
                        (vec (sort (for [^java.io.File d (or (.listFiles root) [])
                                         :when (.isFile (java.io.File. d "module.edn"))]
                                     (str "modules/" (.getName d) "/module.edn"))))))
   :pack/var-meta [:module/tool]
   ;; a module's code: each var it defines (a tool, or a helper whose maps
   ;; are read), each SDK call, each call outside the SDK
   :pack/find
   (fn [analysis _]
     (let [in-module? #(and (module-dir (:filename %)) (str/includes? (str (:filename %)) "/src/"))]
       (concat
        (for [d (:var-definitions analysis) :when (in-module? d)]
          {:filename (:filename d) :row (:row d) :name-row (:row d) :name-col (inc (:col d))
           :site :defn :tool (get-in d [:meta :module/tool]) :var (symbol (str (:ns d)) (str (:name d)))})
        (for [u (:var-usages analysis)
              :when (and (in-module? u) (contains? (get sdk-fns (:to u)) (:name u)))]
          {:filename (:filename u) :row (:row u) :name-row (:name-row u) :name-col (:name-col u)
           :site :sdk :fn (:name u) :from-var (:from-var u)})
        (for [u (:var-usages analysis)
              :when (and (in-module? u)
                         (or (forbidden-nses (:to u))
                             (and (= 'clojure.core (:to u)) (forbidden-core (:name u)))))]
          {:filename (:filename u) :row (:row u) :name-row (:name-row u) :name-col (:name-col u)
           :site :forbidden :sym (symbol (str (:to u)) (str (:name u)))}))))
   :pack/read
   (fn [sites {:keys [form-at]} _]
     (let [dir (some (comp module-dir :filename) sites)
           facts (for [{:keys [site filename row name-row name-col] :as s} sites]
                   (case site
                     :defn (let [form (form-at name-row name-col)]
                             {:defs #{[(:var s) (:tool s) filename row]}
                              :keys (set (for [k (when (seq? form) (qualified-map-keys form))] [k (:var s) filename row]))})
                     :sdk (let [form (form-at name-row name-col)
                                arg (when (seq? form) (second form))]
                            {:calls #{[(:fn s) (if (keyword? arg) arg :computed) (:from-var s) filename row]}})
                     :forbidden {:forbidden #{[(:sym s) filename row]}}))
           merged (apply merge-with into {:defs #{} :keys #{} :calls #{} :forbidden #{}} facts)]
       (if-not dir
         []
         [{:row (:row (first sites)) :kind :module :dev-id (keyword "module-code" dir)
           :entry {:verified/kind :code :code/module-dir dir
                   :code/defs (vec (sort-by str (:defs merged)))
                   :code/keys (vec (sort-by str (:keys merged)))
                   :code/calls (vec (sort-by str (:calls merged)))
                   :code/forbidden (vec (sort-by str (:forbidden merged)))}}])))
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
             :nearest (nearest a (concat terms narrows)) :definition (loc e)}))
        ;; ---- a root says which data class it holds, in the vocabulary
        (when vocab
          (for [[id e] entities
                :when (= :module (get-in e [:entry :verified/kind]))
                [r holds] (get-in e [:entry :module/root-holds])
                :when (not (and (keyword? holds) (= "data" (namespace holds)) (or (terms holds) (narrows holds))))]
            {:severity :error :problem :root-holds-unknown :dev-id id :root r :holds holds :definition (loc e)}))
        ;; ---- each module's code against its declaration
        (let [modules (for [[id e] entities :when (= :module (get-in e [:entry :verified/kind]))] [id (:entry e) e])
              code (into {} (for [[_ e] entities :when (= :code (get-in e [:entry :verified/kind]))]
                              [(get-in e [:entry :code/module-dir]) (:entry e)]))
              at (fn [f row] (loc {:file f :line row}))]
          (for [[mid m me] (sort-by (comp str first) modules)
                :let [c (code (:module/dir m))
                      declared (set (:module/tools m))
                      defs (:code/defs c)
                      tool-of (into {} (for [[v t] defs :when t] [(symbol (name v)) t]))
                      coded (set (keep second defs))]
                f (if-not c
                    [{:severity :info :problem :module-without-code :dev-id mid :definition (loc me)}]
                    (concat
                     (for [t (sort (remove coded declared))]
                       {:severity :error :problem :tool-without-code :dev-id t :module mid :definition (loc me)})
                     (for [[v t f row] defs :when (and t (not (declared t)))]
                       {:severity :error :problem :code-tool-not-declared :dev-id mid :tool t :var v :definition (at f row)})
                     (for [[fname arg from-var f row] (:code/calls c)
                           :let [allowed (case (str fname)
                                           "call" (set (:module/routes m))
                                           "fill" (set (:module/queries m))
                                           ("read" "list") (set (:module/roots m))
                                           "rows" #{(tool-of from-var)})]
                           :when (or (= :computed arg) (not (allowed arg)))]
                       {:severity :error :problem (if (= :computed arg) :sdk-arg-not-literal :sdk-arg-not-declared)
                        :dev-id mid :sdk fname :arg arg :definition (at f row)})
                     (for [[sym f row] (:code/forbidden c)]
                       {:severity :error :problem :call-outside-sdk :dev-id mid :sym sym :definition (at f row)})
                     ;; what a root holds is read by whichever tool reads files from it:
                     ;; the tool whose function calls files/*, else (a helper calls it)
                     ;; every tool of the module
                     (distinct
                      (for [[fname arg from-var f row] (:code/calls c)
                            :when (and (#{"read" "list"} (str fname)) (keyword? arg))
                            :let [holds (get (:module/root-holds m) arg)]
                            :when holds
                            t (if-let [t (tool-of from-var)] [t] (sort declared))
                            :when (not (some #{holds} (get-in entities [t :entry :tool/reads])))]
                        {:severity :error :problem :root-data-not-read :dev-id t :root arg :holds holds
                         :definition (at f row)}))
                     (for [[k v f row] (:code/keys c) :when (not (contains? (set (:module/returns m)) k))]
                       {:severity :error :problem :key-not-declared :dev-id mid :key k :var v :definition (at f row)})))]
            f)))))

   :pack/messages
   {:no-vocabulary (fn [p _ _] (:message p))
    :tool-effect-count (fn [p id _] (str id " declares " (count (:effects p)) " effects "
                                         (pr-str (:effects p)) "; a tool has exactly one"))
    :tool-reads-no-data (fn [_ id _] (str id " declares no :data/* class it reads"))
    :aspect-not-in-vocabulary (fn [p id _] (str id "'s " (:aspect p) " is neither a core term nor a refinement"
                                                (when (:nearest p) (str ": did you mean " (:nearest p) "?"))))
    :module-without-code (fn [_ id _] (str id " declares tools and has no code: it can't run"))
    :tool-without-code (fn [p id _] (str id " is declared by " (:module p) " and no function is its ^{:module/tool}"))
    :code-tool-not-declared (fn [p id _] (str (:var p) " is " (:tool p) ", which " id " doesn't declare"))
    :sdk-arg-not-literal (fn [p id _] (str id ": " (:sdk p) " is called with a value computed at runtime; it takes a literal keyword the declaration names"))
    :sdk-arg-not-declared (fn [p id _] (str id ": " (:sdk p) " " (:arg p) " is not in the declaration"))
    :call-outside-sdk (fn [p id _] (str id " calls " (:sym p) ": a module reaches the world only through the SDK"))
    :root-data-not-read (fn [p id _] (str id " reads files from root " (:root p) ", which holds " (:holds p)
                                          ", and doesn't declare reading " (:holds p)))
    :root-holds-unknown (fn [p id _] (str id "'s root " (:root p) " holds " (pr-str (:holds p))
                                          ": a root says which :data/* class it holds, a core term or a refinement"))
    :key-not-declared (fn [p id _] (str id ": " (:var p) " builds a map with " (:key p) ", which no tool declares returning"))}

   :pack/describe (fn [entry] (case (:verified/kind entry)
                                :tool (str "tool  " (str/join " " (map pr-str (sort (:atlas/aspects entry)))))
                                :module (str "module  " (count (:module/tools entry)) " tools")
                                nil))})
