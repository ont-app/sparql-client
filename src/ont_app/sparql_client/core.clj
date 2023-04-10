(ns ont-app.sparql-client.core
  {
   :voc/mapsTo 'ont-app.sparql-client.ont
   :clj-kondo/config '{:linters {:deprecated-var {:level :off}}}
   }
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.spec.alpha :as spec]
      ;; 3rd party
   [selmer.parser :as parser :refer [render]]
   ;; ont-app
   [ont-app.graph-log.core :as glog]
   [ont-app.graph-log.levels :refer [trace value-trace
                                     debug value-debug
                                     info
                                     warn
                                     fatal
                                     ]]
   [ont-app.sparql-endpoint.core :as endpoint]
   [ont-app.igraph.core :as igraph :refer [IGraph
                                           IGraphMutable
                                           add
                                           add-to-graph
                                           get-p-o
                                           match-or-traverse
                                           normal-form
                                           query
                                           reduce-spo
                                           remove-from-graph
                                           subjects
                                           t-comp
                                           transitive-closure
                                           traverse
                                           traverse-or
                                           union
                                           unique
                                           ]]
   [ont-app.igraph.graph :as native-normal :refer [make-graph]]
   [ont-app.igraph-vocabulary.core :as igv :refer [mint-kwi]]
   [ont-app.rdf.core :as rdf]
   [ont-app.sparql-client.ont :as ont]
   [ont-app.vocabulary.core :as voc]
   [ont-app.vocabulary.format :as fmt :refer [
                                              decode-kw-name
                                              encode-kw-name
                                              ]]
   )
  (:import
   )
  (:gen-class))

;; (set! *warn-on-reflection* true)

(def ontology
  "A native-normal graph describing the supporting vocabulary for sparql-client"
  @ont/ontology-atom)

;;;;;;;;;;;
;; SPECS
;;;;;;;;;;;

