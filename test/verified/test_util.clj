(ns verified.test-util
  "What the tests share: a scratch directory under target/ (not /tmp: the
   sandbox mounts its own /tmp over it), the meta-source index, built
   artifacts, and a router session driven over its stdio like an MCP client."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [meta-source.cli :as cli]
            [meta-source.index :as index]
            [verified.build :as build]))

(def root (.getCanonicalPath (io/file ".")))

(defn scratch
  "A fresh directory under target/test/."
  [name]
  (let [d (io/file root "target" "test" name)]
    (fs/delete-tree d)
    (fs/create-dirs d)
    (.getPath d)))

(def problems
  (delay
    (let [cfg (cli/config {:root root})]
      ((requiring-resolve 'babashka.deps/add-deps) {:deps (cli/under-root (:deps cfg) root)})
      (index/check (index/build cfg)))))

(defn build!
  "Builds an artifact in mode into dir; returns it, read."
  [mode dir]
  (let [f (str dir "/artifact.edn")]
    (with-out-str (build/-main (name mode) f))
    (edn/read-string (slurp f))))

(defn write-edn! [f x] (io/make-parents (io/file f)) (spit f (pr-str x)) f)

(defn audit [dir]
  (let [f (io/file dir "audit.log")]
    (if (.exists f) (mapv edn/read-string (str/split-lines (slurp f))) [])))

;; ---- a router session, as an MCP client

(defn start
  "Starts bb serve with opts (:artifact :live :audit :confirm) and
   initializes it, offering elicitation when asked to."
  [opts & {:keys [elicitation]}]
  (let [p (proc/process (into ["bb" "serve"] (mapcat (fn [[k v]] [(str "--" (name k)) v]) opts))
                        {:err :string})
        s {:p p :in (io/writer (:in p)) :out (io/reader (:out p)) :ids (atom 1)}]
    ((requiring-resolve 'verified.test-util/request) s "initialize"
     {:protocolVersion "2025-06-18" :clientInfo {:name "test"}
      :capabilities (if elicitation {:elicitation {}} {})})
    s))

(defn send! [{:keys [in]} msg]
  (.write in (str (json/generate-string msg) "\n"))
  (.flush in))

(defn recv [{:keys [out]}] (json/parse-string (.readLine out) true))

(defn request
  "Sends a request; returns the next message the router sends."
  [s method params]
  (send! s {:jsonrpc "2.0" :id (swap! (:ids s) inc) :method method :params params})
  (recv s))

(defn call
  "tools/call; returns {:text :error?}, or the router's own request when
   it asks the client something before answering."
  [s tool args]
  (let [msg (request s "tools/call" {:name tool :arguments args})]
    (if (:method msg)
      msg
      {:text (get-in msg [:result :content 0 :text]) :error? (boolean (get-in msg [:result :isError]))})))

(defn answer-elicitation
  "Answers the router's elicitation request; returns the call's result."
  [s req result]
  (send! s {:jsonrpc "2.0" :id (:id req) :result result})
  (let [msg (recv s)]
    {:text (get-in msg [:result :content 0 :text]) :error? (boolean (get-in msg [:result :isError]))}))

(defn stop [{:keys [p in]}] (.close in) @p)
