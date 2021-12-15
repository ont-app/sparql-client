(ns ont-app.sparql-client.core
  {
   :voc/mapsTo 'ont-app.sparql-client.ont
   }
  (:require
   [clojure.string :as s]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as spec]
   ;; 3rd party
   [selmer.parser :as selmer]
   [taoensso.timbre :as timbre]
   [cognitect.transit :as transit]
   ;; ont-app
   [ont-app.graph-log.core :as glog]
   [ont-app.graph-log.levels :as levels :refer :all]
   [ont-app.sparql-endpoint.core :as endpoint]
   [ont-app.igraph.core :as igraph :refer :all]
   [ont-app.igraph.graph :as graph]
   [ont-app.sparql-client.ont :as ont]
   [ont-app.vocabulary.core :as voc]
   [ont-app.vocabulary.lstr :refer [->LangStr lang]]
   )
  (:import
   [java.io ByteArrayInputStream ByteArrayOutputStream]
   [ont_app.vocabulary.lstr LangStr]
   )
  (:gen-class))

(def ontology @ont/ontology-atom)

;;;;;;;;;;;
;; SPECS
;;;;;;;;;;;

(spec/def ::auth-key
  #{:basic-auth :digest-auth :ntlm-auth :oauth-token})

(spec/def ::basic-auth-value
  (fn [v] (or (and (vector? v)
                   (= (count v) 2)
                   (string? (v 0))
                   (string? (v 1)))
              (and (string? v)
                   (re-matches #".+:.+" v)))))

(spec/def ::digest-auth-value
  (fn [v] (and (= (count v) 2) (string? (v 0)) (string? (v 1)))))

(spec/def ::ntlm-auth-value
  (fn [v] (and (vector? v)
               (= (count v) 4)
               (string? (v 0))
               (string? (v 1))
               (string? (v 2))
               (string? (v 3))
               )))

(spec/def ::oauth-token-value string?)

(defn check-auth-value [k v]
  (spec/valid?
   (case k
     :basic-auth ::basic-auth-value
     :digest-auth ::digest-auth-value
     :ntml-auth ::ntlm-auth-value
     :oauth-token ::oauth-token-value)
   v))

(defn check-auth [m]
  (or (nil? m)
      (and (map? m)
           (let [[[k v]] (seq m)]
             (and (spec/valid? ::auth-key k)
                  (check-auth-value k v))))))

(spec/def ::auth check-auth)

(defn bnode-kwi?
  "True when `kwi` matches output of `bnode-translator`."
  [kwi]
  (->> (namespace kwi)
       (re-matches #"^_.*")))

(spec/def ::bnode-kwi bnode-kwi?)


;; VOCABULARY
(def warn-on-no-ns-metadata-for-kwi?
  "True when we should warn if there is no ns metadata found for the URI
  being translated into a KWI in `kwi-for`.
  "
  (atom false))

(def kwi-for
  "Issues a warning if no ns metadata is found for the URI"
  (partial
   voc/keyword-for
   (fn [uri kw]
     (when @warn-on-no-ns-metadata-for-kwi?
       (value-warn
        ::NoNamespaceMetadataFound
        [:glog/message "No ns metadata found for {{log/uri}}"
         :log/uri uri]))
     kw)))

;; RENDERING AND READING RDF ELEMENTS

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
   ;; TODO: add test for blank node
   }
  [sparql-binding]
  (value-trace
   ::URI_Translator
   [:log/sparql-binding sparql-binding]
   (kwi-for (sparql-binding "value"))))

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
  {:post [(fn [kwi] (spec/valid? ::bnode-kwi kwi))]
   }
  (keyword (str "_" (hash (str query-url graph-uri)))
           (sparql-binding "value")))


(defn rdf-bnode
  "Returns RDF string for `kwi` suitable for use as an element in an
  INSERT clause. "
  [kwi]
  {:pre [(spec/valid? ::bnode-kwi kwi)]
   }
  (str "_:b" (subs (namespace kwi) 1) "_" (name kwi))
  )

(defn form-translator [sparql-binding]
  "Returns a keyword for  `binding` as a keyword URI for a natlex form"
  (keyword (str (sparql-binding "xml:lang")
                "Form")
           (s/replace (sparql-binding "value")
                      " "
                      "_")))

(defn quote-str [s]
  "Returns `s`, in excaped quotation marks.
Where
<s> is a string, typically to be rendered in a query or RDF source.
"
  (value-trace
   ::QuoteString
   (str "\"" s "\"")
   ))

(def transit-write-handlers
  "Atom of the form {<Class> <write-handler>, ...}
  Where
  <Class> is a direct reference to the class instance to be encoded
  <write-handler> := fn [s] -> {<field> <value>, ...}
  " 
  (atom
   {LangStr
    (cognitect.transit/write-handler
     "ont_app.vocabulary.lstr.LangStr"
     (fn [ls]
       {:tag (.tag ls)
        :s (.s ls)
        }))
    }))
  
(defn render-transit-json 
  "Returns a string of transit for `value`
  Where
  <value> is any value that be handled by cognitict/transit
  Note: custom datatypes will be informed by @transit-write-handlers
  "
  [value]
  (let [output-stream (ByteArrayOutputStream.)
        ]
    (transit/write
     (transit/writer output-stream :json {:handlers @transit-write-handlers})
     value)
    (String. (.toByteArray output-stream))))


(def transit-read-handlers
  "Atom of the form {<className> <read-handler>
  Where
  <className> is a fully qualified string naming a class to be encoded
  <read-handler> := fn [from-rep] -> <instance>
  <from-rep> := an Object s.t. (<field> from-rep), encoded in corresponding
    write-handler in @`transit-write-handlers`.
  "
  (atom
   {"ont_app.sparql_endpoint.core.LangStr"
    (cognitect.transit/read-handler
     (fn [from-rep]
       (->LangStr (:s from-rep) (:tag from-rep))))
    }
    ))

(defn read-transit-json
  "Returns a value parsed from transit string `s`
  Where
  <s> is a &quot;-escaped string encoded as transit
  Note: custom datatypes will be informed by @transit-read-handlers
  "
  [^String s]
  (transit/read
   (transit/reader
    (ByteArrayInputStream. (.getBytes (clojure.string/replace s "&quot;" "\"")
                                      "UTF-8"))
    :json
    {:handlers @transit-read-handlers})))

(defn render-literal-as-transit-json
  "Returns 'x^^transit:json'
  NOTE: this will be encoded on write and decoded on read by the
    cognitect/transit library."
  [x]
  (selmer/render "\"{{x}}\"^^transit:json" {:x (render-transit-json x)}))

(defn datatype-translator [sparql-binding]
  "Parses value from `sparql-binding`. If it's tagged as transit:json,
  it will read the transit, otherwise try to parse xsd values,
  defaulting to a object with metadata."
  (let [type-spec (voc/keyword-for (sparql-binding "datatype"))
        ]
    (value-trace
     ::DatatypeTranslation
     [:log/sparql-binding sparql-binding]
     (if (= type-spec :transit/json)
       (read-transit-json (sparql-binding "value"))
       ;; else
       (let [dt (endpoint/parse-xsd-value sparql-binding)]
         (if (= (type dt) org.apache.jena.datatypes.xsd.XSDDateTime)
           (try (read-string (str "#inst \"" (str dt) "\""))
                (catch Throwable e
                  (str "Unparsable timestring: " (str dt))))
           dt))
       ))))

(defn default-binding-translators
  "Binding translators used to simplify bindings. See sparq-endpoint.core
  <endpoint-url> and <graph-uri> are used to mint unique values for bnodes.
  "
  [endpoint-url graph-uri]
  (merge endpoint/default-translators
         {:uri uri-translator
          :bnode (partial bnode-translator endpoint-url graph-uri)
          :datatype datatype-translator
          }))

(defn render-literal-dispatch
  "Returns a key for the render-literal method to dispatch on given `literal`
  Where
  <literal> is any non-keyword
  NOTE: ::instant and ::xsd-type are special cases, otherwise (type <literal>)
  "
  [literal]
  (value-trace
   ::RenderLiteralDispatch
   [:log/iteral literal]
   (cond 
     (inst? literal) ::instant
     (endpoint/xsd-type-uri literal) ::xsd-type
     :default (type literal))))

(defmulti render-literal
  "Returns an RDF (Turtle) rendering of `literal`"
  render-literal-dispatch)


(defmethod render-literal ::instant
  [instant]
  (let [xsd-uri (endpoint/xsd-type-uri
                 (if (not (instance? java.time.Instant instant))
                   (.toInstant instant)
                   instant))
        ]
    (str (quote-str (.toInstant instant))
         "^^"
         (voc/qname-for (kwi-for xsd-uri)))))

(defmethod render-literal ::xsd-type
  [xsd-value]
  (let [xsd-uri (endpoint/xsd-type-uri xsd-value)]
    (str (quote-str xsd-value) "^^" (voc/qname-for (kwi-for xsd-uri)))))


(defmethod render-literal (type #lstr "@en")
  [lang-str]
  (str (quote-str (str lang-str)) "@" (lang lang-str)))

(defmethod render-literal (type [])
  [v]
  (render-literal-as-transit-json v))

(defmethod render-literal (type {})
  [m]
  (render-literal-as-transit-json m))

(defmethod render-literal (type '(nil))
  [s]
  (render-literal-as-transit-json s))


(defmethod render-literal :default
  [s]
  (quote-str s)
  )

;; NEW DATATYPES 
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
<auth> := {<auth-key> <auth-value} specifying authorization, or nil
<binding> is the value returned by a call to <query-url>
<simplified> is a single scalar representation of a SPARQL binding. 
  See sparql-endpoint.core.
<auth-key> :- #{:basic-auth, :digest-auth :ntlm-auth :oauth-token}
<auth-value> is a value appropriate to <auth-key>, e.g.
  [<user>, <pw>] for :basic-auth.
  See https://github.com/dakrone/clj-http for details
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
<auth> := {<auth-key> <auth-value} specifying authorization, or nil
<binding> is the value returned by a call to <query-url>
<simplified> is a single scalar representation of a SPARQL binding. 
  See sparql-endpoint.core.
<auth-key> :- #{:basic-auth, :digest-auth :ntlm-auth :oauth-token}
<auth-value> is a value appropriate to <auth-key>, e.g.
  [<user>, <pw>] for :basic-auth.
  See https://github.com/dakrone/clj-http for details
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

(defn make-sparql-reader
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
  [& {:keys
      [graph-uri query-url binding-translator auth]}]
  {:pre [(spec/valid? ::auth auth)]
   }
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

(defn make-sparql-updater 
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
  [& {:keys
      [graph-uri query-url binding-translator
       update-url auth]}]
  {:pre [(spec/valid? ::auth auth)]
   }
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



(defn query-endpoint [client query]
  "Returns [<simplified-binding> ...] for `query` posed to `client`
Where
<simpified-binding> := {<key> <value> ...},
   the output of the binding translator of <client>
<query> is a SPARQL SELECT query
<client> is a SparqlReader or SparqlUpdater
"
  (let [dbg-query (debug ::StartingQueryEndpoint
                         :log/query query
                         :log/query-url (:query-url client))
        ]
  (value-debug
   ::QueryEndpointResult
   [:log/resultOf dbg-query]
   (map (partial endpoint/simplify (:binding-translator client))
        (endpoint/sparql-select (:query-url client)
                                query
                                (or (:auth client) {}) ;; http-req
                                )))))


(defn ask-endpoint [client query]
  "Returns boolean value of `query` posed to `client`
Where
<query> is a SPARQL ASK query
<client> conforms to ::sparql-client spec
"
  (let [starting (debug ::StartingAskEndpoint
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

(defn check-ns-metadata 
  "Logs a warning when `kwi` is in a namespace with no metadata."
  [kwi]
  (let [n (symbol (namespace kwi))]
    (if-let [the-ns (find-ns n)]
      (when (not (meta the-ns))
        (warn ::NoMetaDataInNS
              :glog/message "The namespace for {{log/kwi}} is in a namespace with no associated metadata."
              :log/kwi kwi))))
  kwi)


(defn check-qname [uri-spec]
  "Traps the keyword assertion error in voc and throws a more meaningful error about blank nodes not being supported as first-class identifiers."
  (if (bnode-kwi? uri-spec)
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

^:traversal-fn
(defn property-path
  "Returns fn [g c a q] -> c a' q' for `path`
  Where
  <g> is an sparql update client
  <c> is a tranversal context
  <a> is an accumulator (typically a set)
  <a'> has been conj'ed with the `?o` bindings <query>
  <q> is an input q to the traversal
  <q'> is the rest of <q>
  <path> is a SPARQL property path, e.g. 'myns:prop1/myns:prop2'
  <query> is `query-for-o-template`, with (first <q>) as subject and <path>
    as the predicate. This binds a single var `?o`.
  "
  [path]
  (fn [g c a q]
    (let [query
          (prefixed
           (selmer/render
            query-for-o-template
            (merge (query-template-map g)
                   {:subject (check-qname (first q))
                    :predicate path})))
          query-trace (trace ::PropertyPathQuery
                              :log/path path
                              :log/query query)
          ]
      [c, ;; unchanged context
       ;; accumulate....
       (let [result (value-trace
                     ::PropertyPathQueryResult
                     [:log/resultOf query-trace]
                     (query-endpoint g query))
             ]
         (reduce conj a (map :o result))),
       ;; queue....
       (rest q)
       ])))

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
                                  (render-literal o))})))
        starting (debug ::Starting_ask-s-p-o
                        :log/query query
                        :log/subject s
                        :log/predicate p
                        :log/object o)
        ]
    (value-debug
     ::ask-s-p-o-return
     [:log/resultOf starting]
     (ask-endpoint client query))))

(defn update-endpoint [client update]
  "Side-effect: `update` is sent to `client`
Where
<update> is a sparql update
<client> is a SparqlUpdater
"
  (let [start-state (debug ::StartingUpdateEndpoint
                           :log/update (prefixed update))
        ]
  (value-debug
   ::UpdateEndpointResult
   [:log/resultOf start-state]
   (endpoint/sparql-update (:update-url client)
                           (prefixed update)
                           (or (:auth client) {}) ;; http-req
                           ))))

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


(defn as-rdf 
  "Returns a clause of rdf for `igraph-vector`, using `render-literal`
Where
  <igraph-vector> := [<s> <p> <o> & maybe <p> <o>, ...]
  <render-literal> := (fn [o] ...) -> parsable rendering of <o> if <o> is 
  not a KWI (which would be treated as a URI and we'd use voc/qname-for)
  This is optional. Default is `render-standard-literal`
"
   [igraph-vector]
   {:pre [(spec/valid? ::igraph/vector igraph-vector)]
    }
   (let [render-element (fn [elt]
                          (if (keyword? elt)
                            (if (bnode-kwi? elt)
                              (rdf-bnode elt)
                              ;; else not a bnode...
                              (voc/qname-for elt))
                            ;; else not a keyword...
                            (render-literal elt)))
         render-p-o (fn [p-o]
                      (s/join  " " (map render-element p-o)))
         ]
     (str (render-element (first igraph-vector))
          "\n"
          (s/join ";\n" (map render-p-o (partition 2 (rest igraph-vector))))
          ".")))

(defn as-query-clause 
  "Returns a clause of SPARQL with variables to fill out the rest of `partial-triple`
Where
<partial-triple> := [<s>] or [<s> <p>]
"
  [var-fn partial-triple]
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
  {:pre [(spec/valid? ::igraph/vector-of-vectors triples)]
   }
  (selmer/render add-update-template
                 (merge (query-template-map client)
                        {:triples (s/join "\n"
                                          (map as-rdf 
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
  (add-to-graph client ^:vector-of-vectors [triple]))

(defmethod add-to-graph [SparqlUpdater :normal-form]
  [client triples]
  (add-to-graph
   client
   ^:vector-of-vectors
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
              (as-rdf triple)
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


(defmulti load-rdf-file 
  "Returns <file-uri> for `path`
  Side-effect: loads contents of <file-uri> into (:graph-uri `g`)
  Where
  <file-uri> is a uri for <path>
  <path> identifies a file containing RDF
  <g> has a update-endpoint method.
"
  (fn [g path] [(type g) (type path)])
  )

(defmethod load-rdf-file [SparqlUpdater java.net.URI]
  [g path]
  (let [graph-uri (voc/qname-for (:graph-uri g))
        directive (prefixed
                   (selmer/render "LOAD <{{path}}> INTO GRAPH {{graph-uri}}"
                                  {:path path
                                   :graph-uri graph-uri
                                   }))
        ]
    (info ::LoadingRDFFile
          :glog/message "Loading <{{log/path}}> into {{log/graph-uri}}"
          :log/path path
          :log/graph-uri graph-uri)
    (try (update-endpoint g directive)
       (catch Throwable e
         (throw (ex-info (str "Failed to load RDF file " path)
                         (merge (ex-data e)
                                {:type ::FailedToLoadRDFFile
                                 :g g
                                 :path path
                                 :directive directive
                                 }))))))
  path)

(defmethod load-rdf-file [SparqlUpdater java.io.File]
  [g path]
  (load-rdf-file g (java.net.URI. (str "file://" (.getAbsolutePath path)))))

(defmethod load-rdf-file [SparqlUpdater java.lang.String]
  [g path]
  (load-rdf-file g (io/as-file path)))

