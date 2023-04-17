(ns ont-app.sparql-client.update-test
  {:voc/mapsTo 'ont-app.sparql-client.core-test
   }
  (:require [clojure.test :refer :all]
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
            [ont-app.graph-log.core :as glog :refer [entries
                                                     show]]
            [ont-app.graph-log.levels :as levels :refer :all]
            [ont-app.rdf.core :as rdf]
            [ont-app.rdf.test-support :as rdf-test]
            [ont-app.sparql-client.core :as core :refer []]
            [ont-app.sparql-client.ont :as ont :refer [update-ontology!]]
            [ont-app.sparql-client.core-test] ;; for vann mapping
            [ont-app.sparql-client.test-update-support :as tus :refer [drop-all
                                                                       drop-graph
                                                                       endpoint-live?
                                                                       ensure-fresh-graph
                                                                       graph-exists?
                                                                       make-test-graph
                                                                       sparql-endpoint
                                                                       test-graph-load-context
                                                                       with-valid-endpoint
                                                                       ]]
            [ont-app.sparql-endpoint.core :as endpoint]
            [ont-app.igraph.core :as igraph :refer [add
                                                    add!
                                                    assert-unique
                                                    normal-form
                                                    query
                                                    subtract!
                                                    subjects
                                                    t-comp
                                                    unique
                                                    ]]
            [ont-app.igraph-vocabulary.core :as igv :refer [mint-kwi]]
            [ont-app.igraph.graph :as native-normal :refer [make-graph]]
            [ont-app.igraph.test-support :as test-support]
            [ont-app.vocabulary.core :as voc]
            [ont-app.vocabulary.format :as voc-format :refer [encode-uri-string
                                                              decode-uri-string
                                                              ]]
            [ont-app.vocabulary.wikidata]
            [ont-app.vocabulary.linguistics]
            ))

(glog/log-reset!)

(timbre/set-level! :info) ;;:debug)

(defn log-reset!
  ([]
   (log-reset! :glog/TRACE))
  ([level]
   (glog/log-reset!)
   (glog/set-level! level)))

(comment
  (reset! sparql-endpoint "http://localhost:3030/sparql-client-test/")
  )


(deftest test-add-subtract
  (with-valid-endpoint
    (testing "Test adding and subtracting functions"
      (when-let [g (make-test-graph ::test-add-subtract-graph)]
        (is (= (normal-form g)
               {}))
        (is (= (normal-form (add! g  [[::A ::B ::C]
                                      [::A ::B ::D]]))
               {:sparql-client-test/A
                {:sparql-client-test/B
                 #{:sparql-client-test/C
                   :sparql-client-test/D
                   }}}))
        (is (= (normal-form (subtract! g [::A ::B ::C]))
               {:sparql-client-test/A
                {:sparql-client-test/B
                 #{:sparql-client-test/D
                   }}}))
        (is (= (normal-form (subtract! g [::A ::B]))
               {}))
        (core/drop-client! g)))))

