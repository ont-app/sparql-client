(ns sparql-client.core-test
  (:require [clojure.test :refer :all]
            [clojure.string :as s]
            [clojure.set :as set]
            [sparql-client.core :refer :all]
            [sparql-endpoint.core :as endpoint]
            [igraph.core :refer :all]
            [igraph.graph :as graph]
            [vocabulary.core :as voc]
            ))

(def wikidata-endpoint
  "https://query.wikidata.org/bigdata/namespace/wdq/sparql")

(def client (make-graph :query-url wikidata-endpoint)) 

(def what-is-spanish-for-human? "
  SELECT ?esLabel
WHERE
{
  wd:Q5 rdfs:label ?esLabel; 
  Filter (Lang(?esLabel) = \"es\")
  }"
  )


^{:wd-equivalent "wdt:P31/wdt:P279*"}
(defn isa->subClassOf* [g context acc queue]
  [context
   (->> queue 
        (traverse g
                  (traverse-link :wdt/P31)
                  (assoc context :phase :P31)
                  #{})
                         
        vec
        (traverse g
                  (transitive-closure :wdt/P279)
                  (assoc context :phase :P279)
                  #{}))
   []])

(def minimal-subclass-test-membership
  #{:wd/Q7887142 :wd/Q3778211 :wd/Q23958946 :wd/Q795052 :wd/Q2198779
    :wd/Q4330518 :wd/Q35120 :wd/Q488383 :wd/Q830077 :wd/Q18336849
    :wd/Q7184903 :wd/Q24229398 :wd/Q5 :wd/Q215627 :wd/Q154954})

(deftest test-accessors
  (testing "Test accessor functions"
    (is (= ((:rdfs/label (client :wd/Q76)) :enForm/Barack_Obama)
           :enForm/Barack_Obama))
    (is (= ((client :wd/Q76 :rdfs/label) :enForm/Barack_Obama)
           :enForm/Barack_Obama))
    (is (= (client :wd/Q76 :rdfs/label "\"Barack Obama\"@en")
           true))
    (is (= (vec (query client (prefixed
                               what-is-spanish-for-human?)))
           [{:esLabel :esForm/ser_humano}]))
    ;; testing p-traversal function...
    (is (= (set/intersection
            (client :wd/Q76 isa->subClassOf*)
            minimal-subclass-test-membership)
           ;; This list may change periodically in WD ...
           minimal-subclass-test-membership))
    ;; is Barry a Human?
    (is (= (client :wd/Q76 isa->subClassOf* :wd/Q5)
           :wd/Q5))
    ))
