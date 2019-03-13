(ns sparql-client.core-test
  (:require [clojure.test :refer :all]
            [clojure.string :as s]
            [sparql-client.core :refer :all]
            [sparql-endpoint.core :as endpoint]
            [igraph.core :refer :all]
            [igraph.graph :as graph]
            [vocabulary.core :as voc]
            [vocabulary.wikidata]
            [vocabulary.linguistics]
            ))

(def wikidata-endpoint
  "https://query.wikidata.org/bigdata/namespace/wdq/sparql")

(defn- uri-translator
  "Returns <qualified-keyword> for `sparql-binding`
  Where
  <qualified-keyword> is a keyword in voc-re format
  <sparql-binding> := {?value ...}, typically returned from a query
  "
  {
   :test #(assert
           (= (uri-translator {"value" "http://xmlns.com/foaf/0.1/homepage"})
              :foaf:homepage))
   }
  [sparql-binding]
  (voc/keyword-for (sparql-binding "value"))
  )

(defn- form-translator [sparql-binding]
  "Returns a keyword for  `binding` as a keyword URI for a natlex form"
  (keyword (str (sparql-binding "xml:lang")
                "Form:"
                (s/replace (sparql-binding "value")
                           " "
                           "_"))))

(def translator (assoc endpoint/default-translators
                       :uri uri-translator
                       :lang form-translator))

(def client (->SparqlClient wikidata-endpoint translator nil nil))


(def what-is-spanish-for-human?  "
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
                  (traverse-link :wdt:P31)
                  (assoc context :phase :P31)
                  #{})
                         
        vec
        (traverse g
                  (transitive-closure :wdt:P279)
                  (assoc context :phase :P279)
                  #{}))
   []])

(deftest test-accessors
  (testing "Test accessor functions"
    (is (= ((:rdfs:label (client :wd:Q76)) :enForm:Barack_Obama)
           :enForm:Barack_Obama))
    (is (= ((client :wd:Q76 :rdfs:label) :enForm:Barack_Obama)
           :enForm:Barack_Obama))
    (is (= (client :wd:Q76 :rdfs:label "\"Barack Obama\"@en")
           true))
    (is (= (vec (query client (voc/prepend-prefix-declarations
                          what-is-spanish-for-human?)))
           [{:esLabel :esForm:ser_humano}]))
    ;; testing p-traversal function...
    (is (= (client :wd:Q76 isa->subClassOf*)
           ;; This list may change periodically in WD ...
           #{:wd:Q7887142 :wd:Q3778211 :wd:Q23958946 :wd:Q795052 :wd:Q2198779
             :wd:Q4330518 :wd:Q35120 :wd:Q488383 :wd:Q830077 :wd:Q18336849
             :wd:Q7184903 :wd:Q24229398 :wd:Q5 :wd:Q215627 :wd:Q154954}))
    ;; is Barry a Human?
    (is (= (client :wd:Q76 isa->subClassOf* :wd:Q5)
           :wd:Q5))
    ))

