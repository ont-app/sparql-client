(ns ont-app.sparql-client.test-update-support
  "Supporting logic for updatable endpoints holding test data."
  {:voc/mapsTo 'ont-app.sparql-client.core-test
   }
  (:require ;; [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.repl :refer [apropos]]
            [clojure.reflect :refer [reflect]]
            [clojure.pprint :refer [pprint]]
            ;; 3rd party
            [selmer.parser :as parser :refer [render]]
            [taoensso.timbre :as timbre]
            ;; ont-app
            [ont-app.graph-log.core :as glog]
            [ont-app.graph-log.levels :as levels :refer :all]
            [ont-app.rdf.core :as rdf]
            [ont-app.rdf.test-support :as rdf-test]
            [ont-app.sparql-client.core :as core :refer []]
            [ont-app.sparql-client.ont :as ont :refer [update-ontology!]]
            [ont-app.sparql-endpoint.core :as endpoint]
            [ont-app.igraph.core :as igraph :refer [add
                                                    add!
                                                    assert-unique
                                                    normal-form
                                                    subtract!
                                                    subjects
                                                    t-comp
                                                    unique
                                                    ]]
            [ont-app.igraph-vocabulary.core :as igv :refer [mint-kwi]]
            [ont-app.igraph.graph :as native-normal :refer [make-graph]]
            [ont-app.igraph.test-support :as ts]
            [ont-app.vocabulary.core :as voc]
            [ont-app.vocabulary.format :as voc-format :refer [encode-uri-string
                                                              decode-uri-string
                                                              ]]
            [ont-app.vocabulary.wikidata]
            [ont-app.vocabulary.linguistics]
            ))


(println (str "ONT_APP_TEST_UPDATE_ENDPOINT:" (System/getenv "ONT_APP_TEST_UPDATE_ENDPOINT")))

(println (str "ONT_APP_TEST_UPDATE_AUTH:" (System/getenv "ONT_APP_TEST_UPDATE_AUTH")))

(def sparql-endpoint
  (atom 
   (System/getenv "ONT_APP_TEST_UPDATE_ENDPOINT")))

(def sparql-endpoint-auth
  (atom (System/getenv "ONT_APP_TEST_UPDATE_AUTH")))

(defn endpoint-live?
  []
  (not (nil? (try (if-let [req @sparql-endpoint-auth]
                    (clj-http.client/head @sparql-endpoint
                                          (read-string req))
                    ;; else no auth
                    (clj-http.client/head @sparql-endpoint))
                  (catch Throwable e
                    nil)))))

(defmacro with-valid-endpoint
  "Makes `body` conditional on an endpoint that is defined and up. Issues warnings otherwise."
  [& body]
  `(if @sparql-endpoint
     (if (endpoint-live?)
       (let []
         ~@body)
       ;; endpoint not live
       (warn :sparql-client-test/endpoint-down
             :glog/message
             "$ONT_APP_TEST_UPDATE_ENDPOINT {{endpoint}} not responding. We need a live updatable endpoint to run update tests."
             :endpoint @sparql-endpoint))
       ;; else no endpoint ref
       (warn :sparql-client-test/no-endpoint
             :glog/message
             "No ONT_APP_TEST_UPDATE_ENDPOINT variable defined, e.g. http://localhost:3030/my-dataset/. We need a live update endpoint to run update tests.")))

(defn ensure-final 
  "returns `s`, possibly appending `c` 
Where:
<s> is a string
<c> is a character
"
  [s c]
  {:pre [(char? c)
         (string? s)]
   }
  (if (= (last s) c)
    s
    (str s c)))

(defn graph-exists? [endpoint uri]
  (endpoint/sparql-ask endpoint
                       (core/prefixed
                        (render 
                         "ASK WHERE {graph {{uri|safe}} {}}"
                         {:uri (voc/qname-for uri)}))))

(defn test-graph-load-context
  "[graph-uri] -> load context"
  [graph-uri]
  (assert @sparql-endpoint)
  (core/create-load-context
   (str @sparql-endpoint "query")
   (str @sparql-endpoint "update")
   graph-uri))


(defn ensure-fresh-graph
  [endpoint uri]
  (when (graph-exists? endpoint uri)
    (warn :sparql-client-test/GraphAlreadyExists
          :log/endpoint endpoint
          :log/uri uri
          :glog/message "Graph {{log/uri}} already exists. (dropping now)")
         (endpoint/sparql-update
          endpoint
          (core/prefixed 
           (str "DROP GRAPH " (voc/qname-for uri))))))
  
(defn make-test-graph
  ([]
   (assert @sparql-endpoint)
   (make-test-graph (ensure-final @sparql-endpoint \/)
                    :sparql-client-test/test-graph))
  ([uri]
   (make-test-graph (ensure-final @sparql-endpoint \/)
                    uri))
  ([endpoint uri]
   (value-trace
    :sparql-client-test/starting-make-test-graph
    [:sparql-client-test/endpoint endpoint
     :sparql-client-test/uri uri]
    (try
      (let []
        (ensure-fresh-graph endpoint uri)
        (core/make-sparql-updater
         :graph-uri uri
         :query-url (str endpoint "query")
         :update-url (str endpoint "update")
         :authentication (if-let [req @sparql-endpoint-auth]
                           (read req))
         
         ))
      (catch Throwable e
        (println "Failed to make test graph with args " {:endpoint endpoint
                                                         :uri uri
                                                         })
        nil)))))

(defn drop-all
  ([] (drop-all (ensure-final @sparql-endpoint \/)))
  ([endpoint]
   (if-let [g (make-test-graph endpoint :sparql-client-test/dummy)]
     (let [] (core/update-endpoint!
              g
              "DROP ALL")
          (println "Test graph not available in `drop-all`")))))

(defn drop-graph
  [graph-kwi]
  (endpoint/sparql-update
   @sparql-endpoint
   (core/prefixed 
    (str "DROP GRAPH " (voc/qname-for graph-kwi)))))
  