(deftest write-timestamps
  (with-valid-endpoint
    (testing "Writing timestamps"
      (when-let [g (make-test-graph ::timestamps-test)
                 ]
        (add! g [::a ::b #inst "2000"])
        (is (= (-> (unique (g ::a ::b))
                   (.toInstant))
               (.toInstant #inst "2000")))
        (core/drop-client! g)))))

(deftest write-blank-nodes-issue-8
  (with-valid-endpoint
    (testing "Write a blank node to the test graph"
      (when-let [g (make-test-graph ::write-blank-node-test)
                 ]
        (let [
              add-statement (rdf/prefixed "INSERT en:EnglishForm en:blah _b2. _b2 a en:blah")
              ]
          (rdf/read-rdf core/standard-read-context
                        g "test/resources/dummy.ttl")
          (is (= (into
                  #{}
                  (query
                   g
                   (rdf/prefixed
                    "SELECT ?p ?o
                WHERE
                { 
                  Graph sparql-client-test:write-blank-node-test
                  { 
                    en:EnglishForm rdfs:subClassOf ?restriction.
                    ?restriction ?p ?o.
                  }
                 }")))
                 #{{:p :rdf/type, :o :owl/Restriction}
                   {:p :owl/onProperty, :o :dct/language}
                   {:p :owl/hasValue, :o :iso639/eng}}))
          (add! g [[:en/EnglishForm ::assoc :rdf-app/_:dummy]
                   [:rdf-app/_:dummy :rdfs/label "dummy for EnglishForm"]])
          (is (= (unique (g :en/EnglishForm (core/property-path "sparql-client-test:assoc/rdfs:label")))
                 "dummy for EnglishForm"))
          (core/drop-client! g)
          )))))
 
(deftest write-langstr-issue-10
  (glog/log-reset! (add glog/ontology
                        [:glog/LogGraph :glog/level :glog/DEBUG]))
  (with-valid-endpoint
    (testing "Write langstr to the test graph"
      (if-let [g (make-test-graph ::write-langstr-test)
               ]
        (let [
              add-statement (rdf/prefixed "INSERT en:EnglishForm en:blah _b2. _b2 a en:blah")
              ]
          (rdf/read-rdf core/standard-read-context g "test/resources/dummy.ttl")
          (add! g [[:enForm/cat
                    :rdf/type :en/EnglishForm
                    :ontolex/writtenRep #voc/lstr "cat@en"
                    ::tokenCount 1
                    ]])

          (is (g :enForm/cat :ontolex/writtenRep #voc/lstr "cat@en"))
          (is (g :enForm/cat ::tokenCount 1))
          (core/drop-client! g)
          )))))

;; SUPERSEDED BY rdf/read-rdf in v. 0.2.0
#_(deftest read-rdf-file-issue-11
  (with-valid-endpoint
    (testing "Read dummy file into graph"
      (when-let [g (make-test-graph ::read-rdf-file-test)
                 ]
        (is (= (type (core/load-rdf-file g "test/resources/dummy.ttl"))
               java.net.URI))
        (is (g :enForm/dog :rdf/type :en/EnglishForm))
        (core/drop-client! g)
        ))))

(defn do-update-with-transit-literal
  "Checks for rendering of transit content that won't break the update query."
  [tl]
  (with-valid-endpoint
    (when-let [g (make-test-graph ::update-with-transit-literal)]
      (let [q (render "INSERT { owl:Thing owl:hasThing {{tl|safe}}. } where {}"
                      {:tl tl})
            ]
        (rdf/prefixed q)

      #_(core/update-endpoint! g q)
      #_(core/drop-client! g)
      ))))

(deftest write-and-read-transit-issue-12
  (with-valid-endpoint
    (when-let [g (make-test-graph ::write-and-read-vector)
               ]
      (testing "write and read a vector"
        (is (= (rdf/render-literal [1 2 3])
               "\"[1,2,3]\"^^transit:json"))
        (add! g  [::A
                 ::hasVector [1 2 3]
                 ::hasInt 1
                 ::hasString "string"
                 ::hasVectorOfKws [::a]
                 ::hasMap {::a [#{\a \b \c}]}
                 ::hasInst #inst "2000"
                 ::hasVectorOfInst [#inst "2000"]
                 ::hasVectorOfLangStr [#voc/lstr "dog@en"]
                 ])

        (is (= (unique (g ::A ::hasVector))
               [1 2 3]))
        (is (= (unique (g ::A ::hasInt))
               1))
        (is (= (unique (g ::A ::hasString))
               "string"))
        (is (= (unique (g ::A ::hasVectorOfKws))
               [::a]))
        (is (= (unique (g ::A ::hasMap))
               {::a [#{\a \b \c}]}))
        (is (= (.toInstant (unique (g ::A ::hasInst)))
               (.toInstant #inst "2000")))
        (is (= (.toInstant (unique (unique (g ::A ::hasVectorOfInst)))))
            (.toInstant #inst "2000"))
        (is (= (unique (g ::A ::hasVectorOfLangStr))
               [#voc/lstr "dog@en"]))
        (core/drop-client! g)
        ))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test support from the IGraph module
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def readme-test-report
  "Holds the contents of the readme-examples report to examine in case of failure"
  (atom nil))

(defn init-standard-report
  []
  (-> (make-graph)
      (igraph/add [::test-support/StandardIGraphImplementationReport
                   ::test-support/makeGraphFn (fn [data]
                                                (with-valid-endpoint
                                                  (-> (make-test-graph)
                                                      (add! data))))
                   ])))

(defn run-implementation-tests
  "One-liner to test a fully-featured implemenation of all IGraph protcols except IGraphSet."
  [report]
  (assert (= (test-support/test-readme-eg-mutation-dispatch report)
             ::igraph/mutable))
  (-> report
      (test-support/test-readme-eg-access)
      (test-support/test-readme-eg-mutation)
      (test-support/test-readme-eg-traversal)
      (test-support/test-cardinality-1)
      ))

(defn prepare-standard-igraph-report
  []
  (-> (init-standard-report)
      (run-implementation-tests)))

(defn do-standard-implementation-tests
  []
  (let [report (prepare-standard-igraph-report)
        ]
    ;; `report` with be a graph of test results, some of which might be of type Failed...
    (reset! readme-test-report report)
    report))
  
(deftest standard-implementation-tests
  "Standard tests against examples in the IGraph README for immutable set-enabled graphs"
  (with-valid-endpoint
    (let [report (do-standard-implementation-tests)
          ]
      (is (empty? (test-support/query-for-failures report))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test support from the RDF module
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def rdf-test-report (atom nil))

(defn init-rdf-report
  "Creates a report graph for implementations tests for RDF test suppport module."
  []
  (let [call-write-method (fn call-write-method [g ttl-file]
                            (rdf/write-rdf
                             core/standard-write-context
                             g
                             ttl-file
                             :formats/Turtle))
        load-test-file (fn [url]
                         (let [graph-kwi (voc/keyword-for
                                          (voc/uri-str-for url))
                                                           
                               ]
                         (with-valid-endpoint
                           (ensure-fresh-graph @sparql-endpoint graph-kwi)
                           (rdf/load-rdf
                            (test-graph-load-context graph-kwi)
                            url))))
                          
        read-test-file (fn [client file-url]
                         (with-valid-endpoint
                           (ensure-fresh-graph @sparql-endpoint (:graph-uri client))
                           (rdf/read-rdf core/standard-read-context client file-url)))
                   
        graph-uri ::rdf-test-report-graph
        ]
  (-> (make-graph)
      (add [:rdf-app/RDFImplementationReport
            :rdf-app/makeGraphFn make-test-graph
            :rdf-app/loadFileFn load-test-file
            :rdf-app/readFileFn read-test-file
            :rdf-app/writeFileFn call-write-method
            ]))))

(defn do-rdf-implementation-tests
  "Runs the appropriate test support functions, creating a report graph."
  []
  (reset! rdf-test-report (init-rdf-report))
  (-> rdf-test-report
      ;; (rdf-test/test-bnode-support) Requires native bnode suppport (like Jena)
      (rdf-test/test-load-of-web-resource)
      (rdf-test/test-read-rdf-methods)
      (rdf-test/test-write-rdf-methods)
      (rdf-test/test-transit-support)
      ))

(deftest rdf-implementation-tests
  "Prepares a report from the RDF test support module and checks for errors."
  (with-valid-endpoint
    (let [report (do-rdf-implementation-tests)]
      (is (empty? (test-support/query-for-failures @report))))))

(defn instrument-rdf-implemenation-tests
  "Pokes around in the logs."
  []
  (let [bindings (glog/query-log
                  [[:?e :rdf/type :?type]
                   [:?e :glog/executionOrder :?order]
                   ]
                  )
        entries (map :?e (sort-by :?order
                                  bindings))
        entry (show (last entries))
        ]
    entry))


(defn doit
  []
  (reset! sparql-endpoint "http://localhost:3030/sparql-client-test/")
  (log-reset!)
  ;; (do-standard-implementation-tests)
  (do-rdf-implementation-tests)
  )

(comment ;; basic operations
  (def g (make-test-graph ::test-graph))
  (def standard-report (do-standard-igraph-implementation-tests))
  (def failures (test-support/query-for-failures standard-report))
  (add-tap (fn [the-tap]
             (if (and (:type the-tap)
                      (:value the-tap))
               (debug (:type the-tap)
                      ::value (:value the-tap)))))

  (add! g [::A ::B ::C])
  
  (rdf/ontology :formats/Turtle :formats/media_type)
  (rdf/write-rdf core/standard-read-context g (clojure.java.io/file "/tmp/blah.ttl") :formats/Turtle)
  (render core/write-rdf-construct-query-template
          {:graph (voc/uri-for (:graph-uri g))})
  (descendants :dct/MediaTypeOrExtent)

  (def f (rdf/load-rdf (add core/standard-read-context
                            [:sparql-client/IGraph
                             :sparql-client/graphURI ::test-load
                             :sparql-client/queryURL (str @sparql-endpoint "query")
                             :sparql-client/updateURL (str @sparql-endpoint "update")
                             ])
                       rdf-test/bnode-test-data))
  (def f' (rdf/read-rdf core/standard-read-context g rdf-test/bnode-test-data))

);; comment 


