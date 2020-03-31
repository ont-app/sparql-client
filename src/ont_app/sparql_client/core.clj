(ns ont-app.sparql-client.core
  {:vann/preferredNamespacePrefix "sparql-client"
   :vann/preferredNamespaceUri
   "http://rdf.naturallexicon.org/ont-app/sparql-client/core#"
   }
  (:require
   [clojure.string :as s]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as spec]
   ;; 3rd party
   [selmer.parser :as selmer]
   [taoensso.timbre :as timbre]
   ;; ont-app
   [ont-app.graph-log.core :as glog]
   [ont-app.graph-log.levels :as levels :refer :all]
   [ont-app.sparql-endpoint.core :as endpoint]
   [ont-app.igraph.core :as igraph :refer :all]
   [ont-app.igraph.graph :as graph]
   [ont-app.vocabulary.core :as voc]
   )
  (:gen-class))


(declare query-for-normal-form)
(declare query-for-subjects)
(declare query-for-p-o)
(declare query-for-o)
(declare ask-s-p-o)
(declare query-endpoint)
(declare update-endpoint)
(declare ask-endpoint)
(defrecord 
  ^{:doc "A read-only IGraph-compliant view on a SPARQL endpoint
Where
<graph-uri> is the name of the graph (nil implies DEFAULT)
<query-url> is URL of a SPARQL query endpoint
<binding-translator> := fn[<binding>] -> <simplified>
<update-url> is the URL of the update endpoint (nil implies read-only)
<auth> is the password or other authorization token
<binding> is the value returned by a call to <query-url>
<simplified> is a single scalar representation of a SPARQL binding. 
  See sparql-endpoint.core.
"
    }
    SparqlReader [graph-uri query-url binding-translator auth]
  IGraph
  (normal-form [this] (query-for-normal-form this))
  (subjects [this] (query-for-subjects this))
  (get-p-o [this s] (query-for-p-o this s))
  (get-o [this s p] (query-for-o this s p))
  (ask [this s p o] (ask-s-p-o this s p o))
  (query [this q] (query-endpoint this q))
  (mutability [this] ::igraph/read-only)
  
  clojure.lang.IFn
  (invoke [g] (normal-form g))
  (invoke [g s] (get-p-o g s))
  (invoke [g s p] (match-or-traverse g s p))
  (invoke [g s p o] (match-or-traverse g s p o))
  
  )

(defrecord 
  ^{:doc "An immutable IGraph-compliant view on a SPARQL endpoint
Where
<graph-uri> is the name of the graph (nil implies DEFAULT)
<query-url> is URL of a SPARQL query endpoint
<binding-translator> := fn[<binding>] -> <simplified>
<update-url> is the URL of the update endpoint (nil implies read-only)
<auth> is the password or other authorization token
<binding> is the value returned by a call to <query-url>
<simplified> is a single scalar representation of a SPARQL binding. 
  See sparql-endpoint.core.
"
    }
    SparqlUpdater [graph-uri query-url binding-translator update-url auth]
  IGraph
  (normal-form [this] (query-for-normal-form this))
  (subjects [this] (query-for-subjects this))
  (get-p-o [this s] (query-for-p-o this s))
  (get-o [this s p] (query-for-o this s p))
  (ask [this s p o] (ask-s-p-o this s p o))
  (query [this q] (query-endpoint this q))
  (mutability [this] ::igraph/mutable)

  IGraphMutable
  (add! [this to-add] (add-to-graph this to-add))
  (subtract! [this to-remove] (remove-from-graph this to-remove))
  
  clojure.lang.IFn
  (invoke [g] (normal-form g))
  (invoke [g s] (get-p-o g s))
  (invoke [g s p] (match-or-traverse g s p))
  (invoke [g s p o] (match-or-traverse g s p o))
  
  )


