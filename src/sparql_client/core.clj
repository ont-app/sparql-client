;; (ns org.naturallexicon.rdf.en.form
;;   {:sh:prefix "enForm"
;;    :sh:namespace "http://rdf.naturallexicon.org/en/form/"
;;    })



(ns sparql-client.core
  (:require
   [clojure.string :as s]
   [sparql-endpoint.core :as endpoint]
   [igraph.core :refer :all]
   [igraph.graph :as graph]
   [selmer.parser :as selmer]
   [vocabulary.core :as voc]
   [vocabulary.wikidata]
   [taoensso.timbre :as log]
   ;;[org.naturallexicon.lod.wikidata.wd :as wd]
   )
  (:gen-class))

(log/set-level! :info)

(defn collect-ns-meta [g next-ns]
  (let [prefix (:sh/prefix (meta next-ns))
        namespace (:sh/namespace (meta next-ns))
        ]
    (if (and prefix namespace)
      (add g [[prefix :prefix-for next-ns]
                     [next-ns :namespace namespace]])
      g)))

(def ns-graph (reduce collect-ns-meta (graph/make-graph) (all-ns)))

(def wikidata-endpoint "https://query.wikidata.org/bigdata/namespace/wdq/sparql")

(defn uri-translator [sparql-binding]
  (def scsb sparql-binding)
  (voc/keyword-for (sparql-binding "value"))
  )

(defn form-translator [sparql-binding]
  "Returns a keyword for  `binding` as as a keyword URI for a natlex form"
  (keyword (str (sparql-binding "xml:lang")
                "Form:"
                (s/replace (sparql-binding "value")
                           " "
                           "_"))))

(def translator (assoc endpoint/default-translators
                       :uri uri-translator
                       :lang form-translator))

(defn simplify [sparql-binding]
  (endpoint/simplify sparql-binding translator))

(def the graph/unique)

(def test-query-1 "
SELECT ?enLabel
WHERE
{
  wd:Q5 rdfs:label ?enLabel; 
  Filter (Lang(?enLabel) = \"en\")
  }"
  )


(defn query-endpoint [client query]
  (let [simplifier (fn [sparql-binding]
                     (endpoint/simplify sparql-binding (:binding-translator client)))
        ]
    (log/info query)
    (map simplifier (endpoint/sparql-select (:endpoint-url client) query))))


(defn ask-endpoint [client query]
    (endpoint/sparql-ask (:endpoint-url client) query))



(defn count-subjects [client]
  (let [query "Select (Count (?s) as ?sCount) Where {?s ?p ?o}"
        ]
    (:sCount (the (query-endpoint client query)))))

(defn query-for-subjects [client]
  (let [query "Select ?s Where {?s ?p ?o}"
        ]
    (map :?s (query-endpoint client query))))

(defn query-for-p-o [client s]
  (let [query  (voc/prepend-prefix-declarations
                (selmer/render
                 "Select ?p ?o Where { {{subject}} ?p ?o}"
                 {:subject (voc/qname-for s)}))
        collect-bindings (fn [acc b]
                           (update acc (:p b)
                                   (fn[os] (set (conj os (:o b))))))
                                                
        ]
    (log/info query)
    (reduce collect-bindings {}
            (query-endpoint client query))))

(defn query-for-o [client s p]
  (let [query  (voc/prepend-prefix-declarations
                (selmer/render
                 "Select ?o Where { {{subject}} {{predicate}} ?o}"
                 {:subject (voc/qname-for s)
                  :predicate (voc/qname-for p)
                  }))
        collect-bindings (fn [acc b]
                           (conj acc (:o b)))
                                                
        ]
    (log/debug query)
    (reduce collect-bindings #{}
            (query-endpoint client query))))

(defn ask-s-p-o [client s p o]
  (let [query (voc/prepend-prefix-declarations
               (selmer/render
                "ASK where { {{subject}} {{predicate}} {{object|safe}}. }"
                {:subject (voc/qname-for s)
                 :predicate (voc/qname-for p)
                 :object (if (keyword? o)
                           (voc/qname-for o)
                           o)
                 }))
        ]
    (log/debug query)
    (ask-endpoint client query)))


(defrecord sparql-client [endpoint-url binding-translator]
  IGraph
  (normal-form [this] (throw (Exception. "NYI")))
  (subjects [this] (query-for-subjects this))
  (get-p-o [this s] (query-for-p-o this s))
  (get-o [this s p] (query-for-o this s p))
  (ask [this s p o] (ask-s-p-o this s p o))
  (query [this q] (query-endpoint this q))
  
  clojure.lang.IFn
  (invoke [g s] (get-p-o g s))
  (invoke [g s p] (get-o g s p))
  (invoke [g s p o] (ask g s p o))
  
  )
  
    
    

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!")
  (let [client (->sparql-client wikidata-endpoint translator)
        ]
    ;; #_(endpoint/sparql-select wikidata-endpoint test-query-1)
    ;; #_(query-endpoint client
    ;;                 test-query-1)
    ;; #_(query-for-p-o client "wd:Q76")
    ;; #_(get-p-o client "wd:Q76")
    ;; #_(client "wd:Q76")
    ;; #_(client :wd:Q76)
    ;; #_(get-o client :wd:Q76 :wdt:P166)
    ;; #_ (client :wd:Q76 :wdt:P166)
    ;; #_(client :wd:Q76 :wdt:P166 "\"Yowsa\"")
    ;; #_(client :wd:Q76 :wdt:P166 :wd:Q5593890)
    ;; #_(map (fn [q]
    ;;        (some #(re-matches #":enForm:.*" (str %))
    ;;              (client q :rdfs:label)))
    ;;      (traverse client (transitive-closure :wdt:P31) [] [:wd:Q76]d))
    (let [q (voc/prepend-prefix-declarations
                   "Select ?label
Where
{
  wd:Q76 wdt:P31* ?super.
  ?super rdfs:label ?label.
  Filter (Lang(?label) = \"en\")
}")]
      
      (query client q))
    #_(count-subjects (->sparql-client wikidata-endpoint translator))
    ;;(keyword (:sh/prefix (meta (find-ns 'iri.com.xmlns.foaf.0.1))) "blah")
    #_(print (voc/prepend-prefix-declarations test-query-1))
    #_(map simplify
           (endpoint/sparql-select
            wikidata-endpoint
            (voc/prepend-prefix-declarations test-query-1)))))

