(ns mod.local-notes
  "Notes that mention a text, searched where they are: only the matching
   notes' path, title and a few lines around the first match leave the
   tool, saying which lines they are of how many: an excerpt, never
   mistaken for the note."
  (:require [clojure.string :as str]
            [verified.sdk.files :as files]
            [verified.sdk.out :as out]))

(defn- title [path text]
  (or (some->> (str/split-lines text) (map str/trim) (remove str/blank?) first
               (re-find #"^(?:#\+title:|#+)?\s*(.*)$") second not-empty)
      path))

(defn- excerpt
  "The lines around position i: [text first-line last-line], counted from 1."
  [text i]
  (let [lines (str/split-lines text)
        at (count (str/split-lines (subs text 0 i)))
        from (max 0 (- at 2))
        shown (take 3 (drop from lines))]
    [(str/join "\n" shown) (inc from) (+ from (count shown))]))

(defn ^{:module/tool :tool/local-notes-search} local-notes-search
  "Notes mentioning text, case-insensitively."
  [{:keys [text]}]
  (let [needle (str/lower-case text)]
    (out/rows :tool/local-notes-search
              (for [path (files/list :notes)
                    :let [body (files/read :notes path)
                          i (str/index-of (str/lower-case body) needle)]
                    :when i
                    :let [[ex from to] (excerpt body i)]]
                {:note/path path
                 :note/title (title path body)
                 :note/excerpt ex
                 :note/excerpt-lines (str from "-" to)
                 :note/lines (count (str/split-lines body))}))))
