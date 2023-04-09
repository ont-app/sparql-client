(ns ont-app.sparql-client.core-test
  {
    :vann/preferredNamespacePrefix "sparql-client-test"
    :vann/preferredNamespaceUri
    "http://rdf.naturallexicon.org/ont-app/sparql-client/test#"
   }
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.reflect :refer [reflect]]
            [clojure.repl :refer [apropos]]
            [clojure.string :as str]
            [clojure.set :as set]
            ;; 3rd party
            [selmer.parser :as parser :refer [render]]
            [taoensso.timbre :as timbre]
            ;; ont-app
            [ont-app.graph-log.core :as glog :refer [entries ith-entry show query-log]]
            [ont-app.graph-log.levels :as levels :refer :all]
            [ont-app.igraph.core :as igraph :refer [add
                                                    add!
                                                    assert-unique
                                                    flatten-description
                                                    normal-form
                                                    reduce-spo
                                                    subtract
                                                    subtract!
                                                    subjects
                                                    transitive-closure
                                                    t-comp
                                                    query
                                                    unique
                                                    ]]
            [ont-app.igraph.graph :as native-normal :refer [make-graph]]
            [ont-app.igraph-vocabulary.core :as igv :refer [mint-kwi]]
            [ont-app.rdf.core :as rdf]
            [ont-app.sparql-client.core :as core :refer []]
            [ont-app.sparql-client.ont :as ont :refer [update-ontology!]]
            [ont-app.sparql-client.test-update-support :refer [
                                                               drop-all
                                                               drop-graph
                                                               ;; endpoint-live?
                                                               graph-exists?
                                                               ;; make-test-graph
                                                               sparql-endpoint
                                                               with-valid-endpoint
                                                               ]]
            [ont-app.sparql-endpoint.core :as endpoint]
            [ont-app.vocabulary.core :as voc]
            [ont-app.vocabulary.format :as voc-format :refer [encode-uri-string
                                                              decode-uri-string
                                                              encode-kw-name
                                                              decode-kw-name
                                                              ]]
            [ont-app.vocabulary.lstr :refer [lang]]
            [ont-app.vocabulary.wikidata :as wikidata]
            ))


(timbre/set-level! :info) ;;:debug)

(defn log-reset!
  ([]
   (log-reset! :glog/TRACE))
               
  ([level]
   (glog/log-reset! (add glog/ontology
                         [[::core/clause-id
                           :rdf/type :glog/InformsUri]
                          ])
                    )
   (glog/set-level! level)))


(comment
  (reset! sparql-endpoint "http://localhost:3030/sparql-client-test/")
  )

(def wd-client (core/make-sparql-reader :query-url wikidata/sparql-endpoint))

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
  #{
    :wd/Q5
    ;; :wd/Q154954
    ;; :wd/Q18336849
    ;; :wd/Q215627
    ;; :wd/Q23958946
    ;; :wd/Q24229398
    ;; :wd/Q35120
    ;; :wd/Q3778211
    ;; :wd/Q795052
    ;; :wd/Q830077
    }) ;; subject to change depending on WD.


