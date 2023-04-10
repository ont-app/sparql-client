(ns ont-app.sparql-client.ont
  {:vann/preferredNamespacePrefix "sparql-client"
   :vann/preferredNamespaceUri
   "http://rdf.naturallexicon.org/ont-app/sparql-client/ont#"
   }
  (:require
   ;;[cognitect.transit :as transit]
   [ont-app.igraph.core :as igraph
    :refer [add
            ]]
   [ont-app.igraph.graph :as graph
    :refer [make-graph
            ]]
   [ont-app.vocabulary.core :as voc]
   )
  (:gen-class))


(voc/put-ns-meta!
 'cognitect.transit
 {
  :vann/preferredNamespacePrefix "transit"
  :vann/preferredNamespaceUri "http://rdf.naturallexicon.org/ns/cognitect.transit#"
  :dc/description "Functionality for the transit serialization format"
  :foaf/homepage "https://github.com/cognitect/transit-format"
  })

(def ontology-atom (atom (make-graph)))

(defn update-ontology! [to-add]
  (swap! ontology-atom add to-add))

(update-ontology!
 [
  [:sparql-client/IGraph
   :dc/description "Used as a common method dispatch for both SparqlReader and SparqlWriter. This is referenced in i/o methods"
   ]
  [:igraph/SerializationFormat
   :rdf/type :rdfs/Class
   :rdfs/comment "Refers to a format used to encode/decode values"
   ]
  [:transit/format
   :rdfs/domain :igraph/SerializationFormat
   :rdfs/range :rdf/Literal
   :rdfs/comment "Asserts the name of the transit encoding format"
   ]
  [:transit/json
   :rdf/type :igraph/SerializationFormat
   :transit/format :json
   :igraph/mimeType "application/transit+json"
   :rdfs/comment "Refers to transit data encoded as json. Literals whose 
  :datatype metadata is :transit/json should be readable with transit/read 
   encoded for format :json"
   ]
  [:transit/msgpack
   :rdf/type :igraph/SerializationFormat
   :transit/format :msgpack
   :igraph/mimeType "application/transit+msgpack"
   :rdfs/comment "Refers to the Transit data encoded as msgpack. Literals whose 
  :datatype metadata is :transit/msgpack should be readable with transit/read 
   encoded for format :msgpack (not currently supported in sparql-client)"
   ]
  ])


;; BNODE annotations. Used to describe the context of bnodes so we can render them in a round-trippable manner. rdfs:domain/range are entirely documentary.

(def ^{:vann/preferredNamespacePrefix "bnode"
      :vann/preferredNamespaceUri "http://rdf.naturallexicon.org/sparql-client/bnode#"
      }
  bnode-round-tripping nil)