(spec/def ::auth-key
  #{:basic-auth :digest-auth :ntlm-auth :oauth-token})

(spec/def ::basic-auth-value
  (fn b-i-v [v] (or (and (vector? v)
                   (= (count v) 2)
                   (string? (v 0))
                   (string? (v 1)))
              (and (string? v)
                   (re-matches #".+:.+" v)))))

(spec/def ::digest-auth-value
  (fn d-a-v [v] (and (= (count v) 2) (string? (v 0)) (string? (v 1)))))

(spec/def ::ntlm-auth-value
  (fn n-a-v [v] (and (vector? v)
               (= (count v) 4)
               (string? (v 0))
               (string? (v 1))
               (string? (v 2))
               (string? (v 3))
               )))

(spec/def ::oauth-token-value string?)

(defn- check-auth-value [k v]
  (spec/valid?
   (case k
     :basic-auth ::basic-auth-value
     :digest-auth ::digest-auth-value
     :ntlm-auth ::ntlm-auth-value
     :oauth-token ::oauth-token-value)
   v))

(defn- check-auth [m]
  (or (nil? m)
      (and (map? m)
           (let [[[k v]] (seq m)]
             (and (spec/valid? ::auth-key k)
                  (check-auth-value k v))))))

(spec/def ::auth check-auth)

(spec/def ::bnode-kwi rdf/bnode-kwi?)

(spec/def ::iri-string? (fn [s] (or (re-matches voc/ordinary-iri-str-re s)
                                    (re-matches voc/exceptional-iri-str-re s))))
(spec/def ::url-string? (fn [s] (re-matches voc/ordinary-iri-str-re s)))

(spec/def ::kwi? (fn [k] (and (keyword? k)
                              (spec/valid? ::iri-string? (voc/uri-for k)))))

(spec/def ::kwi-or-nil? (fn [k] (or (not k)
                                    (spec/valid? ::kwi? k))))
(spec/def ::load-context (fn [c]
                            (set/subset?
                             #{:sparql-client/graphURI
                               :sparql-client/queryURL
                               :sparql-client/updateURL
                               }
                             (into #{}
                                   (keys (c :sparql-client/IGraph))))))

;;;;;;;;;;;;;;
;; VOCABULARY
;;;;;;;;;;;;;;

(def warn-on-no-ns-metadata-for-kwi?
  "True when we should warn if there is no ns metadata found for the URI
  being translated into a KWI in `kwi-for`.
  "
  (atom false))

(def kwi-for
  "Issues a warning if no ns metadata is found for the URI"
  (partial
   voc/keyword-for
   (fn kw-for-uri-kw [uri kw]
     (when @warn-on-no-ns-metadata-for-kwi?
       (warn
        ::NoNamespaceMetadataFound
        :glog/message "No ns metadata found for {{log/uri}}"
        :log/uri uri))
     (keyword kw))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RENDERING AND READING RDF ELEMENTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- uri-translator
  "Returns `qualified-keyword` for `sparql-binding`
  - Where 
  -  `qualified-keyword` is a keyword in voc-re format
  - `sparql-binding` := {?value ...}, typically returned from a query
  - `query-url` is the URL of the query endpoint
  - `graph-uri` is the URI of the graph
  - NOTE: `query-url` and `graph-uri`are used to handle blank nodes.
  "
  [sparql-binding]
  (kwi-for (sparql-binding "value")))

(defn- bnode-translator
  "Returns `bnode-keyword` for `sparql-binding`
  - Where
    - `bnode-keyword`  is a keyword guaranteed to be unique even if merged
     with data from other endpoints and graphs
    - `sparql-binding` := {'type' 'bnode', :value ..., ...}, typically returned
      from a SPARQL query
    - `query-url` is the URL of the query endpoint
    - `graph-uri` is the URI of the graph (nil for DEFAULT)
    - `b-cache` := {`b-value` `sym`, ...}
    - `b-value` is the name bound to a blank node in the query, typically 'b0', 'b1', etc
    - `sym` is a gensym to which `b-value` is mapped when interpreting bnodes.
  "
  ([query-url graph-uri b-cache sparql-binding]
   {:post [(partial spec/valid? ::bnode-kwi)]
    }
   (keyword (str "_" (hash (str query-url graph-uri)))
            (let [v (sparql-binding "value")]
              (if-let [sym (@b-cache v)]
                sym
                ;; else no lookup
                (let [sym (name (gensym "b_"))
                      ]
                  (swap! b-cache assoc v sym)
                  sym))))))

(defn- rdf-bnode
  "Returns RDF string for `kwi` suitable for use as an element in an
  INSERT clause. "
  [kwi]
  {:pre [(spec/valid? ::bnode-kwi kwi)]
   }
  (str "_:b" (subs (namespace kwi) 1) "_" (name kwi))
  )

(defn quote-str 
  "Returns `s`, in excaped quotation marks.
- Where
  - `s` is a string, typically to be rendered in a query or RDF source.
"
  [s]
   (str "\"" s "\""))
  

(defn- datatype-translator 
  "Parses value from `sparql-binding`. If it's tagged as transit:json,
  it will read the transit, otherwise try to parse xsd values,
  defaulting to an object with metadata."
  [sparql-binding]
  (let [type-spec (kwi-for (sparql-binding "datatype"))
        ]
    (value-trace
     ::DatatypeTranslation
     [::sparql-binding sparql-binding
      ::type-spec type-spec
      ]
     (if (= type-spec :transit/json)
       (rdf/read-transit-json (sparql-binding "value"))
       ;; else
       (let [dt (endpoint/parse-xsd-value sparql-binding)]
         (if (= (type dt) org.apache.jena.datatypes.xsd.XSDDateTime)
           (try (read-string (str "#inst \"" (str dt) "\""))
                (catch Throwable _e
                  (str "Unparsable timestring: " (str dt))))
           dt))
       ))))


(defn default-binding-translators
 "Binding translators used to simplify bindings. See sparq-endpoint.core
  - Wnere
  - `endpoint-url` and `graph-uri` are used to mint unique values for bnodes.
  "
  [endpoint-url graph-uri]
  (merge endpoint/default-translators
         {:uri uri-translator
          :bnode (partial bnode-translator endpoint-url graph-uri)
          :datatype datatype-translator
          }))

(derive (type #inst "2000") :rdf-app/instant)
(derive :rdf-app/instant :rdf-app/TransitData)

(defmethod rdf/render-literal :rdf-app/instant
  [instant]
  (let [ensure-instant (fn [instant]
                         (if (not (instance? java.time.Instant instant))
                           ;; gotta reflect ....
                           (.toInstant instant)
                           instant))
        xsd-uri (endpoint/xsd-type-uri (ensure-instant instant))
        ]
    (str (quote-str (ensure-instant instant))
         "^^"
         (voc/qname-for (kwi-for xsd-uri)))))

;;;;;;;;;;;;;;;;;;
;; NEW DATATYPES
;;;;;;;;;;;;;;;;;;

(declare get-normal-form-with-bnodes)
(declare list-subjects)
(declare get-p-o-with-bnodes)
(declare get-o-with-bnodes)
(declare ask-s-p-o)
(declare query-endpoint)
(declare update-endpoint!)
(declare ask-endpoint)

(defrecord 
  ^{:doc "A read-only IGraph-compliant view on a SPARQL endpoint
- Where
  - `graph-uri` is the name of the graph (nil implies DEFAULT)
  - `query-url` is URL of a SPARQL query endpoint
  - `binding-translator` := fn[`binding`] -> `simplified`
  - `update-url` is the URL of the update endpoint (nil implies read-only)
  - `auth` := {`auth-key` `auth-value`} specifying authorization, or nil
  - `bnodes` nil, or a native-normal graph annotating blank nodes in the client.
     See `reset-annotation-graph`
  - `binding` is the value returned by a call to `query-url`
  - `simplified` := {`var` `value`, ...} a simplified binding map.
  -   See https://github.com/ont-app/sparql-endpoint#h2-simplifiers
  - `auth-key` :- #{:basic-auth, :digest-auth :ntlm-auth :oauth-token}
  - `auth-value` is a value appropriate to `auth-key`, e.g.
     [`user`, `pw`] for :basic-auth.
  - See https://github.com/dakrone/clj-http for details
"
    }
    SparqlReader [graph-uri query-url binding-translator auth bnodes]
  IGraph
  (normal-form [this] (get-normal-form-with-bnodes this))
  (subjects [this] (list-subjects this))
  (get-p-o [this s] (get-p-o-with-bnodes this s))
  (get-o [this s p] (get-o-with-bnodes this s p))
  (ask [this s p o] (ask-s-p-o this s p o))
  (query [this q] (query-endpoint this q))
  (mutability [_this] ::igraph/read-only)
  
  clojure.lang.IFn
  (invoke [g] (normal-form g))
  (invoke [g s] (get-p-o g s))
  (invoke [g s p] (match-or-traverse g s p))
  (invoke [g s p o] (match-or-traverse g s p o))
  )

(derive SparqlReader :sparql-client/IGraph) ;; dispatch in rdf i/o methods

(defrecord 
  ^{:doc "An immutable IGraph-compliant view on a SPARQL endpoint
- Where
  - `graph-uri` is the name of the graph (nil implies DEFAULT)
  - `query-url` is URL of a SPARQL query endpoint
  - `binding-translator` := fn[`binding`] -> `simplified`
  - `update-url` is the URL of the update endpoint (nil implies read-only)
  - `auth` := {`auth-key` `auth-value`} specifying authorization, or nil
  - `binding` is the value returned by a call to `query-url`
  - `simplified` := {`var` `value`, ...} a simplified binding map.
  -   See https://github.com/ont-app/sparql-endpoint#h2-simplifiers
  - `auth-key` :- #{:basic-auth, :digest-auth :ntlm-auth :oauth-token}
  - `auth-value` is a value appropriate to `auth-key`, e.g.
    [`user`, `pw`] for :basic-auth.
  - See https://github.com/dakrone/clj-http for details
"
    }
    SparqlUpdater [graph-uri query-url binding-translator update-url auth bnodes]
  IGraph
  (normal-form [this] (get-normal-form-with-bnodes this))
  (subjects [this] (list-subjects this))
  (get-p-o [this s] (get-p-o-with-bnodes this s))
  (get-o [this s p] (get-o-with-bnodes this s p))
  (ask [this s p o] (ask-s-p-o this s p o))
  (query [this q] (query-endpoint this q))
  (mutability [_this] ::igraph/mutable)

  IGraphMutable
  (add! [this to-add] (add-to-graph this to-add))
  (subtract! [this to-remove] (remove-from-graph this to-remove))
  
  clojure.lang.IFn
  (invoke [g] (normal-form g))
  (invoke [g s] (get-p-o g s))
  (invoke [g s p] (match-or-traverse g s p))
  (invoke [g s p o] (match-or-traverse g s p o))
  )

(derive SparqlUpdater :sparql-client/IGraph) ;; dispatch in rdf i/o methods

#_(spec/def ::sparql-client (fn [x] (#{SparqlReader SparqlUpdater} (type x))))
(spec/def ::sparql-client (fn [x] (isa? (type x) :sparql-client/IGraph)))

(def prefixed
  "Returns `sparql-string`, prepended with appropriate PREFIX decls."
  voc/prepend-prefix-declarations)

;; (declare default-binding-translators)

(def ^:private  ask-if-graph-exists-template
  "
  ASK WHERE
  {
    Graph {{graph-qname|safe}} {}
  }
  ")

(def ^:private create-graph-template
  "
  CREATE GRAPH {{graph-qname|safe}}
  ")

(defn make-sparql-reader
  "Returns an instance of SparqlReader.
  - Where
  - `graph-uri` is the named graph within the SPARQL endpoint. nil implies DEFAULT
  - `query-url` is the query endpoint
  - `binding-translator` := {:uri `uri-fn` :lang `lang-fn` :datatype `datatype-fn`}
     default is sparql-endpoint.core/default-translators)
  - `auth` (optional)  is the authorization token needed to perform updates
  - `uri-fn` := (fn [binding]...) -> URI representation. 
    Default is just the URI string
  - `lang-fn` := (fn [binding] ...) -> parsed language tag. 
     Default is just the language string with no
  - `datatype-fn` := (fn [binding] -` Parsed datatype
  - `binding` := {:value ... 
                 :type ... 
                  &maybe 
                 :xml:lang ... 
                 :datatype ...}
     This occurs in bindings of the form {`var` `binding`, ...} returned by a 
      SPARQL query.
  - See also sparql-endpoint.core.
"
  [& {:keys
      [graph-uri query-url binding-translator auth]}]
  {:pre [(spec/valid? ::kwi-or-nil? graph-uri)
         (spec/valid? ::url-string? query-url)
         (spec/valid? ::auth auth)
         ]
   :post [(spec/valid? ::sparql-client %)]
   }
  (let [client (->SparqlReader
                graph-uri
                query-url
                (or binding-translator
                    (default-binding-translators query-url graph-uri))
                auth
                nil ;; bnodes only added with reset-annotation-graph
                )]
    (if (:graph-uri client)
      
      (let [graph-qname (voc/qname-for (:graph-uri client))]
        (when-not (ask-endpoint client
                              (prefixed
                               (render
                                ask-if-graph-exists-template
                                {:graph-qname graph-qname})))
          (throw (ex-info (str "Graph " graph-qname " does not exist")
                          {:type ::NoSuchGraph
                           :graph graph-qname})))
        ;; else the graph exists
        client)
        ;;else we're using the default graph
      client)))

(defn make-sparql-updater 
  "Returns an instance of SparqlUpdater.
  - Where
  - `graph-uri` is the named graph within the SPARQL endpoint. nil implies DEFAULT
  - `query-url` is the query endpoint
  - `binding-translator` := {:uri `uri-fn` :lang `lang-fn` :datatype `datatype-fn`}
    default is sparql-endpoint.core/default-translators)
  - `update-url` (optional) is update endpoint (or nil if read-only)
  - `auth` (optional)  is the authorization token needed to perform updates
  - `uri-fn` := (fn [binding]...) -> URI representation. 
    Default is just the URI string
  - `lang-fn` := fn [binding] -> parsed language tag. 
     Default is just the language string
  - `datatype-fn` := fn [binding] -> Parsed datatype
  - `binding` := {:value ... 
                :type ... 
                &maybe 
                :xml:lang ... 
                :datatype ...}
    This occurs in bindings of the form {`var` `binding`, ...} returned by a 
    SPARQL query.
  - See also sparql-endpoint.core.
"
  [& {:keys
      [graph-uri query-url binding-translator
       update-url auth]
      :or {binding-translator (default-binding-translators query-url graph-uri)
           }
      :as args}]
  {:pre [(spec/valid? ::kwi-or-nil? graph-uri)
         (spec/valid? ::url-string? query-url)
         (spec/valid? ::url-string? update-url)
         (spec/valid? ::auth auth)
         ]
   :post [(spec/valid? ::sparql-client %)]
   }
  (let [client (->SparqlUpdater
                graph-uri
                query-url
                binding-translator
                update-url
                auth
                nil ;; bnodes only added with reset-annotation-graph
                )]
    (debug ::starting-make-sparql-updater ::graph-uri (:graph-uri client))
    (value-debug
     ::finishing-make-sparql-updater
     [::args args
      ::graph-uri (:graph-uri client)
      ]
     (if (:graph-uri client)
       (let [graph-qname (voc/qname-for (:graph-uri client))]
         (when-not (ask-endpoint client
                               (prefixed
                                (render
                                 ask-if-graph-exists-template
                                 {:graph-qname graph-qname})))
           (if (:update-url client)
             (do 
               (update-endpoint! client
                                (prefixed
                                 (render create-graph-template
                                         {:graph-qname graph-qname})))
               client)
             ;; else there is no update-url
             (throw (Exception. (str "Graph " graph-qname " does not exist, and there is no update URL.")))))
         ;; else the graph exists
         client)
       ;;else we're using the default graph
       client))))

(defn- query-endpoint 
  "Returns [`simplified-binding` ...] for `query` posed to `client`
- Where
  - `simpified-binding` := {`key` `value` ...},
     the output of the binding translator of `client`
  - `query` is a SPARQL SELECT query
  - `client` is a SparqlReader or SparqlUpdater
"
  [client query]
  (let [dbg-query (debug ::StartingQueryEndpoint
                         :log/query query
                         :log/query-url (:query-url client))
        ]
  (value-debug
   ::QueryEndpointResult
   [:log/resultOf dbg-query]
   (map (partial endpoint/simplify (assoc (:binding-translator client)
                                          :bnode
                                          (partial bnode-translator
                                                   (:query-url client)
                                                   (:graph-uri client)
                                                   (atom {}) ;; b-cache
                                                   )))
        (endpoint/sparql-select (:query-url client)
                                query
                                (or (:auth client) {}) ;; http-req
                                )))))

(defn- ask-endpoint
  "Returns boolean value of `query` posed to `client`
- Where
  - `query` is a SPARQL ASK query
  - `client` conforms to ::sparql-client spec
"
  [client query]
  (let [starting (debug ::starting-ask-endpoint
                        :log/queryUrl (:query-url client)
                        :log/query query)
        ]
    (value-debug
     ::ask-endpoint-return
     [:log/resultOf starting]
     (endpoint/sparql-ask (:query-url client)
                          query
                          (or (:auth client) {}) ;; http-req
                          ))))
(defn- query-template-map
  "Returns {`k` `v`, ...} appropriate for `client`
- Where
  - `client` is a sparql-client
  - `k` and `v` are selmer template parameters which may appear in some query, e.g.
    named graph open/close clauses
  - `k` :~ #{:graph-name-open :graph-name-close}
"
  [client]
  {:graph-name-open (if-let [graph-uri (:graph-uri client)]
                      (str "GRAPH <" (voc/iri-for graph-uri) "> {")
                      "")
   :graph-name-close (if-let [_graph-uri (:graph-uri client)]
                      (str "}")
                      "")
   })                          

(def ^:private normal-form-query-template
  "
  Select ?s ?p ?o
  Where
  {
    {{graph-name-open|safe}}
    ?s ?p ?o
    {{graph-name-close}}
  }
  ")

(defn- query-for-normal-form
  "Returns a normal-form expression of the contents of `client`. Bnodes will rendered naively."
  [client]
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
    (let [query (render normal-form-query-template
                               (query-template-map client))
          ]
      (reduce collect-binding {}
              (query-endpoint client query)))))

(def ^:private count-subjects-query-template
  "A SPARQL query to count the number of subjects in a client graph"
  "
  Select (Count (?s) as ?sCount) 
  Where
  {
    {{graph-name-open|safe}}
    ?s ?p ?o
    {{graph-name-close}}
  }
  "
  )

(defn count-subjects
  "Returns the number of subjects at endpoint of  `client`
  - Where
    - `client` conforms to  `::sparql-client` spec
"
  [client]
  {:pre [(spec/valid? ::sparql-client client)]
   }
  (let [query (render count-subjects-query-template
                      (query-template-map client))
        ]
    (:sCount (unique (query-endpoint client query)))))


(def ^:private subjects-query-template
  "A query for subjects in the client graph."
  "
  Select Distinct ?s Where
  {
    {{graph-name-open|safe}}
    ?s ?p ?o.
    {{graph-name-close|safe}}
  }
  ")

(defn- query-for-subjects 
  "Returns [`subject` ...] at endpoint of `client`
- Where
  - `subject` is the uri of a subject from `client`, 
  rendered per the binding translator of `client`
  - `client` conforms to ::sparql-client spec
"
  [client]
  {:pre [(spec/valid? ::sparql-client client)]
   }
  (let [query (render subjects-query-template
                             (query-template-map client))
        ]
    (map :s
         (query-endpoint client query))))

(def ^:private query-for-p-o-template
  "
  Select ?p ?o Where
  {
    {{graph-name-open|safe}}
    {{subject|safe}} ?p ?o.
    {{graph-name-close|safe}}
  }
  ")

(defn- check-ns-metadata 
  "Logs a warning when `kwi` is in a namespace with no metadata."
  [kwi]
  (try
    (when-let  [ns' (namespace kwi)]
      (let [n (symbol ns')
            ]
        (when-let [the-ns (find-ns n)]
          (when (not (meta the-ns))
            (warn ::NoMetaDataInNS
                  :glog/message "The namespace for {{kwi}} is in a namespace with no associated metadata."
                  :kwi kwi))))
      kwi)
    (catch Throwable _e
      (fatal ::error-checking-ns-metadata
             :glog/message "Error while checking ns metadata of {{kwi}}"
             :kwi kwi)
      kwi)))


(defn- check-qname 
  "Traps the keyword assertion error in voc and throws a more meaningful error about blank nodes not being supported as first-class identifiers."
  [uri-spec]
  (if (rdf/bnode-kwi? uri-spec)
    uri-spec
    ;;else not a blank node
    (try
      (let [qname (voc/qname-for (check-ns-metadata uri-spec))
            has-slash #".*\/.*"
            ]
        (if (re-matches has-slash qname)
          ;; SPARQL will not parse a qname with a slash in it.
          (str "<" (voc/iri-for uri-spec) ">")
          qname))
      (catch java.lang.AssertionError e
        (if (= (str e)
               "java.lang.AssertionError: Assert failed: (keyword? kw)")
          (throw (ex-info (str "The URI spec " uri-spec " is not a keyword.\nCould it be a blank node?\nIf so, blank nodes cannot be treated as first-class identifiers in SPARQL. Use a dedicated query that traverses the blank node instead.")
                          (merge (ex-data e)
                                 {:type ::Non-Keyword-URI-spec
                                  ::uri-spec uri-spec
                                  })))
                             
          ;; else it's some other message
          (throw e))))))

(defn- query-for-p-o 
  "Returns {`p` #{`o`...}...} for `s` at endpoint of `client`
- Where
  - `client` conforms to ::sparql-client
  - `s` is a subject uri keyword. ~ voc/voc-re
  - `p` is a predicate URI rendered per binding translator of `client`
  - `o` is an object value, rendered per the binding translator of `client`
"
  [client s]
  {:pre [(spec/valid? ::sparql-client client)]
   }
    (let [query  (prefixed
                (render query-for-p-o-template
                               (merge (query-template-map client)
                                      {:subject (if (rdf/bnode-kwi? s)
                                                  (decode-kw-name (name s))
                                                  (check-qname s))})))
        collect-bindings (fn q-for-p-o-c-b [acc b]
                           (update acc (:p b)
                                   (fn[os] (set (conj os (:o b))))))
                                                
        ]
    (value-debug
     ::query-for-po
     [::query query ::subject s]
     (reduce collect-bindings {}
             (query-endpoint client query)))))

(def ^:private query-for-o-template
  "
  Select ?o Where
  {
    {{graph-name-open|safe}}
    {{subject|safe}} {{predicate|safe}} ?o.
    {{graph-name-close|safe}}
  }
  ")

(defn- query-for-o 
  "Returns #{`o`...} for `s` and `p` at endpoint of `client`
  - Where:
  - `client` conforms to ::sparql-client
  - `s` is a subject URI rendered per binding translator of `client`
  - `p` is a predicate URI rendered per binding translator of `client`
  - `o` is an object rendered per binding translator of `client`
  "
  [client s p]
  {:pre [(spec/valid? ::sparql-client client)]
   }
  (let [query  (prefixed
                (render
                 query-for-o-template
                 (merge (query-template-map client)
                        {:subject (if (rdf/bnode-kwi? s)
                                    (decode-kw-name (name s))
                                    (check-qname s))
                         :predicate (check-qname p)})))
        
        collect-bindings (fn q-f-o-c-b [acc b]
                           (conj acc (:o b)))
        
        ]
    (value-debug
     ::query-for-o-return
     [::query query
      ::subject s
      ::predicate p]
     (reduce collect-bindings #{}
             (query-endpoint client query)))))

(defn property-path
  "Returns a traversal function [g c a q] -` [c a' q'] equivalent to `path`
  - Where
    - `path` is a SPARQL property path, e.g. 'myns:prop1/myns:prop2'
    - `g` is an sparql update client
    - `c` is a traversal context
    - `a` is an accumulator (typically a set)
    - `a'` has been conj'ed with the `?o` bindings of `query`
    - `q` is an input queue to the traversal
    - `q'` is the rest of `q`
    - `query` is `query-for-o template`, with (first `q`) as subject and `path`
       as the predicate. This binds a single var `?o`.
  "
  [path]
  (fn prop-path [g c a q]
    (let [query
          (prefixed
           (render
            query-for-o-template
            (merge (query-template-map g)
                   {:subject (check-qname (first q))
                    :predicate path})))
          query-trace (trace ::PropertyPathQuery
                              ::path path
                              ::query query)
          ]
      [c, ;; unchanged context
       ;; accumulate....
       (let [result (value-trace
                     ::PropertyPathQueryResult
                     [::resultOf query-trace]
                     (query-endpoint g query))
             ]
         (reduce conj a (map :o result))),
       ;; queue....
       (rest q)
       ])))

(def ^:private ask-s-p-o-template
  "ASK where
  {
    {{graph-name-open|safe}}
    {{subject|safe}} {{predicate|safe}} {{object|safe}}.
    {{graph-name-close}}
  }"
  )

(defn- ask-s-p-o 
  "Returns true if `s` `p` `o` is a triple at endpoint of `client`
- Where:
  - `client` conforms to ::sparql-client
  - `s` `p` `o` are subject, predicate and object
"
  [client s p o]
  {:pre [(spec/valid? ::sparql-client client)]
   }
  (let [query (prefixed
               (render
                ask-s-p-o-template
                (merge (query-template-map client)
                       {:subject (check-qname s)
                        :predicate (check-qname p)
                        :object (if (keyword? o)
                                  (if (rdf/bnode-kwi? o)
                                    (decode-kw-name (name o))
                                    ;;else
                                    (voc/qname-for o))
                                  (rdf/render-literal o))})))
        starting (debug ::Starting_ask-s-p-o
                        ::query query
                        ::subject s
                        ::predicate p
                        ::object o)
        ]
    (value-debug
     ::ask-s-p-o-return
     [::resultOf starting]
     (ask-endpoint client query))))

(defn update-endpoint!
  "Side-effect: `update` is sent to `client`
- Where
  - `update` is a sparql update
  - `client` is a SparqlUpdater
"
  [client update]
  {:pre [(spec/valid? ::sparql-client client)]
   }
  (let [start-state (debug ::StartingUpdateEndpoint
                           ::client client
                           ::update (prefixed update))
        ]
  (value-trace
   ::SparqlUpdateResult
   [::resultOf start-state]
   (endpoint/sparql-update (:update-url client)
                           (prefixed update)
                           (or (:auth client) {}) ;; http-req
                           ))
  (value-debug
   ::UpdateEndpointResult
   [::resultOf start-state]
   client)
  ))

(def ^:private add-update-template
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

(defn- as-rdf 
  "Returns a clause of rdf for `igraph-vector`, using `render-literal`
  - Where
    - `igraph-vector` := [`s` `p` `o` & maybe `p` `o`, ...]
    - `render-literal` := (fn [o] ...) -> parsable rendering of `o` if `o` is 
       not a KWI (which would be treated as a URI and we'd use voc/qname-for)
       This is optional. Default is `render-standard-literal`
"
   [igraph-vector]
   {:pre [(spec/valid? ::igraph/vector igraph-vector)]
    }
   (let [render-element (fn as-rdf-r-e [elt]
                          (if (keyword? elt)
                            (if (rdf/bnode-kwi? elt)
                              (rdf-bnode elt)
                              ;; else not a bnode...
                              (voc/qname-for elt))
                            ;; else not a keyword...
                            (rdf/render-literal elt)))
         render-p-o (fn as-rdf-r-p-o [p-o]
                      (str/join  " " (map render-element p-o)))
         ]
     (str (render-element (first igraph-vector))
          "\n"
          (str/join ";\n" (map render-p-o (partition 2 (rest igraph-vector))))
          ".")))

(defn- as-query-clause 
  "Returns a clause of SPARQL with variables to fill out the rest of `partial-triple`
- Where
  - `partial-triple` := [`s`] or [`s` `p`]
"
  [var-fn partial-triple]
  (case (count partial-triple)
    1 (let [[s] partial-triple]
        (assert (keyword? s))
        (render "{{s-uri|safe}} {{p-var}} {{o-var}}."
                {:s-uri (check-qname s)
                 :p-var (var-fn "p")
                 :o-var (var-fn "o")}))
    
    2 (let [[s p] partial-triple]
        (assert (keyword? s))
        (assert (keyword? p))
        (render "{{s-uri|safe}} {{p-uri|safe}} {{o-var}}."
                       {:s-uri (check-qname s)
                        :p-uri (check-qname p)
                        :o-var (var-fn "o")}))))

(defn- add-triples-query [client triples]
  {:pre [(spec/valid? ::igraph/vector-of-vectors triples)]
   }
  (render add-update-template
                 (merge (query-template-map client)
                        {:triples (str/join "\n"
                                          (map as-rdf 
                                               triples))
                         })))

(defmethod add-to-graph [SparqlUpdater :vector-of-vectors]
  [client triples]

  (debug ::add-to-graph ::triples triples)
  (when-not (empty? triples)
    (update-endpoint! client
                     (prefixed
                      (add-triples-query client triples))))
  client)

(defmethod add-to-graph [SparqlUpdater :vector]
  [client triple]
  (add-to-graph client ^{::igraph/triples-format :vector-of-vectors} [triple]))

(defmethod add-to-graph [SparqlUpdater :normal-form]
  [client triples]
  (add-to-graph
   client
   ^{::igraph/triples-format :vector-of-vectors}
   (reduce-spo (fn a-t-g [v s p o]
                 (conj v [s p o]))
               []
               ;; use igraph.graph as an adapter
               (ont-app.igraph.graph/make-graph :contents triples))))


(def ^:private remove-update-template
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

(defn- remove-triples-query
  "Returns a SPARQL UPDATE query to remove `triples` from the graph of `client`
- where
  - `client` is a sparql client graph
  - `triples` := [`triple`, ...]
  - `triple` := [s p o]
"
  [client triples]
  (assert (seq  triples))
  (debug ::starting-remove-triples-query
         ::client client
         ::triples triples)
  (letfn [(var-fn [triple p-o]
            ;; returns a unique var for <triple> and either 'p' or 'o'
            (str "?_"
                 (Math/abs ^java.lang.Integer (hash triple))
                 "_"
                 p-o))
          (triple-clause [triple]
            (if (= (count triple) 3)
              (as-rdf triple)
              (as-query-clause (partial var-fn triple) triple)))
          (where-clause [triple]
            (if (= (count triple) 3)
              ""
              (as-query-clause (partial var-fn triple) triple)))
          ]
    (value-debug
     ::result-of-remove-triples-query
     [::triples triples]
     (render remove-update-template
                    (merge (query-template-map client)
                           {:triples (str/join "\n"
                                             (map triple-clause
                                                  triples))
                            :where-clause (str/join "\n"
                                                  (map where-clause
                                                       triples))
                            
                            })))))

(defmethod remove-from-graph [SparqlUpdater :vector-of-vectors]
  [client vectors]
  (debug ::starting-remove-from-graph
         ::client client
         ::vectors vectors)
  (when-not (empty? vectors)
    (let [collect-triple (fn [s vacc [p o]]
                           ;; adds s p o vector to `vacc`
                           (conj vacc [s p o]))
          ensure-triple (fn [vacc v]
                          (value-trace
                           ::ensure-triple-result
                          ;; may break out multiple triples from v count > 3
                           (if (> (count v) 3)
                             (do
                               (assert (odd? (count v)))
                               (reduce (partial collect-triple (first v))
                                       vacc
                                       (partition 2 (rest v))))
                             ;; else count not > 3
                             (do
                               (assert (<= 1 (count v) 3))
                               (conj vacc v)))))
          ]
      (update-endpoint! client
                        (prefixed
                         (remove-triples-query client
                                               (reduce ensure-triple [] vectors))))))
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
                     (reduce-spo (fn r-f-g-r-spo [v s p o]
                                     (conj v [s p o]))
                                   []
                                   (add (native-normal/make-graph)
                                        triples))))

(defn drop-client!
  "Side-effect: the named graph associated with `client` is dropped from its endpoint"
  ([g]
   (debug ::StartingDropClient
          :log/graph-uri (:graph-uri g)
          :glog/message "DROPPING GRAPH WITH URI {{log/graph-uri}}"
          )
   (update-endpoint!
    g
    (value-debug
     ::drop-client-result
     [:glog/message "Update: {{glog/value}}"]
     (prefixed 
      (str "DROP GRAPH " (voc/qname-for (:graph-uri g))))))))

;;;;;;;;;
;; I/O
;;;;;;;;;

(def standard-write-context
  "Standard 'context' argument to `rdf/write-rdf` for methods dispatched on  [`sparql-client/IGraph` * *]"
  (-> @rdf/default-context
      (add [
            [#'rdf/write-rdf
             :rdf-app/hasGraphDispatch :sparql-client/IGraph
             ]
            ])))

(def standard-read-context
  "The standard context argument to `rdf/read-rdf` dispatched on [`SparqlUpdater` *] and basis for `create-load-context`"
  (-> standard-write-context
      (add [[#'rdf/load-rdf
             :rdf-app/hasGraphDispatch SparqlUpdater
             ]
            [#'rdf/read-rdf
             :rdf-app/hasGraphDispatch SparqlUpdater
             ]
            ])))

(defn create-load-context
  "Returns `io-context` for `query-endpoint` `update-endpoint` `graph-uri`
  - Where
    - `query-url` is the URL of the query endpoint of the client
    - `update-url` is the URL of the update endpoint of the client
    - `graph-uri` is a KWI for the URI of the nameed graph associated with the file to
       be loaded by `rdf/load-rdf`
    - `io-context` is a native-normal graph serving as the the first, 'context' argument
       to `rdf/load-rdf` dispatched on [`SparqlUpdater` *]
  "
  [query-url update-url graph-uri]
  {:pre [(spec/valid? ::url-string? query-url)
         (spec/valid? ::url-string?  update-url)
         (spec/valid? ::kwi? graph-uri)
         ]
   :post [(spec/valid? ::load-context %)]
   }
  (add standard-read-context
       [:sparql-client/IGraph
        :sparql-client/graphURI graph-uri
        :sparql-client/queryURL query-url
        :sparql-client/updateURL update-url
        ]))

(def ^:private write-rdf-construct-query-template "
CONSTRUCT {?s ?p ?o}
WHERE
{
  Graph <{{graph|safe}}>
  {
    ?s ?p ?o.
  }
}
  ")

#_(derive SparqlUpdater :rdf-app/IGraph) ;; bring in rdf module's default methods.
(derive :sparql-client/IGraph :rdf-app/IGraph) ;; bring in rdf module's default methods.

(defn- derivable-media-types
  "Returns {child parent, ...} for media types
  - where
    - `child` should be declared to derive from `parent`, being subsumed by
      `:dct/MediaTypeOrExtent`
  - note
    - these derivations would inform method dispatch for rdf/write-rdf methods.
  "
  [ont]
  (let [subsumedBy (traverse-or :rdf/type
                                       :rdfs/subClassOf)
        subsumedBy* (transitive-closure subsumedBy)

        media-types (->> (query ont
                                [[:?media-type subsumedBy* :dct/MediaTypeOrExtent]])
                         (map :?media-type)
                         (set)
                         )
        get-derivable (fn get-dirivable [macc media-type]
                        ;; macc := {child parent, ...}
                        (let [parent (unique
                                      (filter media-types (ont media-type subsumedBy)))

                              ]
                          (assoc macc media-type parent)))

        ]
    (reduce get-derivable {} media-types
            )))

;; Declare derivations for media types for write method dispatch...
(doseq [[child parent] (derivable-media-types rdf/ontology)]
  (when parent
    (derive child parent)))

(defmethod rdf/write-rdf [:sparql-client/IGraph :rdf-app/LocalFile :dct/MediaTypeOrExtent]
  [_context g target fmt]
  (let [response (endpoint/sparql-construct
                  (:query-url g)
                  (render write-rdf-construct-query-template
                          {:graph (voc/uri-for (:graph-uri g))})
                  {:accept (unique (rdf/ontology fmt :formats/media_type))})
        ]
    (spit target response)
    target))

(defmethod rdf/load-rdf [SparqlUpdater :rdf-app/LocalFile]
  [context to-load]
  (trace ::starting-load-rdf-for-sparql-updater-local-file
         ::context context
         ::to-load to-load)
  (let [graph-uri (or (unique (context :sparql-client/IGraph :sparql-client/graphURI))
                      (kwi-for (str "file:" to-load)))
        query-url (unique (context :sparql-client/IGraph :sparql-client/queryURL))
        update-url (unique (context :sparql-client/IGraph :sparql-client/updateURL))
        ]
    (if (and graph-uri query-url update-url)
      (let [updater (make-sparql-updater
                     :graph-uri graph-uri
                     :query-url query-url
                     :update-url update-url
                     )
            ]
        (when (endpoint/sparql-ask query-url
                                   (value-trace
                                    ::load-rdf-graph-ask-query
                                    (prefixed
                                    (render
                                     "ASK WHERE {graph {{uri|safe}} {}}"
                                     {:uri (voc/qname-for graph-uri)}))))
          (throw (ex-info (format "Graph %s already exists" graph-uri)
                          {:type ::graph-already-exists
                           ::graph-uri graph-uri
                           })))

        (endpoint/sparql-update (:update-url updater)
                                (render "LOAD <file:{{to-load}}> INTO GRAPH <{{graph-uri}}>"
                                        {:to-load to-load
                                         :graph-uri (voc/uri-for graph-uri)
                                         }))
        updater)
      ;; else updater was not properly specified in the context
      (cond
        (not graph-uri)
        (throw (ex-info "No graph URI provided in context"
                        {:type ::no-graph-uri-provided-in-context
                         ::context context
                         ::to-load to-load
                         }))
        (not query-url)
        (throw (ex-info "No query URL provided in context"
                        {:type ::no-query-url-provided-in-context
                         ::context context
                         ::to-load to-load
                         }))
        (not query-url)
        (throw (ex-info "No update URL provided in context"
                        {:type ::No-update-url-provided-in-context
                         ::context context
                         ::to-load to-load
                         }))
        ))))


(defmethod rdf/read-rdf [SparqlUpdater :rdf-app/LocalFile]
  [_context updater to-load]
  (trace ::starting-read-rdf
         ::updater updater
         ::to-load to-load)
  (endpoint/sparql-update (:update-url updater)
                          (render "LOAD <file:{{to-load}}> INTO GRAPH <{{graph-uri}}>"
                                  {:to-load to-load
                                   :graph-uri (voc/uri-for (:graph-uri updater))
                                   }))
  updater)

;;;;;;;;;;;;;;;;;;;;;;;;
;; BNODE ROUND-TRIPPING
;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod mint-kwi :bnode/BnodeDescriptionClause
  [head-kwi property object]
  (let [ns' (namespace head-kwi)
        name' (name head-kwi)
        stringify (fn stringify [x]
                    (cond (string? x) x
                          (keyword? x) (if (rdf/bnode-kwi? x)
                                         (name x)
                                         (voc/qname-for x))
                          (sequential? x) (hash x)
                          :else (str x)))
        kwi (keyword ns' (encode-kw-name
                          (str name'
                               ".p=" (stringify property)
                               ".o=" (stringify object))))
        ]
    kwi))

(declare render-element)

(defmethod mint-kwi :bnode/BnodeClass
  [_head _ann element bnode-class-description]
  (keyword (namespace element)
           (encode-kw-name bnode-class-description))
  )

(declare render-clause)
(defmethod mint-kwi :bnode/BnodeClauseClass
  [head clause-type property counterpart]
  {:pre []
   }
  (let [stringify (fn stringify [x]
                    (cond (string? x) x
                          (keyword? x) (if (rdf/bnode-kwi? x)
                                         (name x)
                                         (voc/qname-for x))
                          (sequential? x) (hash x)
                          :else (str x)))
        ]
  (keyword (namespace head)
           (encode-kw-name (str
                            (name clause-type)
                            ".p=" (stringify property)
                            ".corr=" (stringify counterpart))))))


(defn decode-bnode-kwi-name
  "Returns a string description of `bkwi` suitable for embedding in SPARQL
  - where
    - `bkwi` is a keyword encoded by (`mint-kwi` `bnode-class`)
    - `bnode` is a bnode annotated and rendered with `render-element`
  "
  [bkwi]
  (decode-kw-name (name bkwi)))

(declare render-clause)

(defn- render-element-dispatch
  "Dispatches `render-element` on the `:bnode/render-type` property for `element`"
  [ann element]
   (or (unique (ann element :bnode/render-type))
       (if (rdf/bnode-kwi? element)
         :bnode/BnodeClass
         :bnode/Simple)))

(defmulti ^:private render-element
  "[ann element] -> SPARQL description of `element`
  - Dispatched on `rendeer-element` [ann element] -> #{:bnode/BnodeClass :bnode/Simple}
  - Where
    - `ann` is a native-normal graph describing the context of `element` in the
       original RDF graph.
    - `element` is a node in the graph
  - NOTE: if `element` is a :bnode/Bnode, it will be rendered in [nested [brackets]] in a way
    That can be embedded in a SPARQL query.
  "
  render-element-dispatch)

(defmethod render-element :default
  [ann element]
  (throw (ex-info "invalid render-element-dispatch"
                  {:type :bnode/invalid-render-element-dispatch
                   ::ann ann
                   ::element element
                   })))


(defmethod render-element :bnode/Simple
  [ann element]
  (when (rdf/bnode-kwi? element)
    (throw (ex-info "not expecting bnode"
                    {:type :bnode/not-expecting-bnode
                     ::ann ann
                     ::element element
            })))
  (if (keyword? element)
    (voc/qname-for element)
    element))

(def ^:private bnode-rendered-as
  "A traversal function rendered-locally-as|node-class?/rendered-as"
  (t-comp [:bnode/bnode-class
           :bnode/rendered-as]))

(defmethod render-element :bnode/BnodeClass
  [ann element]
  (value-trace
   ::render-element-bnode-class-result
   [::ann ann ::element element]
   (let [result (unique (or (ann element :bnode/rendered-locally-as)
                            (ann element bnode-rendered-as)))
         ]
     (when (not result)
       (let [entry (fatal ::no-rendering-for-element
                          ::ann ann
                          ::element element)
             ]
         (throw (ex-info (str "No rendering for " element)
                         (or (glog/show entry)
                             {:type ::no-rendering-for-element
                              ::element element
                              })))))
     result)))


(defn- render-clause-dispatch
  [ann clause-id _element _correspondent]
  (let [result (unique (ann clause-id :bnode/render-type))]
    result))

(defmulti ^:private render-clause
  "[ann clause-id] -> SPARQL description of `clause`
    - Where
    - `ann` is a native-normal graph describing the context of `clause` in the
       original RDF graph.
    - `clause` is kwi naming a link between two nodes in the original RDF graph
  "
  render-clause-dispatch)

(defmethod render-clause :default
  [ann clause-class _element _correspondent]
  (throw (ex-info "Invalid render-clause-dispatch"
                  {:type ::invalid-render-clause-dispatch
                   ::ann ann
                   ::clause-id clause-class
                   })))

(defmethod render-clause :bnode/SubordinateClause
  [ann clause-class _element object]
  (str (voc/qname-for (unique (ann clause-class :bnode/property)))
       " "
       (render-element ann object)))

(defmethod render-clause :bnode/ContextClause
  [ann clause-class _element referring]
  (str "^" ;; inverse property path operator
       (voc/qname-for (unique (ann clause-class :bnode/property)))
       " "
       (render-element ann referring)))

(defn- collect-rendered-as
  "Returns [`c` `ann`, `element-stack`'] adding (`ann` `target-element` :bnode/bnode-class `bnode-class`, with `:bnode/rendered-as` as appropriate.
  - A traversal function
  - Where
    - `ann` is the annotation graph for some sparql-client
    - `c` is the traversal context which annotates the traversal state
    - `element-stack` := (list `supporting-element`, ..., `target-element`) ;;
      - note that this is a seq not a vector, to drive a depth-first search.
    - `supporting-element` is a bnode in the client model of `ann` linked through a set of clauses to the `target-element`
    - `target-element` is the bnode in client model from which `bnode-class` will be
       derived
    - `bnode-class` names the subject in `ann` describing `target-element`
       s.t. `bnode-class :bnode/rendered-as `rendering`
    - `rendering` :~ [`clause`, ....] describing the BnodeClass of `element`
  "
  [_ c ann element-stack]
  (trace ::starting-collect-rendered-as
         ::c c
         ::ann ann
         ::element-stack element-stack
         ::element (first element-stack)
         )
  (assert ann)
  (assert (seq? element-stack)) ;; depth first search under conj
  (let [element (first element-stack)
        context-notes (::notes c)
        in-q? (into #{} element-stack)
        ]
    (if (or (seq (ann element :bnode/rendered-locally-as))
            ;; (seq (ann element :bnode/bnode-class))
            (ann element :bnode/render-class :bnode/Simple))
      ;; our work here is done, just pop the q
      [c ann (rest element-stack)]
      ;; else there's no rendered-locally-as...
      (let [correspondent-of (fn correspondent-of [clause-class]
                               (unique (ann clause-class
                                            (traverse-or
                                             :bnode/object
                                             :bnode/referring))))
            clause-classes (filter (fn [clause-class]
                                     ;; filter out redundant clauses...
                                     (not (in-q?
                                           (correspondent-of clause-class))))
                                   (ann element :rdfs/subClassOf))
            pending-clause-classes (filter
                                    (fn [clause-class]
                                      (let [corr (correspondent-of clause-class)
                                            ]
                                        ;; don't need to calculate simple renderings
                                        ;; don't want to re-calculate what's already
                                        ;; been calculated
                                        (and (not (context-notes corr
                                                                 :bnode/render-type
                                                                 :bnode/Simple))
                                             (not
                                              (seq
                                               (context-notes corr
                                                              :bnode/rendered-locally-as
                                                              ))))))
                                    clause-classes)

            ]
        (if (seq  pending-clause-classes)
          ;; we can't render this element until the correpondents are rendered ...
          (let [corrs (map correspondent-of pending-clause-classes)
                ]
          (value-trace
           ::push
           [c 
           ,
           ann
           ,
           (reduce conj ;; prepend the stack
                   element-stack
                   corrs)
            ]))
          ;; else there are no more pending clause classes  and we're ready to render...
          (let [render-class-clause (fn render-class-clause [clause-class]
                                      (let [corr (correspondent-of clause-class)
                                            ]
                                        (render-clause context-notes
                                                       clause-class
                                                       element
                                                       corr
                                                       )
                                        ))
                ;; Ensure canonical order ....
                presentation< (fn pres< [this-class that-class]
                                (let [this-desc (unique
                                                 (ann this-class :bnode/render-type))
                                      that-desc (unique
                                                 (ann that-class :bnode/render-type))
                                      ]
                                  ;; subordinates precied context
                                  (if (not (= this-desc that-desc))
                                    (if (= this-desc :bnode/SubordinateClause)
                                      -1
                                      ;; else it's context...
                                      1)
                                    ;; else they're the same render type
                                    (compare (str this-class)
                                             (str that-class)))))
                rendering   (str "["
                                 (str/join
                                  "; "
                                  (filter (complement empty?)
                                          (map render-class-clause
                                               (sort presentation< clause-classes))))
                                 "]")
                ]
            (if (= (count element-stack) 1)
              ;; this is the target element, update the main annotation graph
              (let [bnode-class (mint-kwi :bnode/BnodeClass ann element rendering)
                    ]
                (value-trace
                 ::collect-rendered-as-result
                 [::ann ann ::element element ::bnode-class bnode-class ::rendering rendering]
                 [(update c :skip? (fn [skip?] (conj skip? element)))
                  ,
                  (add ann [[element :bnode/bnode-class bnode-class]
                            [bnode-class :bnode/rendered-as rendering]
                            ])
                  ,
                  (rest element-stack)
                  ]))
              ;; else we're still rendering supporting elements
              [(update c ::notes
                       (fn [notes]
                         (add notes
                              [element :bnode/rendered-locally-as rendering])))
               ,
               ann
               ,
               (rest element-stack)
               ]
              )))))))

(def ^:private query-for-all-bnodes
  "Queries `graph-uri` for all of its bnodes
- Where
  - `graph-uri` names the graph-uri for some sparql client.
 "
 
  "
SELECT *
FROM <{{graph-uri|safe}}>
Where
{
  {
    ?bnode ?p ?o.
    FILTER isBlank(?bnode)
  }
  UNION
  {
    ?s ?p ?bnode.
    FILTER isBlank(?bnode)
  }
}
  "
  )

(defn- collect-bnode-query-binding
  "Returns `gacc`' for `bnode-query` binding `b`
  - A reducing function
  - Where
    - `gacc` is a native-normal graph accumulating triples to describe a model of blank
      nodes in some sparql-client.
    - `b` is a binding acquired from posing `sparql-query` to its client
  
  "
  [gacc b]
  (let [bpo #{:bnode :p :o}
        spb #{:s :p :bnode}
        ]
    (cond
      (= (set/intersection (set (keys b)) bpo) bpo)
      (add gacc [(:bnode b)
                 (:p b) (:o b)])
      (= (set/intersection (set (keys b)) spb) spb)
      (add gacc [(:s b)
                 (:p b) (:bnode b)]
           )
      :else
      (throw (ex-info "Fell through in  collect-bnode-query-binding"
                      {:type :fell-through-in-cond
                       ::gacc gacc
                       ::b b
                       })))))

(declare collect-bnode-triple-class-annotation)

(defn- derive-bnode-classes
  "Returns `ann`', with a BnodeClass defined for each Bnode in the client model.
  - where
    - `ann` := (:bnodes client)  is an bnode annotation graph
  - See ont.clj for vocabulary
  "
  [ann]
  (trace ::starting-derive-bnode-classes
         ::ann ann)
  (let [collect-bnode-class (fn [ann client-bnode]
                              (let [ann (traverse ann
                                                  collect-rendered-as
                                                  {:skip? #{}
                                                   ::notes ann
                                                   }
                                                  ann
                                                  (list client-bnode))
                                    bnode-class (unique (ann client-bnode
                                                             :bnode/bnode-class))
                                    ]
                                (add ann
                                     [[bnode-class :bnode/derived-from client-bnode]
                                      [client-bnode :bnode/bnode-class bnode-class]])))
        ]
    (->> (ann :bnode/BnodeClass :bnode/render-type-of)
         (reduce collect-bnode-class ann))))

(defn reset-annotation-graph
  "Returns `client`' annotated for blank nodes so that round-trippable values can be
  provided. Overwrites any previous annotations.
  - Where
    - `client` is a sparql-client
    - `client-model` is a native-normal graph containing a subgraph of the client's
       graph with triples containing the bnodes we wish to annotate. Optional. Defalt
       will build a new client model from `bnode-query` and `collect-bindings`
    - `bnode-query` is a SPARQL query for the client model. Default is
      `query-for-all-bnodes`
    - `collect-bindings` is a `reduce-spo` function collecting the output of
       `bnode-query` into a new `client-model`
    - `ignore-if` := (fn [ann clause-type node property counterpart] -> truthy when a
      given clause should not inform annotations for bnodes. Referenced in
      `ignore-triple-clause?` function.
  "
  [client & {:keys [client-model bnode-query collect-bindings ignore-if]
             :or {bnode-query query-for-all-bnodes
                  collect-bindings collect-bnode-query-binding
                  ignore-if (fn [_ann _clause-type _node _property counterpart]
                              (string? counterpart))
                  }}]
  {:pre [(spec/valid? ::sparql-client client)]
   }
  (let [client-model (or client-model
                         (reduce (or collect-bindings
                                     collect-bnode-query-binding)
                                 (make-graph)
                                 (query-endpoint
                                  client
                                  (prefixed
                                   (render bnode-query
                                           {:graph-uri (voc/uri-for (:graph-uri client))
                                            })))))
        
        ]
    (assoc client
           :bnodes (-> (reduce-spo collect-bnode-triple-class-annotation
                                   (add (make-graph)
                                        [:bnode/AnnotationGraph
                                         :bnode/client-model client-model
                                         :bnode/ignore-if ignore-if
                                         ])
                                   client-model)
                       ;;(derive-bnode-clause-classes)
                       (derive-bnode-classes)
                       ))))

(defn- ignore-triple-clause?
  "Truthy when a given clause should not inform annotations for bnodes
  - Where
    - `ann` is an annotation graph which may have [:bnode/AnnotationGraph :bnode/ignore-if `ignore?`
    - `clause-type` :~ #{`:bnode/SubordinateClause` `:bnode/ContextClause`}
    - `node` is a kwi representing a blank node in some snapshot of an RDF graph
    - `property` is a kwi naming a property
    - `counterpart` is a kwi or literal naming the opposing element to `node` in the
       annoated triple.
    - `ignore?` (fn [ann clause-type node property counterpart] -> truthy as set in
      (ann :bnode/AnnotationGraph :bnode/ignore-if `ignore?`)
      - there may be 0 or more of these.
    - `:bnode/SubordinateClause` leads 'outward' from `node`
    - `:bnode/ContextClause` leads 'inward' to `node` and is rendered with the inversion
       operator ^
  "
  [ann clause-type node property counterpart]
  (seq (filter
        identity
        (map (fn [ignore?] (ignore? ann clause-type node property counterpart))
             (ann :bnode/AnnotationGraph :bnode/ignore-if)))))

#_(defn annotate-subgraph
  "Returns `ann`' annotated for `subgraph`
  - Where
    - `subgraph` is a subgraph of sparql-client typically constucted from query-for-X
       output. Typically this graph has only one subject, and may only have a single
       predicate as well.
    - `ann` := (:bnodes `client`)
    - see BNODE annotations section in ont.clj for vocabulary
  "
  ([ann subgraph]
   (let [;; the subgraph of g that contains bnodes....
         bnode-subgraph (fn bnode-subgraph [acc s p o]
                          (if (or (rdf/bnode-kwi? s)
                                  (rdf/bnode-kwi? o))
                            (add acc [s p o])
                            acc))
         ;; get pertinent annotations...
         collect-bindings
         (fn [sacc p]
           ;; returns #{{:?prop :?clause-class :?render-type :?client-node}, ...}
           (let [collect-clause-class
                 (fn [sacc clause-class]
                   (reduce
                    conj sacc
                    (map (fn [b]
                           (assoc b
                                  :?prop p
                                  :?clause-class clause-class
                                  ))
                         (query ann
                                [[clause-class :bnode/render-type :?render-type]
                                 [clause-class 
                                  (traverse-or :bnode/object
                                               :bnode/referring)
                                  :?correspondent]
                                 [clause-class :bnode/node :?client-node]
                                 ]))))
                 
                 ]
             (reduce collect-clause-class sacc (ann p :bnode/property-of))))

         bindings (value-trace
                   ::bindings-in-annotate-subgraph
                   (reduce collect-bindings
                          #{}
                          (reduce (fn [acc s]
                                    (let [subgraph' (reduce-spo bnode-subgraph
                                                                (make-graph) subgraph)]
                                      (reduce conj acc (keys (subgraph' s)))))
                                  #{}
                                  (subjects subgraph))))

         ;; link bnodes in g to subordinate clauses derived from the client model...
         collect-target (fn [b ann tb]
                          (if (= (:?render-type b) :bnode/SubordinateClause)
                            (add ann [(:?target-node tb)
                                      :bnode/node-of-subordinate
                                      (:?clause-class b)
                                      ])
                            ;; else
                            ann))

         ;; link bnodes in g to correspondents in context clauses...
         collect-target-correspondent (fn [b ann tb]
                                        ;; tb := {:?target-node...
                                        ;;        maybe :?target-correspondent..}
                                        (if (= (:?render-type b) :bnode/ContextClause)
                                          (if-let [tc (:?target-correspondent tb)]
                                            (add ann
                                                 [tc
                                                  :bnode/node-of-context
                                                  (:?clause-class b)
                                                  ])
                                            ;; else no target correspondent
                                            ann)
                                          ;; else not a context clause
                                          ann))

         ;; returns the :?variable in binding b as appropriate for correspondents
         correspondent-elt (fn [b] (if (rdf/bnode-kwi? (:?correspondent b))
                                     ;; bind to a variable...
                                     :?target-correspondent
                                     ;; else use literal value from b
                                     (:?correspondent b)))
         ;; annotates bnode from g linking it to clauses annotated for the client model
         integrate-binding (fn [ann b]
                             (let [
                                   target-bindings
                                   (if (= (:?render-type b)
                                          :bnode/ContextClause)
                                     (query
                                      subgraph
                                      [[(correspondent-elt b)
                                        (:?prop b)
                                        :?target-correspondent]])
                                     ;; else
                                     (query
                                      subgraph
                                      [[:?target-node (:?prop b) (correspondent-elt b)]]))
                                   ]
                               (-> ann
                                   ((partial reduce (partial collect-target b))
                                    target-bindings)
                                   ((partial reduce (partial collect-target-correspondent b))
                                    target-bindings))))
         ]
     (reduce integrate-binding ann bindings)
     )))

(defn- annotate-triple-clause
  "Returns `ann`' with annotations of `clause-type` for a clause describing `property` and `object`
  - where
    - `ann` is a native-normal graph annotating some snapshot of an RDF graph
    - `clause-type` :~ #{`:bnode/SubordinateClause` `:bnode/ContextClause`}
    - `node` is a kwi representing a blank node in some snapshot of an RDF graph
    - `property` is a kwi naming a property
    - `counterpart` is a kwi or literal naming the opposing element to `node` in the
       annoated triple.
    - `:bnode/SubordinateClause` leads 'outward' from `node`
    - `:bnode/ContextClause` leads 'inward' to `node` and is rendered with the inversion
       operator ^
  "
  [ann clause-type node property counterpart]
  (if (ignore-triple-clause? ann clause-type node property counterpart)
    ann
    ;; else don't ignore
    (value-trace
     ::annotate-triple-clause-result
     [::ann ann
      ::clause-type clause-type
      ::node node
      ::property property
      ::counterpart counterpart]
     (let [clause-class (mint-kwi :bnode/BnodeClauseClass clause-type property counterpart) 

           counterpart-annotation (case clause-type
                                    :bnode/SubordinateClause :bnode/object
                                    :bnode/ContextClause :bnode/referring)
           reciprocal-annotation  (case clause-type
                                    :bnode/SubordinateClause :bnode/object-of-clause
                                    :bnode/ContextClause :bnode/referring-of-clause)
           ]
       (add ann [[clause-class
                  :rdfs/subClassOf :bnode/BnodeClauseClass
                  :bnode/render-type clause-type
                  :bnode/node node
                  :bnode/property property
                  counterpart-annotation counterpart
                  ]
                 [clause-type :bnode/render-type-of clause-class]
                 [counterpart reciprocal-annotation clause-class]
                 [node
                  :rdfs/subClassOf clause-class
                  :bnode/render-type :bnode/BnodeClass
                  ]
                 [:bnode/BnodeClass :bnode/render-type-of node]
                 [property
                  :bnode/property-of clause-class
                  :bnode/render-type :bnode/Simple
                  ]
                 [counterpart
                  :bnode/render-type
                  (if (rdf/bnode-kwi? counterpart)
                    :bnode/BnodeClass
                    :bnode/Simple)
                  ]
                 ]
            )))))

(defn- annotate-reciprocals
  "Returns `ann`', with `:bnode/reciprocoal-of` assertions added as appropriate for triple [s p o] in `ann`
  - Where
    - `ann` is a bnode annotation graph
    - `s`, `p`, `o` define a triple in the `client-model` of `ann`
    - `client-model` is a subgraph of the client containing bnodes.
  "
  [ann s p o]
  (let [this-way (unique (filter (fn [clause-class]
                                   (and (ann clause-class :bnode/property p)
                                        (ann clause-class :bnode/object o)))
                                 (ann s :rdfs/subClassOf)))
        that-way (unique (filter (fn [clause-class]
                                   (and (ann clause-class :bnode/property p)
                                        (ann clause-class :bnode/referring s)))
                                 (ann o :rdfs/subClassOf)))
        ]
    (add ann [[this-way :bnode/reciprocal-of that-way]
              [that-way :bnode/reciprocal-of this-way]])))
                                    
(defn- collect-bnode-triple-class-annotation
  "Returns `ann`' annotating `s` `p` `o` from `ann`'s `client-model`
  - a reduce-spo function
  - where
    - `ann` is a bnode annotation graph for some client
    - `s` `p` `o` are a triple in the `client-model`
    - `client-model` is a native-normal graph holding a subgraph of the client representing
       the contexts of bnodes in that graph which we will need to describe if we're to
       generate round-trippable element descriptions.
  "
  [ann s p o]
  {:pre [(and ann s p o)]
   }
  (trace
   ::starting-collect-bnode-triple-class-annotaton
   ::ann ann ::s s ::p p ::o o)
  (cond
    (and (rdf/bnode-kwi? s) (rdf/bnode-kwi? o))
    ;; we need reciprocal clauses pointing both ways...
    (-> ann
        (annotate-triple-clause :bnode/SubordinateClause s p o)
        (annotate-triple-clause :bnode/ContextClause o p s)
        (annotate-reciprocals s p o))
    
    (rdf/bnode-kwi? s)
    (annotate-triple-clause ann :bnode/SubordinateClause s p o)

    (rdf/bnode-kwi? o)
    (annotate-triple-clause ann :bnode/ContextClause o p s)

    :else
    (throw (ex-info "Fell through in collect-bnode-annotation"
                    {:type ::FellThroughInCond
                     ::ann ann
                     ::s s
                     ::p p
                     ::o o
                     }))))

(defn- list-subjects
  "Returns (`s`, ...)`, subjects from `client`, with bnodes possibly rendered as bnode descriptions.
  - Where
    - `client` is a sparql-client
    - `s` is a subject in `client`.
  - Note: If (:bnodes client) is non-nil blank nodes will be be rendered as a keyword
    whose name can be used to-round-trip on subsequent queries. If (:bnodes client)
    is nil, bnodes will be rendered as ordinary keywords which will not round-trip.
  "
  [client]
  (let [raw-subjects (query-for-subjects client)
        non-bnode-subjects (filter (complement rdf/bnode-kwi?) raw-subjects)
        ]
    (if-let [ann (:bnodes client)]
      (reduce conj
              (doall (map (fn l-s [client-bnode] (value-trace
                                           ::l-s-result
                                           [::ann ann ::client-bnode client-bnode]
                                           (unique (ann client-bnode :bnode/bnode-class))))
                   (value-trace
                    ::lsf-result
                    (filter (fn l-s-f [s] (ann s :bnode/render-type :bnode/BnodeClass))
                            (subjects
                             (unique (ann :bnode/AnnotationGraph :bnode/client-model)))))))
              non-bnode-subjects)
      ;; else bnodes are not annotated
      raw-subjects)))

(defn- collect-bnode-class
  "Returns `sacc`' with the bnode class of `o` added.
  - where
    - `ann` := (:bnodes `sparql-client`)
    - `sacc` is a set accumulator
    - `o` is a bnode in the client model of `ann`
  - Notes
    - typically used to look up bnodes classes in accessor functions.
  "
  [ann sacc o]
  (conj sacc
        (if (rdf/bnode-kwi? o)
          (unique (ann o :bnode/bnode-class))
          o)))

(defn- get-o-with-bnodes
    "Returns #{`o`...} for `s` and `p` at endpoint of `client`
  - Where:
  - `client` conforms to ::sparql-client
  - `s` is a subject URI rendered per binding translator of `client`
  - `p` is a predicate URI rendered per binding translator of `client`
  - `o` is an object rendered per binding translator of `client`
  - Note: If (:bnodes client) is non-nil blank nodes will be be rendered as a keyword
    whose name can be used to-round-trip on subsequent queries. If (:bnodes client)
    is nil, bnodes will be rendered as ordinary keywords which will not round-trip.
  "
  [client s p]
  (if-let [ann (:bnodes client)]
    (let [client-model (unique (ann :bnode/AnnotationGraph :bnode/client-model))
          ]
      (if (rdf/bnode-kwi? s)
        (let [client-desc (client-model (or (unique (ann s :bnode/derived-from))
                                            s))
              ]
          (reduce (partial collect-bnode-class ann) #{} (p client-desc)))
        ;; else this is a standard URI KWI
        (let [result
              (reduce (partial collect-bnode-class ann)
                      (into #{} (filter (complement rdf/bnode-kwi?)
                                        (query-for-o client s p)))
                      (client-model s p))
              ]
          (when (seq result)
            result))))
    ;; else there are no bnode annotations
    (query-for-o client s p)))

(defn- collect-bnode-description
  "Returns {`p` #{`bnode-class` or `o`, ...}...} for `p` and `os`
  - Where
    - `ann` := (:bnodes `sparql-client`)
    - `macc` is a map accumultor
    - `p` is a kwi for a property in the description of some `s` in `sparql-client`
    - `os` := #{o, ...}
    - `o` is an object in said description, which may be a bnode annoated in `ann`
  "
  [ann macc p os]
  (assoc macc p (reduce (partial collect-bnode-class ann)
                        #{}
                        os)))

(defn- integrate-bnode-and-non-bnode-descriptions
  "Returns `desc` modified for bnodes in `ann` for `s`
  - Where
    - `ann` := (:bnodes `sparql-client`)
    - `s` is a subject in `sparql-client`, which may be a kwi for a URI or a bnode class
      annotated in `ann`
    - `desc` := {p #{o, ...}}, a description of `s` in `sparql-client`
  "
  [ann s desc]
  (let [client-model (unique (ann :bnode/AnnotationGraph :bnode/client-model))
        client-desc (client-model (or (unique (ann s :bnode/derived-from))
                                      s))
        remove-bnode-objects (fn [macc p os]
                               (assoc macc p (into #{}
                                                   (filter (complement rdf/bnode-kwi?)
                                                           os))))
        g (make-graph
                  :contents
                  {s (reduce-kv remove-bnode-objects
                                {}
                                desc)})
        ]
    (-> (if client-desc
          (union g
                 (make-graph
                  :contents
                  {s (reduce-kv (partial collect-bnode-description ann)
                                {}
                                client-desc)}))
          g)
        (get-p-o s))
    ))

(defn- get-p-o-with-bnodes
    "Returns {`p` #{`o`...}...} for `s` at endpoint of `client`
- Where
  - `client` conforms to ::sparql-client
  - `s` is a subject uri keyword. ~ voc/voc-re
  - `p` is a predicate URI rendered per binding translator of `client`
  - `o` is an object value, rendered per the binding translator of `client`
  - Note: If (:bnodes client) is non-nil blank nodes will be be rendered as a keyword
    whose name can be used to-round-trip on subsequent queries. If (:bnodes client)
    is nil, bnodes will be rendered as ordinary keywords which will not round-trip.
"

  [client s]
  (if-let [ann (:bnodes client)]
    (let [client-model (unique (ann :bnode/AnnotationGraph :bnode/client-model))
          client-desc (client-model (or (unique (ann s :bnode/derived-from))
                                        s))
          
          ]
      
      (if (rdf/bnode-kwi? s)
        (reduce-kv (partial collect-bnode-description ann)
                   {}
                   client-desc)
        ;; else this is a standard URI KWI
        (let [desc (query-for-p-o client s)
              result (integrate-bnode-and-non-bnode-descriptions ann s desc)
              ]
          (when (seq result)
            result))))
    ;; else there are no bnode annotations
    (query-for-p-o client s)))

(defn- get-normal-form-with-bnodes
  "Returns normal form rendering of `client`, possibly with bnodes rendered in
    round-trippable form
  - Where
    - `client` is a sparql-client
  - Note: If (:bnodes client) is non-nil blank nodes will be be rendered as a keyword
    whose name can be used to-round-trip on subsequent queries. If (:bnodes client)
    is nil, bnodes will be rendered as ordinary keywords which will not round-trip.
  "
  [client]
  (if-let [ann (:bnodes client)]
    (let [client-model (unique (ann :bnode/AnnotationGraph :bnode/client-model))
          nf (-> (make-graph)
                 (add (query-for-normal-form client)))
          integrate-subject (fn [g s]
                              (trace ::starting-integrate-subject ::ann ann ::nf nf ::s s)
                              (let [desc (integrate-bnode-and-non-bnode-descriptions
                                          ann
                                          s
                                          (nf s))
                                    ]
                                (if (seq desc)
                                  (add g
                                       ^{::igraph/triples-format :normal-form}
                                       {s desc})
                                  g)))
          g (reduce integrate-subject
                    (make-graph)
                    (filter (complement rdf/bnode-kwi?)
                            (subjects nf)))
          collect-client-model-subject (fn [g client-subject]
                                         (let [s (unique
                                                  (ann client-subject :bnode/bnode-class))
                                               desc (client-model client-subject)
                                               ]
                                           (add-to-graph
                                            g
                                            ^{::igraph/triples-format :normal-form}
                                            {s (reduce-kv
                                                (partial collect-bnode-description ann)
                                                {}
                                                desc)})))
          
          
          ]
      (-> (reduce collect-client-model-subject g
                  (filter rdf/bnode-kwi? (subjects client-model)))
          (normal-form)))
    ;; else there are no bnode-annotations
    (query-for-normal-form client)))  

;;;;;;;;;;;;;;;
;; DEPRECATED
;;;;;;;;;;;;;;;
;; I/O functions from versions <= 0.1.1 are deprecated in favor of rdf/load-rdf and
;; rdf/read-rdf methods.

^{:deprecated "0.2"
  :superseded-by "ont-app.rdf.core/read-rdf"
  }
(defmulti ^:deprecated load-rdf-file 
  "DEPRECATED. use `rdf/read-rdf` instead
"
  (fn ld-rdf-file [g path] [(type g) (type path)])
  )

^:deprecated
(defmethod load-rdf-file [SparqlUpdater java.net.URI]
  [g path]
  (let [graph-uri (voc/qname-for (:graph-uri g))
        directive (prefixed
                   (render "LOAD <{{path}}> INTO GRAPH {{graph-uri}}"
                                  {:path path
                                   :graph-uri graph-uri
                                   }))
        ]
    (info ::LoadingRDFFile
          :glog/message "Loading <{{sparql-client/path}}> into {{sparql-client/graph-uri}}"
          :sparql-client/path path
          :sparql-client/graph-uri graph-uri)
    (try (update-endpoint! g directive)
       (catch Throwable e
         (throw (ex-info (str "Failed to load RDF file " path)
                         (merge (ex-data e)
                                {:type ::FailedToLoadRDFFile
                                 ::g g
                                 ::path path
                                 ::directive directive
                                 }))))))
  path)

^:deprecated
(defmethod load-rdf-file [SparqlUpdater java.io.File]
  [g ^java.io.File path]
  (load-rdf-file g (java.net.URI. (str "file://" (.getAbsolutePath path)))))

^:deprecated
(defmethod load-rdf-file [SparqlUpdater java.lang.String]
  [g path]
  (load-rdf-file g (io/as-file path)))
