;; (ns org.naturallexicon.rdf.en.form
;;   {:sh:prefix "enForm"
;;    :sh:namespace "http://rdf.naturallexicon.org/en/form/"
;;    })



(ns sparql-client.core
  (:require
   ;;[clojure.string :as s]
   [sparql-endpoint.core :as endpoint]
   [igraph.core :refer :all]
   [igraph.graph :as graph]
   [selmer.parser :as selmer]
   [vocabulary.core :as voc]
   [taoensso.timbre :as log]
   ;;[org.naturallexicon.lod.wikidata.wd :as wd]
   )
  (:gen-class))

(log/set-level! :info)

(def the unique)

(defn query-endpoint [client query]
  "Returns [<simplified-binding> ...] for `query` posed to `client`
Where
<simpified-binding> := {<key> <value> ...},
   the output of the binding translator of <client>
<query> is a SPARQL SELECT query
<client> is a SparqlClient
"
  (let [simplifier (fn [sparql-binding]
                     (endpoint/simplify sparql-binding
                                        (:binding-translator client)))
        ]
    (log/debug query)
    (map simplifier
         (endpoint/sparql-select (:query-url client) query))))


(defn ask-endpoint [client query]
  "Returns boolean value of `query` posed to `client`
Where
<query> is a SPARQL ASK query
<client> is a SparqlClient
"
    (endpoint/sparql-ask (:query-url client) query))


(defn count-subjects [client]
  "Returns the number of subjects at endpoint of  `client`
Where
<client> is a SparqlClient
"
  (let [query "Select (Count (?s) as ?sCount) Where {?s ?p ?o}"
        ]
    (:?sCount (the (query-endpoint client query)))))

(defn query-for-subjects [client]
  "Returns [<subject> ...] at endpoint of `client`
Where
<subject> is the uri of a subject from <client>, 
  rendered per the binding translator of <client>
<client> is a SparqlClient
"
  (let [query "Select ?s Where {?s ?p ?o}"
        ]
    (map :?s
         (query-endpoint client query))))

(defn query-for-p-o [client s]
  "Returns {<p> #{<o>...}...} for `s` at endpoint of `client`
Where
<p> is a predicate URI rendered per binding translator of <client>
<o> is an object value, rendered per the binding translator of <client>
<s> is a subject uri keyword. ~ voc/voc-re
<client> is a SparqlClient
"
  (let [query  (voc/prepend-prefix-declarations
                (selmer/render
                 "Select ?p ?o Where { {{subject}} ?p ?o}"
                 {:subject (voc/qname-for s)}))
        collect-bindings (fn [acc b]
                           (update acc (:p b)
                                   (fn[os] (set (conj os (:o b))))))
                                                
        ]
    (log/debug query)
    (reduce collect-bindings {}
            (query-endpoint client query))))

(defn query-for-o [client s p]
  "Returns #{<o>...} for `s` and `p` at endpoint of `client`
Where:
<o> is an object rendered per binding translator of <client>
<s> is a subject URI rendered per binding translator of <client>
<p> is a predicate URI rendered per binding translator of <client>
<client> is a SparqlClient
"
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
  "Returns true if `s` `p` `o` is a triple at endpoint of `client`
Where:
<s> <p> <o> are subject, predicate and object
<client> is a SparqlClient
"
  (let [query (voc/prepend-prefix-declarations
               (selmer/render
                "ASK where { {{subject|safe}} {{predicate|safe}} {{object|safe}}. }"
                {:subject (voc/qname-for s)
                 :predicate (voc/qname-for p)
                 :object (if (keyword? o)
                           (voc/qname-for o)
                           o)
                 }))
        ]
    (log/debug query)
    (ask-endpoint client query)))

(defrecord 
  ^{:doc "An IGraph compliant view on a SPARQL endpoint
With arguments <query-url> <binding-translator>
Where
<query-url> is URL of a SPARQL query endpoint
<binding-translator> := fn[<binding>] -> <simplified>
<binding> is the value returned by a call to <query-url>
<simplified> := {<key> <value> ...}"}
    SparqlClient [query-url binding-translator update-url auth]
  IGraph
  (normal-form [this] (throw (Exception. "NYI")))
  (subjects [this] (query-for-subjects this))
  (get-p-o [this s] (query-for-p-o this s))
  (get-o [this s p] (query-for-o this s p))
  (ask [this s p o] (ask-s-p-o this s p o))
  (query [this q] (query-endpoint this q))
  
  clojure.lang.IFn
  (invoke [g s] (get-p-o g s))
  (invoke [g s p] (match-or-traverse g s p))
  (invoke [g s p o] (match-or-traverse g s p o))
  
  )
