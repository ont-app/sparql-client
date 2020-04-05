(ns ont-app.sparql-client.core-test
  (:require [clojure.test :refer :all]
            [clojure.string :as s]
            [clojure.set :as set]
            [taoensso.timbre :as timbre]
            [ont-app.graph-log.core :as glog]
            [ont-app.sparql-client.core :as sparql :refer :all]
            [ont-app.sparql-endpoint.core :as endpoint]
            [ont-app.igraph.core :refer :all]
            [ont-app.igraph.graph :as graph]
            [ont-app.vocabulary.core :as voc]
            [ont-app.vocabulary.wikidata :as wikidata]
            ))

(glog/log-reset!)

(def client (make-sparql-reader :query-url wikidata/sparql-endpoint))


(def what-is-spanish-for-human? "
  SELECT ?esLabel
WHERE
{
  wd:Q5 rdfs:label ?esLabel; 
  Filter (Lang(?esLabel) = \"es\")
  }"
  )

(def barry-query
    "
SELECT ?label
WHERE
{
  wd:Q76 rdfs:label ?label; 
  Filter (Lang(?label) = \"en\")
  }")

(def instance-of (t-comp [:wdt/P31 (transitive-closure :wdt/P279)]))

(def minimal-subclass-test-membership
  #{:wd/Q488383 :wd/Q24229398 :wd/Q5 :wd/Q215627 :wd/Q795052
    :wd/Q3778211 :wd/Q154954 :wd/Q830077 :wd/Q35120 :wd/Q23958946
    :wd/Q18336849}) ;; subject to change depending on WD.


(deftest test-accessors
  (testing "Test accessor functions"
    (is (= (:query-url client)
           "https://query.wikidata.org/bigdata/namespace/wdq/sparql"))
    (is (= (filter (comp #(re-find #"^enForm" (namespace %)))
                   (client :wd/Q76 :rdfs/label))
           '(:enForm/Barack_Obama)))
    (is (= (filter (comp #(re-find #"^zhForm" (namespace %)))
                   (client :wd/Q76 :rdfs/label))
           '(:zhForm/巴拉克·奧巴馬)))
    (is (= (client :wd/Q76 instance-of :wd/Q5)
           :wd/Q5))
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
            (client :wd/Q76 instance-of)
            minimal-subclass-test-membership)
           ;; This list may change periodically in WD ...
           minimal-subclass-test-membership))
    ;; is Barry a Human?
    (is (= (client :wd/Q76 instance-of :wd/Q5)
           :wd/Q5))
    (is (= (prefixed barry-query)
           "PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?label
WHERE
{
  wd:Q76 rdfs:label ?label; 
  Filter (Lang(?label) = \"en\")
  }"))
    (is (= (query client (prefixed barry-query))
           '({:label :enForm/Barack_Obama})))

    ))


(deftest check-ns-metadata-issue-3
  (testing "Warn if there is no namespace metadata"
    (glog/log-reset!)
    (timbre/with-config (merge timbre/*config* {:level :fatal})
      (check-ns-metadata ::in-test-ns)) ;; just want to glog logging
    (check-ns-metadata :sparql-client/in-sparql-client-ns)
    (check-ns-metadata ::sparql/in-sparql-client-ns)
    (let [q (glog/query-log [[:?issue :rdf/type ::sparql/NoMetaDataInNS]])
          ]
      (is (= (count q) 1))
      (is (= (the (:log/kwi (second (glog/ith-entry 0))))
             ::in-test-ns)))))
