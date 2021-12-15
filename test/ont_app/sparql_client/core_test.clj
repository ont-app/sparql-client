(ns ont-app.sparql-client.core-test
  (:require [clojure.test :refer :all]
            [clojure.string :as s]
            [clojure.set :as set]
            [taoensso.timbre :as timbre]
            [ont-app.graph-log.core :as glog]
            [ont-app.sparql-client.core :as sparql :refer :all]
            [ont-app.vocabulary.lstr :refer [lang]]
            [ont-app.igraph.core :refer :all]
            [ont-app.igraph.graph :as graph]
            [ont-app.vocabulary.core :as voc]
            [ont-app.vocabulary.wikidata :as wikidata]
            ))

(glog/log-reset!)
(glog/set-level! :glog/LogGraph :glog/INFO)
(timbre/set-level! :info) ;;:debug) ;; :info)

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
  #{ :wd/Q24229398 :wd/Q5 :wd/Q215627 :wd/Q795052
    :wd/Q3778211 :wd/Q154954 :wd/Q830077 :wd/Q35120 :wd/Q23958946
    :wd/Q18336849}) ;; subject to change depending on WD.


(deftest test-accessors
  (testing "Test accessor functions"
    (is (= (:query-url client)
           "https://query.wikidata.org/bigdata/namespace/wdq/sparql"))
    (is (= (filter #(re-find #"^en$" (lang %))
                   (client :wd/Q76 :rdfs/label))
           '(#lstr "Barack Obama@en")))
    (is (#{#lstr "奧巴馬@zh"
           #lstr "奥巴马@zh"
           #lstr "巴拉克·奧巴馬@zh"
           }
         (first (filter #(re-find #"^zh$" (lang %))
                        (client :wd/Q76 :rdfs/label)))))
           
    (is (= (client :wd/Q76 instance-of :wd/Q5)
           :wd/Q5))
    #_(is (= ((:rdfs/label (client :wd/Q76)) #lstr "Barack Obama@en")
             #lstr "Barack Obama@en"))
    ;; ... TODO
    (is (client :wd/Q76 :rdfs/label #lstr "Barack Obama@en"))

    (is (= (vec (query client (prefixed
                               what-is-spanish-for-human?)))
           [{:esLabel #lstr "ser humano@es"}]))
    ;; testing p-traversal function...
    (is (= (set/difference
            minimal-subclass-test-membership
            (client :wd/Q76 instance-of)
            )
           ;; This list may change periodically in WD ...
           #{}))
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
           '({:label #lstr "Barack Obama@en"}))

    )))


(deftest check-ns-metadata-issue-3
  (testing "Warn if there is no namespace metadata"
    (glog/log-reset!)
    (timbre/with-config (merge timbre/*config* {:level :fatal})
      (check-ns-metadata ::in-test-ns)) ;; just want glog logging
    (check-ns-metadata :sparql-client/in-sparql-client-ns)
    (check-ns-metadata ::sparql/in-sparql-client-ns)
    (let [q (glog/query-log [[:?issue :rdf/type ::sparql/NoMetaDataInNS]])
          ]
      (is (= (count q) 1))
      (is (= (the (:log/kwi (second (glog/ith-entry 0))))
             ::in-test-ns)))))