(update-ontology!
 [
  [:bnode/AnnotationGraph
   :dc/description "Names the bnode annotation graph for a given client within that same graph."
   ]
  [:bnode/client-model
   :dc/description "Asserts the graph containing a snapshot of triples in the client involving at least one bnode. This is the basis for inferring bnode classes."
   ]
  [:bnode/RenderDispatch
   :dc/description "A value used to dispatch a rendering method. Either Simple or Bnode."
   :rdfs/comment "This class is implicit and mainly documentary."
   ]
  [:bnode/render-type-of
   :rdfs/domain :bnode/RenderDispatch
   :rdfs/range  :bnode/BnodeClauseClass
   :owl/inverseOf :bnode/render-type
   :dc/description "Names a bnode description clause associated with some render dispatch"
   ]
  [:bnode/Simple
   :rdf/type :bnode/RenderDispatch
   :dc/description
   "Rendering by simply writing the value of the node (or its qnname if a kwi)"
   ]
  [:bnode/Bnode
   :rdf/type :bnode/RenderDispatch
   :dc/description "A blank node we are trying to describe in a round-trippable manner."
   :rdfs/comment "This class is implicit and strictly documentary."
   ]
  [:bnode/object-of-clause
   :rdfs/domain :bnode/Bnode
   :rdfs/range :bnode/SubordinateClause
   :dc/description "The object a triple associated with a subordinate clause of which some bnode is the subject"
   ]
  [:bnode/referring-of-clause
   :rdfs/domain :bnode/Bnode
   :rdfs/range :bnode/ContextClause
   :dc/description "The subject a triple associated with a context clause of which some node is the object"
   ]
  [:bnode/rendered-locally-as
   :rdfs/domain :bnode/Bnode
   :rdfs/range :xsd/string
   :dc/description "Asserts a string used to render a client bnode in the context of inferring the rendering of some target BnodeClass; this will exclude redundancies introduced upstream in the process of such inference."
   ]
  [:bnode/bnode-class
   :rdfs/domain :bnode/Bnode
   :rdfs/range :bnode/BnodeClass
   :dc/description "Asserts the bnode class rended as [<clause>, ....] whose description would retrieve some bnode from the client graph."
   ]
  [:bnode/BnodeClauseClass
   :rdfs/subClassOf :bnode/Bnode
   :rdf/type :bnode/RenderDispatch
   :dc/description "A class of bnodes retrievable by a single clause."
   :rdfs/comment "Typically a bnode will be a subclass of each of its component clauses in the client model, and in the vast majority of cases the intersection of all such subclasses will uniquely identify a single bnode in the client."
   ]
  [:bnode/property-of
   :rdfs/domain :rdf/property
   :rdfs/range :bnode/BnodeClauseClass
   :dc/description "Asserts a clause class associated with some property in the client model."
   ]
  [:bnode/reciprocal-of
   :rdfs/domain :bnode/BnodeClauseClass
   :rdfs/range :bnode/BnodeClauseClass
   :dc/description "Asserts a subordinate clause which is equivalent to some context clause, or vice-versa. This is used to filter out redundant clauses when rendering local details of a bnode class description."
   ]
  [:bnode/render-type
   :rdfs/domain :bnode/BnodeClauseClass
   :rdfs/range :bnode/RenderDispatch
   :dc/description "Names the render-clause dispatch method appropriate to the clause"
   ]
  [:bnode/node
   :rdfs/domain :bnode/BnodeClauseClass
   :rdfs/range :bnode/Bnode
   :dc/description "Asserts a bnode subsumed by the clause class"
   ]
  [:bnode/property
   :rdfs/domain :bnode/BnodeClauseClass
   :owl/inverseOf :bnode/property-of
   :dc/description "Asserts an RDF property linking a Bnode to some corresponding node in a clause class"
   ]
  [:bnode/SubordinateClause
   :rdf/type :bnode/RenderDispatch
   :rdfs/subClassOf :bnode/BnodeClauseClass
   :dc/description
   "A clause describing a property attributed to some bnode"
   ]
  [:bnode/object
   :rdfs/domain :bnode/SubordinateClause
   :dc/description
   "Asserts the object of some triple of which the current bnode is the subject."
   ]
  [:bnode/ContextClause
   :rdf/type :bnode/RenderDispatch
   :rdfs/subClassOf :bnode/BnodeClauseClass
   :dc/description
   "A clause describing a link to some node 'upstream' referring to the current bnode."
   ]
  [:bnode/referring
   :rdfs/domain :bnode/ContextClause
   :rdfs/range :rdf/Resource
   :dc/description "Asserts a node 'upstream' linked to some bnode by a property within some subordinate clause."
   ]
  [:bnode/BnodeClass
   :rdfs/subClassOf :bnode/RenderDispatch
   :dc/description "A class of bnodes rendered [<clause>, ....], where each clause is a BnodeClauseClass. In the vast majority of cases this class should be a singleton within the client model, and presumabley the client as well."
   ]
  [:bnode/rendered-as 
   :rdfs/domain :bnode/BnodeClass
   :rdfs/range :xsd/string
   :dc/description "Asserts a string [<clause>, ....] whose description would retrieve some bnode from the client graph."
   ]
  [:bnode/derived-from
   :rdfs/domain :bode/BnodeClass
   :rdfs/range :bnode/Bnode
   :dc/description "Asserts the bnode in the client model from which some bnode class was derived."
   ]
  ]
 )

;; These derivations inform the mint-kwi methods....
(derive :bnode/SubordinateClause :bnode/BnodeClauseClass)
(derive :bnode/ContextClause :bnode/BnodeClauseClass)
