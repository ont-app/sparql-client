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
            [ont-app.graph-log.core :as glog]
            [ont-app.graph-log.levels :as levels :refer :all]
            [ont-app.rdf.core :as rdf]
            [ont-app.rdf.test-support :as rdf-test]
            [ont-app.sparql-client.core :as core :refer []]
            [ont-app.sparql-client.ont :as ont :refer [update-ontology!]]
            [ont-app.sparql-client.test-update-support :refer [drop-all
                                                               drop-client
                                                               drop-graph
                                                               endpoint-live?
                                                               graph-exists?
                                                               make-test-graph
                                                               sparql-endpoint
                                                               with-valid-endpoint
                                                               ]]
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

(glog/log-reset!)

(timbre/set-level! :info) ;;:debug)

(defn log-reset!
  ([]
   (log-reset! :glog/TRACE))
  ([level]
   (glog/log-reset!)
   (glog/set-level! level)))

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
        (drop-client g)))))

(deftest write-timestamps
  (with-valid-endpoint
    (testing "Writing timestamps"
      (when-let [g (make-test-graph ::timestamps-test)
                 ]
        (add! g [::a ::b #inst "2000"])
        (is (= (-> (unique (g ::a ::b))
                   (.toInstant))
               (.toInstant #inst "2000")))
        (drop-client g)))))

(deftest write-blank-nodes-issue-8
  (with-valid-endpoint
    (testing "Write a blank node to the test graph"
      (when-let [g (make-test-graph ::write-blank-node-test)
                 ]
        (let [
              add-statement (core/prefixed "INSERT en:EnglishForm en:blah _b2. _b2 a en:blah")
              ]
          (rdf/read-rdf core/standard-updater-io-context
                        g "test/resources/dummy.ttl")
          (is (= (into
                  #{}
                  (core/query-endpoint
                   g
                   (core/prefixed 
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

          (add! g [[:en/EnglishForm ::assoc :_/dummy]
                   [:_/dummy :rdfs/label "dummy for EnglishForm"]])
          (is (= (unique (g :en/EnglishForm (core/property-path "sparql-client-test:assoc/rdfs:label")))
                 "dummy for EnglishForm"))
          (drop-client g)
          )))))
 
(deftest write-langstr-issue-10
  (glog/log-reset! (add glog/ontology
                        [:glog/LogGraph :glog/level :glog/DEBUG]))
  (with-valid-endpoint
    (testing "Write langstr to the test graph"
      (if-let [g (make-test-graph ::write-langstr-test)
               ]
        (let [
              add-statement (core/prefixed "INSERT en:EnglishForm en:blah _b2. _b2 a en:blah")
              ]
          (rdf/read-rdf core/standard-updater-io-context g "test/resources/dummy.ttl")
          (add! g [[:enForm/cat
                    :rdf/type :en/EnglishForm
                    :ontolex/writtenRep #voc/lstr "cat@en"
                    ::tokenCount 1
                    ]])

          (is (g :enForm/cat :ontolex/writtenRep #voc/lstr "cat@en"))
          (is (g :enForm/cat ::tokenCount 1))
          (drop-client g)
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
        (drop-client g)
        ))))

(deftest write-and-read-transit-issue-12
  (with-valid-endpoint
    (when-let [g (make-test-graph ::write-and-read-vector)
               ]
      (testing "write and read a vector"
        (is (= (rdf/render-literal [1 2 3])
               "\"[1,2,3]\"^^transit:json"))
        (add! g [::A
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
        (drop-client g)
        ))))

(defn make-standard-igraph-report
  "Creates a configured report graph to use test support logic from ont-app/igraph"
  []
  (let [make-and-initialize-graph (fn make-and-initialize-graph [data]
                                    (-> (make-test-graph ::standard-igraph-test)
                                        (add! data)))
        ]
    (-> 
     (native-normal/make-graph)
     (add [::ts/StandardIGraphImplementationReport
           ::ts/makeGraphFn make-and-initialize-graph
           ]))))

(defn do-readme-eg-access
  [report]
  (value-debug
   ::test-readme-eg-access
   (ts/test-readme-eg-access report)))
  
(defn do-standard-igraph-implementation-tests
  []
  (-> (make-standard-igraph-report)
      (do-readme-eg-access)
      (ts/test-readme-eg-mutation)
      (ts/test-readme-eg-traversal)
      (ts/test-cardinality-1)
      ))

(deftest run-standard-implementation-tests
  "Runs the tests in igraph test-support module (does not include set ops)"
  (with-valid-endpoint 
    (is (empty? (-> (do-standard-igraph-implementation-tests)
                    (ts/query-for-failures))))))

(comment ;; basic operations
  (def g (make-test-graph ::test-graph))
  (def standard-report (do-standard-igraph-implementation-tests))
  (def failures (ts/query-for-failures standard-report))
  (add-tap (fn [the-tap]
             (if (and (:type the-tap)
                      (:value the-tap))
               (debug (:type the-tap)
                      ::value (:value the-tap)))))

  (add! g [::A ::B ::C])
  
  (rdf/ontology :formats/Turtle :formats/media_type)
  (rdf/write-rdf core/standard-updater-io-context g (clojure.java.io/file "/tmp/blah.ttl") :formats/Turtle)
  (render core/write-rdf-construct-query-template
          {:graph (voc/uri-for (:graph-uri g))})
  (descendants :dct/MediaTypeOrExtent)

  (def f (rdf/load-rdf (add core/standard-updater-io-context
                            [:sparql-client/IGraph
                             :sparql-client/graphURI ::test-load
                             :sparql-client/queryURL (str @sparql-endpoint "query")
                             :sparql-client/updateURL (str @sparql-endpoint "update")
                             ])
                       rdf-test/bnode-test-data))
  (def f' (rdf/read-rdf core/standard-updater-io-context g rdf-test/bnode-test-data))

);; comment 

