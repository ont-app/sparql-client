(ns ont-app.sparql-client.update-test
  {:vann/preferredNamespacePrefix "uptest"
   :vann/preferredNamespaceUri
   "http://rdf.naturallexicon.org/ont-app/sparql-client/update-test#"
   }
  (:require [clojure.test :refer :all]
            [clojure.string :as s]
            ;; 3rd party
            [selmer.parser :as parser :refer [render]]
            [taoensso.timbre :as timbre]
            ;; ont-app
            [ont-app.graph-log.core :as glog]
            [ont-app.graph-log.levels :as levels :refer :all]
            [ont-app.sparql-client.core :as client :refer :all]
            [ont-app.sparql-endpoint.core :as endpoint]
            [ont-app.igraph.core :refer :all]
            [ont-app.igraph.graph :as graph]
            [ont-app.igraph.core-test :as igraph-test]
            [ont-app.vocabulary.core :as voc]
            [ont-app.vocabulary.wikidata]
            [ont-app.vocabulary.linguistics]
            ))



(glog/log-reset!)
(timbre/set-level! :info) ;;:debug) ;; :info)

(def endpoint-ref (atom (System/getenv "SPARQL_TEST_ENDPOINT")))
;; eg: "http://localhost:3030/ont-app/"

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
                       (prefixed
                        (render 
                         "ASK WHERE {graph {{uri}} {}}"
                         {:uri (voc/qname-for uri)}))))

(defn make-test-graph
  ([]
   (if (not @endpoint-ref)
     (fatal ::no-endpoint
            :glog/message "No SPARQL_TEST_ENDPOINT variable defined, e.g. http://localhost:3030/my-dataset/")
     (make-test-graph (ensure-final @endpoint-ref \/)
                      ::test-graph)))
  ([uri]
   (make-test-graph (ensure-final @endpoint-ref \/)
                    uri))
  ([endpoint uri]
   (when (graph-exists? endpoint uri)
     (warn ::GraphShouldNotExist
           :log/endpoint endpoint
           :log/uri uri
           :glog/message "Graph {{log/uri}} should not exist! (dropping now)")
     (endpoint/sparql-update
      endpoint
      (prefixed 
       (str "DROP GRAPH " (voc/qname-for uri)))))
   (make-sparql-updater
    :graph-uri uri
    :query-url (str endpoint "query")
    :update-url (str endpoint "update")
    )))


(defn drop-all
  ([] (drop-all (ensure-final @endpoint-ref \/)))
  ([endpoint] (update-endpoint
               (make-test-graph endpoint ::dummy))
               "DROP ALL"))

(defn drop-client
  ([g]
   (debug ::StartingDropClient
          :log/graph-uri (:graph-uri g)
          :glog/message "DROPPING GRAPH WITH URI {{log/graph-uri}}"
          )
   (update-endpoint
    g
    (value-debug
     ::DropGraphUpdate
     [:glog/message "Update: {{glog/value}}"]
     (prefixed 
      (str "DROP GRAPH " (voc/qname-for (:graph-uri g))))))))


(deftest test-add-subtract
  (testing "Test adding and subtracting functions"
    (if @endpoint-ref
      (let [g (make-test-graph ::test-add-subtract-graph)]
        (is (= (normal-form g)
               {}))
        (is (= (normal-form (add! g  [[::A ::B ::C]
                                      [::A ::B ::D]]))
               {:uptest/A
                {:uptest/B
                 #{:uptest/C
                   :uptest/D
                   }}}))
        (is (= (normal-form (subtract! g [::A ::B ::C]))
               {:uptest/A
                {:uptest/B
                 #{:uptest/D
                   }}}))
        (is (= (normal-form (subtract! g [::A ::B]))
               {}))
        (drop-client g))
      ;; else
      (warn ::no-endpoint
                  :glog/message
                  "No SPARQL_TEST_ENDPOINT variable defined, e.g. http://localhost:3030/my-dataset/"))))