(deftest test-accessors
  (testing "Test accessor functions"
    (is (= (:query-url wd-client)
           "https://query.wikidata.org/bigdata/namespace/wdq/sparql"))
    (is (= (filter #(re-find #"^en$" (lang %))
                   (wd-client :wd/Q76 :rdfs/label))
           '(#voc/lstr "Barack Obama@en")))
    ;; lots of ways to transliterate this...
    (is (#{#voc/lstr "奧巴馬@zh"
           #voc/lstr "奥巴马@zh"
           #voc/lstr "巴拉克·奧巴馬@zh"
           #voc/lstr "贝拉克·奥巴马@zh"
           }
         (first (filter #(re-find #"^zh$" (lang %))
                        (wd-client :wd/Q76 :rdfs/label)))))
           
    (is (= (wd-client :wd/Q76 instance-of :wd/Q5)
           :wd/Q5))
    (is (= ((:rdfs/label (wd-client :wd/Q76)) #voc/lstr "Barack Obama@en")
             #voc/lstr "Barack Obama@en"))
    (is (wd-client :wd/Q76 :rdfs/label #voc/lstr "Barack Obama@en"))

    (is (= (vec (query wd-client (core/prefixed
                               what-is-spanish-for-human?)))
           [{:esLabel #voc/lstr "ser humano@es"}]))
    ;; testing p-traversal function...
    (is (= (set/difference
            minimal-subclass-test-membership
            (wd-client :wd/Q76 instance-of)
            )
           ;; This list may change periodically in WD ...
           #{}))
    ;; is Barry a Human?
    (is (= (wd-client :wd/Q76 instance-of :wd/Q5)
           :wd/Q5))
    (is (= (core/prefixed barry-query)
           "PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?label
WHERE
{
  wd:Q76 rdfs:label ?label; 
  Filter (Lang(?label) = \"en\")
  }"
           ))
    (is (= (query wd-client (core/prefixed barry-query))
           '({:label #voc/lstr "Barack Obama@en"}))

    )))

(deftest check-ns-metadata-issue-3
  (testing "Warn if there is no namespace metadata"
    (log-reset! :glog/INFO)
    (create-ns 'ns-with-no-metadata)
    (timbre/with-config (merge timbre/*config* {:level :fatal})
      ;; trigger the NoMetaDataInNS warning....
      (try (wd-client :ns-with-no-metadata/in-test-ns)
           (catch Throwable e)))
    (let [q (glog/query-log [[:?issue :rdf/type ::core/NoMetaDataInNS]])
          ]
      (is (= (count q) 1))
      (if (> (count q) 0)
        (is (= (unique (:kwi (second (glog/ith-entry 0))))
               :ns-with-no-metadata/in-test-ns))))))

(comment
  client
  (filter #(re-find #"^en$" (lang %))
          (client :wd/Q76 :rdfs/label))
  (filter #(re-find #"^zh$" (lang %))
          (client :wd/Q76 :rdfs/label))
  )

;;;;;;;;;;;;;;;;;;;;;;;;;
;; BNODE ROUND-TRIPPING
;;;;;;;;;;;;;;;;;;;;;;;;;

;; Test data from "jack.ttl"

(def test-graph-load-context
  (partial core/create-load-context
           (str @sparql-endpoint "query")
           (str @sparql-endpoint "update")))

(defn select-for-description-path
  "Returns SPARQL bindings for properties of `bnode-description` in `g`
  - where
    - `g` is a sparql-client
    - `bnode-description` is output of (`render-element` `ann` `bnode`)
    - `ann` := native-normal graph describing the context of `bnode` in some `g`
    - `bnode` is a blank node observed in `g`.
  "
  [g bnode-description]
  (assert bnode-description)
  (map endpoint/simplifier-with-kwis
       (endpoint/sparql-select
        (:query-url g)
        (value-info
         ::query
         (rdf/prefixed
          (render "
SELECT *
FROM <{{graph-uri}}>
Where
{
  {{bnode-description}} ?p ?o.
}
"
                  {:graph-uri (voc/uri-for (:graph-uri g))
                   :bnode-description bnode-description
                   }))))))

(def expected-jack-subjects-decoded-names
  "The set of subjects expected to be retrieved from jack.ttl if bnodes have been annotated."
  #{
    "Jack" ;; Jack (the only proper URI)
    ;; the house
    "[rdf:type sparql-client-test:House; ^sparql-client-test:built sparql-client-test:Jack; ^sparql-client-test:livedIn [rdf:type sparql-client-test:Mouse; ^sparql-client-test:ate [rdf:type sparql-client-test:Cat; ^sparql-client-test:chased [rdf:type sparql-client-test:Dog]]]]"
    ;; the mouse
    "[rdf:type sparql-client-test:Mouse; sparql-client-test:livedIn [rdf:type sparql-client-test:House; ^sparql-client-test:built sparql-client-test:Jack]; ^sparql-client-test:ate [rdf:type sparql-client-test:Cat; ^sparql-client-test:chased [rdf:type sparql-client-test:Dog]]]"
    ;; the cat
    "[rdf:type sparql-client-test:Cat; sparql-client-test:ate [rdf:type sparql-client-test:Mouse; sparql-client-test:livedIn [rdf:type sparql-client-test:House; ^sparql-client-test:built sparql-client-test:Jack]]; ^sparql-client-test:chased [rdf:type sparql-client-test:Dog]]"
    ;; the dog
    "[rdf:type sparql-client-test:Dog; sparql-client-test:chased [rdf:type sparql-client-test:Cat; sparql-client-test:ate [rdf:type sparql-client-test:Mouse; sparql-client-test:livedIn [rdf:type sparql-client-test:House; ^sparql-client-test:built sparql-client-test:Jack]]]]"
    }
)

(deftest bnode-annotation-and-round-tripping
  (with-valid-endpoint
    (let [jack (rdf/load-rdf
                (test-graph-load-context ::test-jack)
                (io/resource "jack.ttl"))
          jack' (core/reset-annotation-graph jack)
          ann (:bnodes jack')
          client-model (unique (ann :bnode/AnnotationGraph :bnode/client-model))
          ;; the cat that ate the mouse and got chased by the dog...
          ;; this is the clause describing the type of the cat
          type-cat-clause :bnode/SubordinateClause.p=rdf:type.corr=sparql-client-test:Cat
          ;; ...its `node` is the bnode for the cat itself....
          cat-bnode (unique (ann type-cat-clause :bnode/node))
          ]
      #_(is (= "[rdf:type sparql-client-test:Cat; sparql-client-test:ate [rdf:type sparql-client-test:Mouse; sparql-client-test:livedIn [rdf:type sparql-client-test:House; ^sparql-client-test:built sparql-client-test:Jack]]; ^sparql-client-test:chased [rdf:type sparql-client-test:Dog]]"
             cat-bnode-description))
      ;; select-test returns something like....
      ;; [{:p :rdf/type, :o :sparql-client-test/Cat}
      ;;  {:p :sparql-client-test/ate, :o "b0"}]
      ;; the `b0` part may vary depending on implementation
      #_(is (not (empty? (filter (fn [b] (and (= (:p b) :rdf/type)
                                            (= (:o b) :sparql-client-test/Cat)))
                               select-test))))
      #_(is (not (empty? (filter (fn [b] (= (:p b) :sparql-client-test/ate))
                               select-test))))
      (is (= expected-jack-subjects-decoded-names
             (into #{} (map (comp decode-kw-name name) (subjects jack')))))

      ;; Jack built the house (that was lived in by the mouse, etc)
      (is (= "[rdf:type sparql-client-test:House; ^sparql-client-test:built sparql-client-test:Jack; ^sparql-client-test:livedIn [rdf:type sparql-client-test:Mouse; ^sparql-client-test:ate [rdf:type sparql-client-test:Cat; ^sparql-client-test:chased [rdf:type sparql-client-test:Dog]]]]"
             (unique (map (comp decode-kw-name name)
                          (jack' :sparql-client-test/Jack :sparql-client-test/built)))))
      (let [desc (jack' :sparql-client-test/Jack)]
        (is (= #{:rdf/type :sparql-client-test/built}
               (into #{} (keys desc))))
        (is (= #{"[rdf:type sparql-client-test:House; ^sparql-client-test:built sparql-client-test:Jack; ^sparql-client-test:livedIn [rdf:type sparql-client-test:Mouse; ^sparql-client-test:ate [rdf:type sparql-client-test:Cat; ^sparql-client-test:chased [rdf:type sparql-client-test:Dog]]]]"}
               (into #{} (map (comp decode-kw-name name)
                              (desc :sparql-client-test/built)))))
        )
      (core/drop-client! jack')
      )))

(comment
  (reset! sparql-endpoint "http://localhost:3030/sparql-client-test/")
  (drop-all)
  (def jack (rdf/load-rdf
                (test-graph-load-context ::test-jack)
                (io/resource "jack.ttl")))
  (def jack' (core/reset-annotation-graph jack))
  (def ann (:bnodes jack'))
  (def client-model (unique (igraph/get-o ann :bnode/AnnotationGraph :bnode/client-model)))
  (jack' :sparql-client-test/Jack :sparql-client-test/built)
  (jack' :sparql-client-test/Jack)
  (log-reset! :glog/TRACE)

  (defn doit
    []
    (log-reset! :glog/TRACE)
    ;;(log-reset! :glog/OFF)
    (let [jack' (core/reset-annotation-graph jack)
          ]
      (-> (make-graph)
          (add (core/get-normal-form-with-bnodes jack')))
      ;; (instrument-collect-rendered-as-logs)
      )))

;;;;;;;;;;;;;
;; PROFILING
;;;;;;;;;;;;;

(comment
  (require '[clj-async-profiler.core :as prof])
  ;; remember options  "-J-Djdk.attach.allowAttachSelf -A:dev"
  ;; also
  ;; sudo sysctl -w kernel.perf_event_paranoid=1
  ;; sudo sysctl -w kernel.kptr_restrict=0
  ;; see also https://github.com/clojure-goes-fast/clj-async-profiler
  (prof/profile (doit))
  (prof/serve-ui 7777)
  )

;;;;;;;;;;;;;;;;;;;
;; OTHER TEST DATA
;;;;;;;;;;;;;;;;;;;

(defn load-cedict-schema
  "Loads a graph containing the OWL spec for https://github.com/ont-app/cedict"
  []
  ;; An owl specification with bnodes
  (if @sparql-endpoint
    (-> 
     (rdf/load-rdf
      (test-graph-load-context ::cedict-schema)
      (io/resource "cedict-schema.ttl"))
     (core/reset-annotation-graph))
    ;; else
    (do
      (warn
       ::no-sparql-endpoint-defined
       :glog/message "No SPARQL endpoint defined for `test-jack` in sparql-client core tests")
      nil)))

(comment
  (defn load-gist
    "Loads the gist upper ontology from semantic arts, a non-trivial OWL specification.
  This is a local copy drawn from https://github.com/semanticarts/gist and converted to ttl."
    []
    (drop-all)
    ;;(log-reset! :glog/TRACE)
    (log-reset! :glog/OFF)
    ;;(try 
    (let [gist 
          (-> (rdf/load-rdf
               (test-graph-load-context ::gist)
               (io/file "/home/eric/Data/RDF/gistCore.ttl"))
              (core/reset-annotation-graph))
          ]
      gist)))


;;;;;;;;;;;;;;;;;;;;
;; INSTRUMENTATION
;;;;;;;;;;;;;;;;;;;;

(defn instrument-collect-rendered-as-result
  [& {:keys [element]}]
  (let [bindings (query-log (if element
                              [[:?e :rdf/type ::core/collect-rendered-as-result]
                               [:?e ::core/element element]
                               ]
                              ;; else get 'em all
                              [[:?e :rdf/type ::core/collect-rendered-as-result]]))
        describe-binding (fn [b]
                           (let [e (-> b
                                       :?e
                                       show
                                       flatten-description
                                       )
                                 ann (::core/ann e)

                                 ]
                             (assoc e :element-description
                                    (ann (::core/element e)))))

        ]
    (sort-by :glog/execution-order
             (map describe-binding bindings))
    ))

(defn instrument-ont-coverage
  []
  (let [jack (-> (rdf/load-rdf
                  (test-graph-load-context ::test-jack)
                  (io/resource "jack.ttl"))
                 (core/reset-annotation-graph))
        ann (:bnodes jack)

        ont-constructs (into #{} (subjects @ont/ontology-atom))
        ann-constructs (reduce-spo (fn [sacc s p o]
                                     (reduce conj sacc [s p o]))
                                   #{}
                                   ann)
        ]
    (core/drop-client! jack)
    ;; (set/difference ont-constructs ann-constructs)
    ann
    ))
