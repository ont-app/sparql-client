(ns sparql-client.update-test
  {:vann/preferredNamespacePrefix "uptest"
   :vann/preferredNamespaceUri "http://rdf.naturallexicon.org/ont-app/sparql-client/update-test#"
   }
  (:require [clojure.test :refer :all]
            [clojure.string :as s]
            [sparql-client.core :refer :all]
            [sparql-endpoint.core :as endpoint]
            [taoensso.timbre :as log]
            [igraph.core :refer :all]
            [igraph.graph :as graph]
            [vocabulary.core :as voc]
            [vocabulary.wikidata]
            [vocabulary.linguistics]
            ))

(log/set-level! :info)

(def client-ref (atom nil))
(def endpoint-ref (atom (System/getenv "SPARQL_TEST_ENDPOINT")))

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
   (make-test-graph (ensure-final @endpoint-ref \/)))

  ([endpoint]
   (make-graph
    :graph-uri ::test-graph
    :query-url (str endpoint "query")
    :update-url (str endpoint "update"))))



(defn client-fixture [test-fn]
  "Side-effect: runs `test-fn` in the context of a test graph named ::test-graph at a SPARQL endpoint typically specified by the env SPARQL_TEST_ENDPOINT.
Side-effect: drops ::test-graph
Where
<test-fn> is a deftest.
SPARQL_TEST_ENDPOINT should point to a SPARQL endpoint with update privileges.
"
  (if-let [endpoint @endpoint-ref]
    (do
      (reset! client-ref (make-test-graph)) 
      (test-fn)
      (update-endpoint
       @client-ref
       (prefixed (str "DROP GRAPH " (voc/qname-for ::test-graph))))
      (reset! client-ref nil)
      )
    ;; else no env var set...
    (log/warn "No SPARQL_TEST_ENDPOINT variable defined")))

(use-fixtures :once client-fixture)

(deftest test-add-subtract
  (testing "Test adding and subtracting functions"
    (is (= (normal-form (add @client-ref [[::A ::B ::C]
                                          [::A ::B ::D]]))
           {:uptest/A
            {:uptest/B
             #{:uptest/C
               :uptest/D
               }}}))
    (is (= (normal-form (subtract @client-ref [::A ::B ::C]))
           {:uptest/A
            {:uptest/B
             #{:uptest/D
               }}}))
    (is (= (normal-form (subtract @client-ref [::A ::B]))
           {}))
    ))


