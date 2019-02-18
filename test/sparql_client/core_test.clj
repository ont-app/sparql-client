(ns sparql-client.core-test
  (:require [clojure.test :refer :all]
            [clojure.string :as s]
            [sparql-client.core :refer :all]
            [igraph.core :refer :all]
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
  SELECT ?barryLabel
WHERE
{
  wd:Q75 rdfs:label ?esLabel; 
  Filter (Lang(?barryLabel) = \"en\")
  }"
  )
"

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
           [{:esLabel :esForm:ser_humano}]))))
