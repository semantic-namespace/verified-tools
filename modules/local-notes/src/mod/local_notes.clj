(ns mod.local-notes
  "Notes that mention a text, searched where they are: only the matching
   notes' path, title and a few lines around the first match leave the tool."
  (:require [clojure.string :as str]
            [verified.sdk.files :as files]
            [verified.sdk.out :as out]))

(defn- title [path text]
  (or (some->> (str/split-lines text) (map str/trim) (remove str/blank?) first
               (re-find #"^(?:#\+title:|#+)?\s*(.*)$") second not-empty)
      path))

(defn- excerpt [text i]
  (let [lines (str/split-lines text)
        at (count (str/split-lines (subs text 0 i)))
        from (max 0 (- at 2))]
    (str/join "\n" (take 3 (drop from lines)))))

(defn ^{:module/tool :tool/local-notes-search} local-notes-search
  "Notes mentioning text, case-insensitively."
  [{:keys [text]}]
  (let [needle (str/lower-case text)]
    (out/rows :tool/local-notes-search
              (for [path (files/list :notes)
                    :let [body (files/read :notes path)
                          i (str/index-of (str/lower-case body) needle)]
                    :when i]
                {:note/path path
                 :note/title (title path body)
                 :note/excerpt (excerpt body i)}))))