(deftest test-readme
  (testing "igraph readme stuff"
    (if @endpoint-ref
      (let []
        (reset! igraph-test/eg
                (make-test-graph @endpoint-ref ::igraph-test/graph_eg))
        (reset! igraph-test/eg-with-types
                (make-test-graph @endpoint-ref
                                 ::igraph-test/graph_eg-with-types))
        (reset! igraph-test/mutable-eg
                (make-test-graph  @endpoint-ref ::igraph-test/mutable-eg))
        
        (add! @igraph-test/eg igraph-test/eg-data)
        (add! @igraph-test/eg-with-types
              (normal-form
               (union (graph/make-graph
                       :contents igraph-test/eg-data)
                      (graph/make-graph
                       :contents igraph-test/types-data))))
        (igraph-test/readme)
        
        (add! @igraph-test/mutable-eg igraph-test/eg-data)
        (igraph-test/readme-mutable)
        (drop-client @igraph-test/eg)
        (drop-client @igraph-test/eg-with-types)
        (drop-client @igraph-test/mutable-eg)
        )
      ;; else
      (warn ::no-endpoint
            :glog/message
            "No SPARQL_TEST_ENDPOINT variable defined, e.g. http://localhost:3030/my-dataset/"))))


(deftest write-timestamps
  (testing "Writing timestamps"
    (if @endpoint-ref

      (let [g (make-test-graph ::timestamps-test)
            ]
        (add! g [::a ::b #inst "2000"])
        (is (= (-> (the (g ::a ::b))
                   (.asCalendar)
                   (.toInstant))
               (.toInstant #inst "2000")))
        (drop-client g))
    ;; else
    (when (empty? (glog/query-log [[:?nep :rdf/type ::no-endpoint]]))
      (warn ::no-endpoint
            :glog/message
            "No SPARQL_TEST_ENDPOINT variable defined, e.g. http://localhost:3030/my-dataset/")))))

(deftest write-blank-nodes-issue-8
  #_(glog/log-reset! (add glog/ontology
                        [:glog/LogGraph :glog/level :glog/DEBUG]))
  (testing "Write a blank node to the test graph"
    (let [g (make-test-graph ::write-blank-node-test)
          add-statement (prefixed "INSERT en:EnglishForm en:blah _b2. _b2 a en:blah")
          ]
      (load-rdf-file g "test/resources/dummy.ttl")
      (is (= (into
              #{}
              (query-endpoint
               g
               (prefixed 
                "SELECT ?p ?o
                WHERE
                { 
                  Graph uptest:write-blank-node-test
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
      (is (= (the (g :en/EnglishForm (property-path "uptest:assoc/rdfs:label")))
             "dummy for EnglishForm"))
      (drop-client g)
      )))

(deftest write-langstr-issue-10
  (glog/log-reset! (add glog/ontology
                        [:glog/LogGraph :glog/level :glog/DEBUG]))
  (testing "Write langstr to the test graph"
    (let [g (make-test-graph ::write-langstr-test)
          add-statement (prefixed "INSERT en:EnglishForm en:blah _b2. _b2 a en:blah")
          ]
      (load-rdf-file g "test/resources/dummy.ttl")
      
             
      (add! g [[:enForm/cat
                :rdf/type :en/EnglishForm
                 :ontolex/writtenRep #langStr "cat@en"
                ]])
            
      (is (= (the (g :enForm/cat :ontolex/writtenRep))
             #langStr "cat@en"))
      (drop-client g)
      )))


(deftest read-rdf-file-issue-11
  (testing "Read dummy file into graph"
    (let [g (make-test-graph ::read-rdf-file-test)
          ]
      (is (= (type (load-rdf-file g "test/resources/dummy.ttl"))
             java.net.URI))
      (is (g :enForm/dog :rdf/type :en/EnglishForm))
      (drop-client g)
      )))


      
(comment
 
  )
