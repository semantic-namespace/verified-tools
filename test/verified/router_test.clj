(ns verified.router-test
  "The router, replaying recorded responses: each refusal, the session's
   taint, asking the user by elicitation and by terminal, the caps, and
   what is masked. Each test is its own router session."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [verified.confirm :as confirm]
            [verified.router :as router]
            [verified.test-util :as u]))

(def dir (delay (let [d (u/scratch "router")] (u/build! :replay d) d)))

(defn- opts
  "A session's files, under its own directory: the shared artifact, and
   its own live.edn, audit and confirm."
  [name live]
  (let [d (str @dir "/" name)]
    (fs/create-dirs d)
    {:artifact (str @dir "/artifact.edn")
     :live (u/write-edn! (str d "/live.edn") live)
     :secrets (u/write-edn! (str d "/secrets.edn") {})
     :audit (str d "/audit")
     :confirm (str d "/confirm")}))

(defmacro with-session [[s name live & flags] & body]
  `(let [o# (opts ~name ~live)
         ~s (assoc (u/start o# ~@flags) :opts o#)]
     (try ~@body (finally (u/stop ~s)))))

(def october {"from" "2026-10-01" "to" "2026-10-31"})

(defn- rows [{:keys [text]}] (json/parse-string text true))

(deftest lists-the-artifacts-tools
  (with-session [s "list" {}]
    (let [tools (get-in (u/request s "tools/list" {}) [:result :tools])]
      (is (= #{"receipts_search" "share_snippet" "local_notes_search"} (set (map :name tools))))
      (is (str/includes? (:description (first (filter #(= "share_snippet" (:name %)) tools)))
                         "asks the user")))))

(deftest receipts
  (with-session [s "receipts" {}]
    (let [r (rows (u/call s "receipts_search" october))]
      (is (= 4 (count r)))
      (is (= 269.19 (/ (Math/round (* 100 (reduce + (map :receipt/amount r)))) 100.0)))
      (is (every? #(= #{:receipt/merchant :receipt/date :receipt/amount :receipt/currency :receipt/message-id}
                      (set (keys %)))
                  r)
          "only the declared keys"))))

(deftest refusals
  (with-session [s "refusals" {}]
    (doseq [[label tool args why] [["an extra argument" "receipts_search" (assoc october "q" "label:secrets") "arguments must be exactly"]
                                   ["a missing argument" "receipts_search" (dissoc october "to") "arguments must be exactly"]
                                   ["not a date" "receipts_search" (assoc october "from" "last month") "arguments must be exactly"]
                                   ["too long a text" "share_snippet" {"text" (apply str (repeat 2001 "x"))} "arguments must be exactly"]
                                   ["no such tool" "leaky_receipts_search" october "no such tool"]
                                   ["an excluded module's tool" "notes_search" {} "no such tool"]]]
      (testing label
        (let [{:keys [text error?]} (u/call s tool args)]
          (is error?)
          (is (str/includes? text why) text))))))

(deftest taint-blocks-before-asking
  (with-session [s "taint" {} :elicitation true]
    (u/call s "receipts_search" october)
    (let [res (u/call s "share_snippet" {"text" "Renfe 34.80, Amazon 19.99"})]
      (is (nil? (:method res)) "the user is not asked")
      (is (:error? res))
      (is (str/includes? (:text res) ":session/blocks")))
    (is (not-any? :asked-user (u/audit (get-in s [:opts :audit]))))))

(deftest asking-by-elicitation
  (with-session [s "elicitation" {} :elicitation true]
    (testing "the question is the router's: the tool, where it sends, the exact text"
      (let [req (u/call s "share_snippet" {"text" "Lunch on Thursday"})]
        (is (= "elicitation/create" (:method req)))
        (let [[l1 l2 l3] (str/split-lines (get-in req [:params :message]))]
          (is (= "Send this to paste.example (internet)?" l1) "where it goes, first")
          (is (= "text: \"Lunch on Thursday\"" l2) "exactly what goes, second")
          (is (str/starts-with? l3 "— share_snippet: ")))
        (is (= {:url "https://paste.example/s/abc123"}
               (update-keys (first (rows (u/answer-elicitation s req {:action "accept" :content {:allow true}}))) (comp keyword name))))))
    (doseq [[label result] [["unticked" {:action "accept" :content {:allow false}}]
                            ["declined" {:action "decline"}]
                            ["cancelled" {:action "cancel"}]]]
      (testing label
        (let [req (u/call s "share_snippet" {"text" "Lunch on Thursday"})
              {:keys [text error?]} (u/answer-elicitation s req result)]
          (is error?)
          (is (str/includes? text "did not allow") text))))))

(defn- answer-when-asked
  "As the user in their own terminal: waits for a question under dir and
   answers it."
  [dir answer]
  (future
    (loop [n 0]
      (if-let [f (first (fs/glob dir "pending/*.edn"))]
        (with-out-str (confirm/answer! dir (str/replace (str (fs/file-name f)) #"\.edn$" "") answer))
        (when (< n 200) (Thread/sleep 50) (recur (inc n)))))))

(deftest a-multi-line-payload-stays-on-one-line
  (with-session [s "one-line" {} :elicitation true]
    (let [req (u/call s "share_snippet" {"text" "Trip to Lisbon\n\nFlights on the 14th\nHotel in Alfama"})
          [_ l2] (str/split-lines (get-in req [:params :message]))]
      (is (= "text: \"Trip to Lisbon ⏎  ⏎ Flights on the 14th ⏎ Hotel in Alfama\"" l2) l2)
      (u/answer-elicitation s req {:action "decline"}))))

(deftest asking-in-the-terminal
  (with-session [s "terminal" {:confirm {:via :terminal :timeout-s 20}} :elicitation true]
    (let [cdir (get-in s [:opts :confirm])]
      (testing "yes"
        (answer-when-asked cdir "yes")
        (is (str/includes? (:text (u/call s "share_snippet" {"text" "a"})) "paste.example/s/")))
      (testing "no"
        (answer-when-asked cdir "no")
        (let [{:keys [text error?]} (u/call s "share_snippet" {"text" "b"})]
          (is error?)
          (is (str/includes? text "terminal: :no"))))
      (is (empty? (fs/glob cdir "pending/*.edn")) "nothing left waiting")))
  (testing "no answer is a no"
    (with-session [s "timeout" {:confirm {:via :terminal :timeout-s 1}}]
      (let [{:keys [text error?]} (u/call s "share_snippet" {"text" "c"})]
        (is error?)
        (is (str/includes? text ":no-answer"))))))

(deftest per-session-cap
  (with-session [s "cap" {} :elicitation true]
    (dotimes [_ 5]
      (u/answer-elicitation s (u/call s "share_snippet" {"text" "x"}) {:action "accept" :content {:allow true}}))
    (let [{:keys [text error?]} (u/call s "share_snippet" {"text" "x"})]
      (is error?)
      (is (str/includes? text ":caps")))))

(deftest notes-in-their-folder
  (let [notes (str u/root "/test/fixtures/notes")]
    (with-session [s "notes" {:roots {:mod/local-notes {:notes notes}}}]
      (let [r (rows (u/call s "local_notes_search" {"text" "lisbon"}))]
        (is (= #{"2026/lisbon.md" "lunch.md"} (set (map :note/path r))))
        (is (every? #(= #{:note/path :note/title :note/excerpt :note/excerpt-lines :note/lines} (set (keys %))) r))
        (is (= {:note/excerpt-lines "1-3" :note/lines 5}
               (select-keys (first (filter #(= "2026/lisbon.md" (:note/path %)) r)) [:note/excerpt-lines :note/lines]))
            "an excerpt says which lines it is, of how many"))
      (is (str/includes? (:text (u/call s "local_notes_search" {"text" "deposit"})) "[card-number masked]"))
      (is (= [] (rows (u/call s "local_notes_search" {"text" "not a note"}))) "an undeclared extension isn't read"))
    (testing "a link out of the folder leads nowhere: the sandbox mounts only the folder"
      (let [d (u/scratch "notes-with-link")]
        (spit (str d "/mine.md") "# Mine\nnothing here")
        (fs/create-sym-link (str d "/readme.md") (str u/root "/README.md"))
        (with-session [s "notes-link" {:roots {:mod/local-notes {:notes d}}}]
          (is (= [] (rows (u/call s "local_notes_search" {"text" "Verified local tools"})))))))
    (testing "the user says what a folder holds: a class the tool doesn't declare is refused"
      (with-session [s "notes-secret" {:roots {:mod/local-notes {:notes {:path notes :holds :data/secret}}}}]
        (let [{:keys [text error?]} (u/call s "local_notes_search" {"text" "lisbon"})]
          (is error?)
          (is (str/includes? text "you bound :notes as holding :data/secret") text)))
      (with-session [s "notes-classified" {:roots {:mod/local-notes {:notes {:path notes :holds :data/note}}}}]
        (is (= 2 (count (rows (u/call s "local_notes_search" {"text" "lisbon"})))))))
    (testing "an unbound root is refused, saying how to bind it"
      (with-session [s "notes-unbound" {}]
        (let [{:keys [text error?]} (u/call s "local_notes_search" {"text" "x"})]
          (is error?)
          (is (str/includes? text "{:roots {:mod/local-notes {:notes")) text)))))

(deftest a-result-says-what-it-leaves-out
  (let [d (u/scratch "many-notes")]
    (dotimes [i 25] (spit (str d "/n" i ".md") (str "# Note " i "\nabout lisbon")))
    (with-session [s "many" {:roots {:mod/local-notes {:notes d}}}]
      (let [msg (u/request s "tools/call" {:name "local_notes_search" :arguments {"text" "lisbon"}})
            [rows-item note] (get-in msg [:result :content])]
        (is (= 20 (count (json/parse-string (:text rows-item)))) "the per-call cap")
        (is (str/includes? (:text note) "first 20 results; there are more") (:text note)))
      (let [msg (u/request s "tools/call" {:name "local_notes_search" :arguments {"text" "Note 7"}})]
        (is (= 1 (count (get-in msg [:result :content]))) "no note when nothing is left out")))))

(deftest data-goes-back-silently-only-where-it-came-from
  (let [notes (str u/root "/test/fixtures/notes")
        live {:roots {:mod/local-notes {:notes notes}}}]
    (testing "a provider's data may go back to it"
      (with-session [s "origin-same" live]
        (u/call s "receipts_search" october)
        (is (not (:error? (u/call s "receipts_search" october))))))
    (testing "data from elsewhere may not go to a provider without asking"
      (with-session [s "origin-other" live]
        (u/call s "local_notes_search" {"text" "lisbon"})
        (let [{:keys [text error?]} (u/call s "receipts_search" october)]
          (is error?)
          (is (str/includes? text ":data/note from local-notes notes") text)
          (is (str/includes? text ":session/origin")))))
    (testing "a tool that asks is the user's call, with the payload shown"
      (with-session [s "origin-asks" live :elicitation true]
        (u/call s "local_notes_search" {"text" "lisbon"})
        (is (= "elicitation/create" (:method (u/call s "share_snippet" {"text" "Lisbon on the 14th"}))))))))

(deftest masking
  (let [patterns (get-in (u/build! :replay @dir) [:session :redact])
        redact @#'router/redact]
    (is (= "paid with [card-number masked], from [iban masked]"
           (redact patterns "paid with 4111 1111 1111 1111, from ES91 2100 0418 4502 0005 1332")))))