(spec/def ::sparql-client #(#{SparqlReader SparqlUpdater} (type %)))

(def the unique)
(def prefixed voc/prepend-prefix-declarations)

(declare default-binding-translators)

(def ask-if-graph-exists-template
  "
  ASK WHERE
  {
    Graph {{graph-qname|safe}} {}
  }
  ")

(def create-graph-template
  "
  CREATE GRAPH {{graph-qname|safe}}
  ")

(defn make-sparql-reader [& {:keys
                     [graph-uri query-url binding-translator auth]}]
  "Returns an instance of SparqlReader.
Where
<graph-uri> is the named graph within the SPARQL endpoint. nil implies DEFAULT
<query-url> is the query endpoint
<binding-translator> := {:uri <uri-fn> :lang <lang-fn> :datatype <datatype-fn>}
  default is sparql-endpoint.core/default-translators)
<auth> (optional)  is the authorization token needed to perform updates
<uri-fn> := (fn [binding]...) -> URI representation. 
  Default is just the URI string
<lang-fn> := (fn [binding] ...) -> parsed language tag. 
   Default is just the language string with no
<datatype-fn> := (fn [binding] -> Parsed datatype
<binding> := {:value ... 
              :type ... 
              &maybe 
              :xml:lang ... 
              :datatype ...}
  This occurs in bindings of the form {<var> <binding>, ...} returned by a 
  SPARQL query.
  See also sparql-endpoint.core.
"
  (let [client (->SparqlReader
                graph-uri
                query-url
                (or binding-translator
                    (default-binding-translators query-url graph-uri))
                auth)]
    (debug ::starting-make-sparql-reader ::graph-uri (:graph-uri client))
    (if (:graph-uri client)
      
      (let [graph-qname (voc/qname-for (:graph-uri client))]
        (if-not (ask-endpoint client
                              (prefixed
                               (selmer/render
                                ask-if-graph-exists-template
                                {:graph-qname graph-qname})))
          (throw (ex-info (str "Graph " graph-qname " does not exist")
                          {:type ::NoSuchGraph
                           :graph graph-qname})))
        ;; else the graph exists
        client)
        ;;else we're using the default graph
      client)))

(defn make-sparql-updater [& {:keys
                              [graph-uri query-url binding-translator
                               update-url auth]}]
  "Returns an instance of SparqlUpdater.
Where
<graph-uri> is the named graph within the SPARQL endpoint. nil implies DEFAULT
<query-url> is the query endpoint
<binding-translator> := {:uri <uri-fn> :lang <lang-fn> :datatype <datatype-fn>}
  default is sparql-endpoint.core/default-translators)
<update-url> (optional) is update endpoint (or nil if read-only)
<auth> (optional)  is the authorization token needed to perform updates
<uri-fn> := (fn [binding]...) -> URI representation. 
  Default is just the URI string
<lang-fn> := fn [binding] -> parsed language tag. 
   Default is just the language string
<datatype-fn> := fn [binding] -> Parsed datatype
<binding> := {:value ... 
              :type ... 
              &maybe 
              :xml:lang ... 
              :datatype ...}
  This occurs in bindings of the form {<var> <binding>, ...} returned by a 
  SPARQL query.
  See also sparql-endpoint.core.
"
  (let [client (->SparqlUpdater
                graph-uri
                query-url
                (or binding-translator
                    (default-binding-translators query-url graph-uri))
                update-url
                auth)]
    (debug ::starting-make-sparql-updater ::graph-uri (:graph-uri client))
    (if (:graph-uri client)
      (let [graph-qname (voc/qname-for (:graph-uri client))]
        (if-not (ask-endpoint client
                              (prefixed
                               (selmer/render
                                ask-if-graph-exists-template
                                {:graph-qname graph-qname})))
          (if (:update-url client)
            (do 
              (update-endpoint client
                               (prefixed
                                (selmer/render create-graph-template
                                               {:graph-qname graph-qname})))
              client)
          ;; else there is no update-url
            (throw (Exception. (str "Graph " graph-qname " does not exist, and there is no update URL.")))))
        ;; else the graph exists
        client)
        ;;else we're using the default graph
      client)))



(defn uri-translator
  "Returns <qualified-keyword> for `sparql-binding`
  Where
  <qualified-keyword> is a keyword in voc-re format
  <sparql-binding> := {?value ...}, typically returned from a query
  <query-url> is the URL of the query endpoint
  <graph-uri> is the URI of the graph
  NOTE: <query-url> and <graph-uri> are used to handle blank nodes.
  "
  {
   :test #(assert
           (= (uri-translator nil nil {"value" "http://xmlns.com/foaf/0.1/homepage"})
              :foaf:homepage))
   ;; TODO: add test for blank node
   }
  [sparql-binding]
  (voc/keyword-for (sparql-binding "value")))

(defn bnode-translator
  "Returns <bnode-keyword> for `sparql-binding`
  Where
  <bnode-keyword> is a keyword guaranteed to be unique even if merged
    with data from other endpoints and graphs
  <sparql-binding> := {'type' 'bnode', :value ..., ...}, typically returned
    from a query
  <query-url> is the URL of the query endpoint
  <graph-uri> is the URI of the graph
  "
  [query-url graph-uri sparql-binding]
  (keyword (str "_" (hash (str query-url graph-uri)))
           (sparql-binding "value")))



(defn form-translator [sparql-binding]
  "Returns a keyword for  `binding` as a keyword URI for a natlex form"
  (keyword (str (sparql-binding "xml:lang")
                "Form")
           (s/replace (sparql-binding "value")
                      " "
                      "_")))

(defn default-binding-translators
  "Binding translators used to simplify bindings. See sparq-endpoint.core
  <endpoint-url> and <graph-uri> are used to mint unique values for bnodes.
  "
  [endpoint-url graph-uri]
  (merge endpoint/default-translators
         {:uri uri-translator
          :lang form-translator
          :bnode (partial bnode-translator endpoint-url graph-uri)
          }))

(defn query-endpoint [client query]
  "Returns [<simplified-binding> ...] for `query` posed to `client`
Where
<simpified-binding> := {<key> <value> ...},
   the output of the binding translator of <client>
<query> is a SPARQL SELECT query
<client> is a SparqlReader or SparqlUpdater
"
  (let [simplifier (fn [sparql-binding]
                     (endpoint/simplify sparql-binding
                                        (:binding-translator client)))
        ]

    (value-debug
     ::query-endpoint-return
     [::query query
      ::query-url (:query-url client)]
     (map simplifier
          (endpoint/sparql-select (:query-url client)
                                  query)))))


(defn ask-endpoint [client query]
  "Returns boolean value of `query` posed to `client`
Where
<query> is a SPARQL ASK query
<client> conforms to ::sparql-client spec
"
  (value-debug
   ::ask-endpoint-return
   [::query-url (:query-url client)
    ::query query]
  (endpoint/sparql-ask (:query-url client)
                       query)))


(defn- query-template-map [client]
  "Returns {<k> <v>, ...} appropriate for <client>
Where
<k> and <v> are selmer template parameters which may appear in some query, e.g.
  named graph open/close clauses
<client> is a ::sparql-client
"
  {:graph-name-open (if-let [graph-uri (:graph-uri client)]
                      (str "GRAPH <" (voc/iri-for graph-uri) "> {")
                      "")
   :graph-name-close (if-let [graph-uri (:graph-uri client)]
                      (str "}")
                      "")
   })                          

(def normal-form-query-template
  "
  Select ?s ?p ?o
  Where
  {
    {{graph-name-open|safe}}
    ?s ?p ?o
    {{graph-name-close}}
  }
  ")

(defn query-for-normal-form [client]
  (letfn [(add-o [o binding]
            (conj o (:o binding)))
          (add-po [po binding]
            (assoc po (:p binding)
                   (add-o (get po (:p binding) #{})
                          binding)))
          (collect-binding [spo binding]
            (assoc spo (:s binding)
                   (add-po (get spo (:s binding) {})
                           binding)))
          
          ]
    (let [query (selmer/render normal-form-query-template
                               (query-template-map client))
          ]
      (reduce collect-binding {}
              (query-endpoint client query)))))
    
  
(def count-subjects-query-template
  "
  Select (Count (Distinct ?s) as ?sCount) 
  Where
  {
    {{graph-name-open|safe}}
    ?s ?p ?o
    {{graph-name-close}}
  }
  "
  )
(defn count-subjects [client]
  "Returns the number of subjects at endpoint of  `client`
Where
<client> conforms to  ::sparql-client spec
"
  (let [query (selmer/render count-subjects-query-template
                             (query-template-map client))
        ]
    (:?sCount (the (query-endpoint client query)))))

(def subjects-query-template
  "
  Select Distinct ?s Where
  {
    {{graph-name-open|safe}}
    ?s ?p ?o.
    {{graph-name-close|safe}}
  }
  ")

(defn query-for-subjects [client]
  "Returns [<subject> ...] at endpoint of `client`
Where
<subject> is the uri of a subject from <client>, 
  rendered per the binding translator of <client>
<client> conforms to ::sparql-client spec
"
  (let [query (selmer/render subjects-query-template
                             (query-template-map client))
        ]
    (def q query)
    (def x (query-endpoint client query))
    (map :s
         (query-endpoint client query))))


(def query-for-p-o-template
  "
  Select ?p ?o Where
  {
    {{graph-name-open|safe}}
    {{subject|safe}} ?p ?o.
    {{graph-name-close|safe}}
  }
  ")

(defn check-ns-metadata [kwi]
  "Logs a warning when `kwi` is in a namespace with no metadata."
  (let [n (symbol (namespace kwi))]
    (if-let [the-ns (find-ns n)]
      (when (not (meta the-ns))
        (warn ::NoMetaDataInNS
              :glog/message "The namespace for {{log/kwi}} is in a namespace with no associated metadata."
              :log/kwi kwi))))
  kwi)


(defn check-qname [uri-spec]
  "Traps the keyword assertion error in voc and throws a more meaningful error about blank nodes not being supported as first-class identifiers."
  (try
    (voc/qname-for (check-ns-metadata uri-spec))
    (catch java.lang.AssertionError e
      (if (= (str e)
             "java.lang.AssertionError: Assert failed: (keyword? kw)")
        (throw (ex-info (str "The URI spec " uri-spec " is not a keyword.\nCould it be a blank node?\nIf so, blank nodes cannot be treated as first-class identifiers in SPARQL. Use a dedicated query that traverses the blank node instead.")
                        (merge (ex-data e)
                               {:type ::Non-Keyword-URI-spec
                                ::uri-spec uri-spec
                                })))
                             
        ;; else it's some other message
        (throw e)))))
        
(defn query-for-p-o [client s]
  "Returns {<p> #{<o>...}...} for `s` at endpoint of `client`
Where
<p> is a predicate URI rendered per binding translator of <client>
<o> is an object value, rendered per the binding translator of <client>
<s> is a subject uri keyword. ~ voc/voc-re
<client> conforms to ::sparql-client
"
  (let [query  (prefixed
                (selmer/render query-for-p-o-template
                               (merge (query-template-map client)
                                      {:subject (check-qname s)})))
        collect-bindings (fn [acc b]
                           (update acc (:p b)
                                   (fn[os] (set (conj os (:o b))))))
                                                
        ]
    (value-debug
     ::query-for-po
     [::query query ::subject s]
     (reduce collect-bindings {}
             (query-endpoint client query)))))

(def query-for-o-template
  "
  Select ?o Where
  {
    {{graph-name-open|safe}}
    {{subject|safe}} {{predicate|safe}} ?o.
    {{graph-name-close|safe}}
  }
  ")

(defn query-for-o [client s p]
  "Returns #{<o>...} for `s` and `p` at endpoint of `client`
Where:
<o> is an object rendered per binding translator of <client>
<s> is a subject URI rendered per binding translator of <client>
<p> is a predicate URI rendered per binding translator of <client>
<client> conforms to ::sparql-client
"
  (let [query  (prefixed
                (selmer/render
                 query-for-o-template
                 (merge (query-template-map client)
                        {:subject (check-qname s)
                         :predicate (check-qname p)})))
        
        collect-bindings (fn [acc b]
                           (conj acc (:o b)))
                                                
        ]
    (value-debug
     ::query-for-o-return
     [::query query
      ::subject s
      ::predicate p]
     (reduce collect-bindings #{}
             (query-endpoint client query)))))

(def ask-s-p-o-template
  "ASK where
  {
    {{graph-name-open|safe}}
    {{subject|safe}} {{predicate|safe}} {{object|safe}}.
    {{graph-name-close}}
  }"
  )

(defn ask-s-p-o [client s p o]
  "Returns true if `s` `p` `o` is a triple at endpoint of `client`
Where:
<s> <p> <o> are subject, predicate and object
<client> conforms to ::sparql-client
"
  (let [query (prefixed
               (selmer/render
                ask-s-p-o-template
                (merge (query-template-map client)
                       {:subject (check-qname s)
                        :predicate (check-qname p)
                        :object (if (keyword? o)
                                  (voc/qname-for o)
                                  o)})))
        ]
    (value-debug
     ::ask-s-p-o-return
     [::query query
      ::subject s
      ::predicate p
      ::object o]
     (ask-endpoint client query))))

(defn update-endpoint [client update]
  "Side-effect: `update` is sent to `client`
Where
<update> is a sparql update
<client> is a SparqlUpdater
"
  (value-debug
   ::update-endpoint-return
   [::update update]
   (endpoint/sparql-update (:update-url client)
                           (prefixed update))))

(def add-update-template
  "
  INSERT
  {
    {{graph-name-open|safe}}
    {{triples|safe}}
    {{graph-name-close}}
  }
  WHERE
  {}
  ")


(defn quote-str [s]
  "Returns `s`, in excaped quotation marks.
Where
<s> is a string, typically to be rendered in a query or RDF source.
"
  (str "\"" s "\""))

(defn maybe-tag-xsd-type [x]
  "Returns `x`, encoded as `x`^^xsd:<xsd-type-url>, or (quote-str x) 
  if no xsd type can be found.
Where
<x> is a value to be rendered in a query or ttl source.
NOTE: typically used as the <render-literal> arg to `as-rdf`
"
  (let [x (if (and (inst? x) (not (instance? java.time.Instant x)))
            (.toInstant x)
            x)
        ]
    (if-let[xsd-uri (endpoint/xsd-type-uri x)]
      (str (quote-str x) "^^" (voc/qname-for (voc/keyword-for xsd-uri)))
      ;; else no xsd-tag found
      (quote-str x))))

(defn as-rdf [render-literal triple]
  "Returns a clause of rdf for `triple`, using `render-literal`
Where
<triple> := [<s> <p> <o>]
<render-literal> := (fn [o] ...) -> parsable rendering of <o> if <o> is 
  not a keyword (which would be treated as a URI and we'd use voc/qname-for)
"
  (let [render-element #(if (keyword? %)
                          (voc/qname-for %)
                          (render-literal %))
        ]
    (str (s/join  " " (map render-element triple))
         ".")))

(defn as-query-clause [var-fn partial-triple]
  "Returns a clause of SPARQL with variables to fill out the rest of `partial-triple`
Where
<partial-triple> := [<s>] or [<s> <p>]
"
  (case (count partial-triple)
    1 (let [[s] partial-triple]
        (assert (keyword? s))
        (selmer/render "{{s-uri|safe}} {{p-var}} {{o-var}}."
                       {:s-uri (check-qname s)
                        :p-var (var-fn "p")
                        :o-var (var-fn "o")}))
    
    2 (let [[s p] partial-triple]
        (assert (keyword? s))
        (assert (keyword? p))
        (selmer/render "{{s-uri|safe}} {{p-uri|safe}} {{o-var}}."
                       {:s-uri (check-qname s)
                        :p-uri (check-qname p)
                        :o-var (var-fn "o")}))))

(defn add-triples-query [client triples]
  (selmer/render add-update-template
                 (merge (query-template-map client)
                        {:triples (s/join "\n"
                                          (map (partial as-rdf
                                                        maybe-tag-xsd-type)
                                               triples))
                         })))

(defmethod add-to-graph [SparqlUpdater :vector-of-vectors]
  [client triples]

  (debug ::add-to-graph ::triples triples)
  (when-not (empty? triples)
    (update-endpoint client
                     (prefixed
                      (add-triples-query client triples))))
  client)

(defmethod add-to-graph [SparqlUpdater :vector]
  [client triple]
  (add-to-graph client [triple]))

(defmethod add-to-graph [SparqlUpdater :normal-form]
  [client triples]
  (add-to-graph
   client
   (reduce-spo (fn [v s p o]
                 (conj v [s p o]))
               []
               ;; use igraph.graph as an adapter
               (ont-app.igraph.graph/make-graph :contents triples))))


(def remove-update-template
  "
  DELETE
  {
    {{graph-name-open|safe}}
    {{triples|safe}}
    {{graph-name-close}}
  }
  WHERE
  {
    {{graph-name-open|safe}}
    {{where-clause|safe}}
    {{graph-name-close}}
  }
  ")

(defn remove-triples-query [client triples]
  (letfn [(var-fn [triple p-o]
            ;; returns a unique var for <triple> and either 'p' or 'o'
            (str "?_"
                 (Math/abs (hash triple))
                 "_"
                 p-o))
          (triple-clause [triple]
            (if (= (count triple) 3)
              (as-rdf maybe-tag-xsd-type triple)
              (as-query-clause (partial var-fn triple) triple)))
          (where-clause [triple]
            (if (= (count triple) 3)
              ""
              (as-query-clause (partial var-fn triple) triple)))
          ]
    (selmer/render remove-update-template
                   (merge (query-template-map client)
                          {:triples (s/join "\n"
                                            (map triple-clause
                                                 triples))
                           :where-clause (s/join "\n"
                                                 (map where-clause
                                                      triples))
                                                
                           }))))

(defmethod remove-from-graph [SparqlUpdater :vector-of-vectors]
  [client triples]
  (when-not (empty? triples)
    (update-endpoint client
                     (prefixed
                      (remove-triples-query client triples))))
  client)

(defmethod remove-from-graph [SparqlUpdater :vector]
  [client triple]
  (remove-from-graph client [triple]))

(defmethod remove-from-graph [SparqlUpdater :underspecified-triple]
  [client triple]
  (remove-from-graph client [triple]))

(defmethod remove-from-graph [SparqlUpdater :normal-form]
  [client triples]
  (remove-from-graph client
                     (reduce-spo (fn [v s p o]
                                     (conj v [s p o]))
                                   []
                                   (add (graph/make-graph)
                                        triples))))


