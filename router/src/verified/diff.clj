(ns verified.diff
  "What changes between two artifacts, said in the vocabulary's own words,
   each change marked by whether it lets a tool reach further: more data
   read, a stronger effect, a wider egress, a new host or route, a new
   credential scope, more keys returned, higher caps, an argument more, or
   no longer asking the user. Pure: two artifacts in, changes out.

     bb diff old.edn new.edn"
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]))

(defn- term [artifact k]
  (let [d (get-in artifact [:vocabulary :terms k])]
    (if d (str (name k) " (" d ")") (str k))))

(defn- routes [t] (set (map (juxt :method :host :path) (:proxy t))))
(defn- scopes [t] (set (mapcat :scopes (:proxy t))))
(defn- route-str [[m h p]] (str (str/upper-case (name m)) " " h p))

(defn describe
  "A tool in plain words, from the artifact alone."
  [artifact tid]
  (let [{:keys [doc reads effect egress returns caps confirm args module] :as t} (get-in artifact [:tools tid])
        {:keys [version source]} (get-in artifact [:modules module])]
    (str/join "\n"
              (concat
               [(str (name tid) ": " doc)
                (str "  from     " module " " version
                     (if (map? source) (str ", " (:git/url source) " @ " (subs (:git/sha source) 0 12)) ", local"))
                (str "  takes    " (str/join ", " (for [[k ty] args] (str (name k) " (" (name ty) ")"))))
                (str "  reads    " (str/join "; " (map #(term artifact %) (sort reads))))
                (str "  does     " (term artifact effect))
                (str "  network  " (str/join "; " (map #(term artifact %) (sort egress))))]
               (for [r (sort (routes t))
                     :let [host (second r) holds (get-in artifact [:vocabulary :providers host])]]
                 (str "             " (route-str r)
                      (if holds
                        (str "   (a registered provider of " (str/join ", " (map name (sort holds))) ")")
                        "   (not a registered provider: the internet)")))
               (when (seq (scopes t)) [(str "  with     your credential, scope " (str/join ", " (sort (scopes t))))])
               (for [[r {:keys [holds extensions]}] (sort (:roots t))]
                 (str "  files    the folder you bind as " (name r) ", read-only: " (term artifact holds)
                      ", " (str/join " " (map #(str "*." %) (sort extensions)))))
               [(str "  returns  " (str/join ", " (map name returns)))
                (str "  at most  " (:per-call caps) " rows a call, " (:per-session caps) " a session")
                (str "  asks you " (if confirm "before every call" "never"))]))))

(defn- rank [artifact k] (get-in artifact [:vocabulary :rank k] 0))

(defn- tool-changes [old new tid]
  (let [a (get-in old [:tools tid]) b (get-in new [:tools tid])
        added (fn [f] (sort (set/difference (set (f b)) (set (f a)))))
        removed (fn [f] (sort (set/difference (set (f a)) (set (f b)))))]
    (concat
     (for [k (added :reads)] {:tool tid :up true :what (str "now reads " (term new k))})
     (for [k (removed :reads)] {:tool tid :up false :what (str "no longer reads " (name k))})
     (when (not= (:effect a) (:effect b))
       [{:tool tid :up (> (rank new (:effect b)) (rank old (:effect a)))
         :what (str "does " (term new (:effect b)) ", was " (name (:effect a)))}])
     (for [k (added :egress)] {:tool tid :up true :what (str "network now " (term new k))})
     (for [k (removed :egress)] {:tool tid :up false :what (str "network no longer " (name k))})
     (for [r (added routes)] {:tool tid :up true :what (str "new route " (route-str r))})
     (for [r (removed routes)] {:tool tid :up false :what (str "route gone " (route-str r))})
     (for [s (added scopes)] {:tool tid :up true :what (str "uses your credential with scope " s)})
     (for [r (added (comp keys :roots))]
       {:tool tid :up true :what (str "reads files from the folder you bind as " (name r)
                                      " (" (name (get-in b [:roots r :holds])) ")")})
     (for [r (sort (keys (:roots b)))
           :let [x (get-in a [:roots r :extensions]) y (get-in b [:roots r :extensions])]
           :when (and x (seq (remove (set x) y)))]
       {:tool tid :up true :what (str "reads more kinds of file from " (name r) ": "
                                      (str/join " " (map #(str "*." %) (sort (remove (set x) y)))))})
     (for [k (added :returns)] {:tool tid :up true :what (str "now returns " (name k))})
     (for [k (removed :returns)] {:tool tid :up false :what (str "no longer returns " (name k))})
     (for [k (sort (set/union (set (keys (:args a))) (set (keys (:args b)))))
           :when (not= (get-in a [:args k]) (get-in b [:args k]))]
       {:tool tid :up (some? (get-in b [:args k]))
        :what (if (get-in b [:args k])
                (str "takes " (name k) " (" (name (get-in b [:args k])) ")")
                (str "no longer takes " (name k)))})
     (for [c [:per-call :per-session]
           :let [x (get-in a [:caps c] 0) y (get-in b [:caps c] 0)] :when (not= x y)]
       {:tool tid :up (> y x) :what (str (name c) " cap " x " -> " y)})
     (when (not= (:confirm a) (:confirm b))
       [{:tool tid :up (not (:confirm b))
         :what (if (:confirm b) "now asks you before every call" "no longer asks you before calling")}])
     (when (not= (:doc a) (:doc b))
       [{:tool tid :up false :what (str "says: " (:doc b))}]))))

(defn changes
  "Every change from artifact old to new: tools added, removed or changed,
   modules newly excluded or back. :up marks reaching further."
  [old new]
  (let [ta (set (keys (:tools old))) tb (set (keys (:tools new)))]
    (concat
     (for [tid (sort (set/difference tb ta))] {:tool tid :up true :what "a new tool" :added true})
     (for [tid (sort (set/difference ta tb))] {:tool tid :up false :what "removed" :removed true})
     (mapcat #(tool-changes old new %) (sort (set/intersection ta tb)))
     (for [[mid why] (:excluded new) :when (not (contains? (:excluded old) mid))]
       {:module mid :up false :what (str "excluded: " (str/join "; " why))}))))

(defn render
  "Changes as lines: ▲ reaches further, ▽ reaches less or only says
   something new."
  [cs]
  (if (empty? cs)
    "  no change in what any tool can do"
    (str/join "\n" (for [{:keys [tool module up what]} cs]
                     (str "  " (if up "▲" "▽") " " (name (or tool module)) ": " what)))))

(defn -main [old-file new-file]
  (println (render (changes (edn/read-string (slurp old-file)) (edn/read-string (slurp new-file))))))
