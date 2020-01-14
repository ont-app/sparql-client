(ns ont-app.sparql-client.update-test
  {:vann/preferredNamespacePrefix "uptest"
   :vann/preferredNamespaceUri
   "http://rdf.naturallexicon.org/ont-app/sparql-client/update-test#"
   }
  (:require [clojure.test :refer :all]
            [clojure.string :as s]
            ;; 3rd party
            [taoensso.timbre :as timbre]
            ;; ont-app
            [ont-app.graph-log :as glog]
            [ont-app.sparql-client.core :refer :all]
            [ont-app.sparql-endpoint.core :as endpoint]
            [ont-app.igraph.core :refer :all]
            [ont-app.igraph.graph :as graph]
            [ont-app.igraph.core-test :as igraph-test]
            [ont-app.vocabulary.core :as voc]
            [ont-app.vocabulary.wikidata]
            [ont-app.vocabulary.linguistics]
            ))

(timbre/set-level! :info) ;;:debug) ;; :info)
(glog/reset-log!)

(def clients-ref (atom (graph/make-graph)))
;; holds all clients used in testing [endpoint uri client]*

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


(defn make-test-graph
  ([]
   (when (not @endpoint-ref)
     (glog/warn! ::no-endpoint
                 :glog/message "No SPARQL_TEST_ENDPOINT variable defined"))
   (make-test-graph (ensure-final @endpoint-ref \/)
                    ::test-graph))
  ([uri]
   (make-test-graph (ensure-final @endpoint-ref \/)
                    uri))
  ([endpoint uri]
   (swap! clients-ref add
          [endpoint
            uri
            (make-sparql-updater
             :graph-uri uri
             :query-url (str endpoint "query")
             :update-url (str endpoint "update"))])
   (the (@clients-ref endpoint uri))))


(defn drop-all
  ([] (drop-all (ensure-final @endpoint-ref \/)))
  ([endpoint] (update-endpoint
               (or (the (@clients-ref endpoint)
                        first)
                   (make-test-graph endpoint ::dummy))
               "DROP ALL")))

(defn drop-client
  ([]
   (drop-client (ensure-final @endpoint-ref \/)
                ::test-graph))
  ([endpoint]
   (drop-client endpoint
               ::test-graph))
  ([endpoint uri]
   (when-let [g (the (@clients-ref endpoint uri))]
    (update-endpoint
     g
     (prefixed (str "DROP GRAPH " (voc/qname-for uri))))
    (swap! clients-ref subtract [endpoint uri]))))

(defn reset-client
  ([]
   (reset-client @endpoint-ref ::test-graph))
  ([endpoint]
   (reset-client endpoint ::test-graph))
  ([endpoint uri]
   (drop-client endpoint uri)
   (make-test-graph endpoint uri)))
  

(deftest test-add-subtract
  (testing "Test adding and subtracting functions"
    (if @endpoint-ref
      (let [g (reset-client)]
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
               {})))
      ;; else
      (glog/warn! ::no-endpoint
                  :glog/message
                  "No SPARQL_TEST_ENDPOINT env variable defined"))))

(deftest test-readme
  (testing "igraph readme stuff"
    (if @endpoint-ref
      (let []
        (reset! igraph-test/eg
                (reset-client @endpoint-ref ::igraph-test/graph_eg))
        (reset! igraph-test/eg-with-types
                (reset-client @endpoint-ref ::igraph-test/graph_eg-with-types))
        (reset! igraph-test/mutable-eg
                (reset-client @endpoint-ref ::igraph-test/mutable-eg))
        
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
        )
      ;; else
      (glog/warn! ::no-endpoint
                  :glog/message
                  "No SPARQL_TEST_ENDPOINT env variable defined"))))


    

