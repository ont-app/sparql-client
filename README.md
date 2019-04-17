# sparql-client

Provides a view onto an arbitrary [SPARQL endpoint](https://github.com/ont-app/sparql-endpoint) using the [ont-app/IGraph](https://github.com/ont-app/igraph) protocol, and incoporating the [ont-app/vocabulary](https://github.com/ont-app/vocabulary) facility.

## Installation

[![Clojars Project](https://img.shields.io/clojars/v/ont-app/sparql-client.svg)](https://clojars.org/ont-app/sparql-client)

```
(defproject ...
  :dependencies 
  [...
   [ont-app/sparql-client "0.1.0-SNAPSHOT"]
   ...
   ])
```

## Usage

Require thus:
```
(ns ...
  (:require 
     ...
    [sparql-client.core :refer :all]
    [igraph.core :refer :all]
    [vocabulary.core :as voc]
    ...
    ))
```

Then create the client thus:
```
(make-graph 
  :graph-uri <graph name> (optional)
  :query-url <query endpoint> 
  :update-url <update endpoint> (optional if read-only)
  :binding-translator <binding translator> (optional)
  :authentication <authentication> (as required by <update endpoint>)
  )

```

Where:
* `graph name` is a keyword representing the URI of the appropriate named graph.
  if unspecified, the DEFAULT graph will be assumed.
* `query-endpoint` is a string indicating the URL of a SPARQL query endpoint
* `binding-translator` is a function that takes the bindings returned in the standard SPARQL query response format, and returns a simplified key/value map.
* `update-endpoint` is a string indicating the URL of a SPARQL update query endpoint (or `nil`)
* `authentication` is a password (or `nil`)

This will produce a record that implements [ont-app/IGraph](https://github.com/ont-app/igraph). At present only the read-only accessor facilities are supported.

Keywords in any namespaces with the appropriate [Linked Open Data](https://en.wikipedia.org/wiki/Linked_data) (LOD) constructs described in [ont-app/vocabulary](https://github.com/ont-app/vocabulary) will be interpreted as [URI](https://en.wikipedia.org/wiki/Uniform_Resource_Identifier)s.


## Examples
    

Let's say we want to reference subjects in [Wikidata](https://www.wikidata.org/wiki/Wikidata:Main_Page). We can define the query endpoint...

```
(def wikidata-endpoint
  "https://query.wikidata.org/bigdata/namespace/wdq/sparql")
```


We can define a read-only SPARQL client to that endpoint...

```
(def client (make-graph :query-url wikidata-endpoint)) 
```

This will produce an instance of a SparqlClient

```
client
;; -> 
{:graph-uri nil,
 :query-url
 "https://query.wikidata.org/bigdata/namespace/wdq/sparql",
 :binding-translator
 {:uri #function[sparql-client.core/uri-translator],
  :lang #function[sparql-client.core/form-translator],
  :datatype #function[sparql-endpoint.core/parse-xsd-value]},
 :update-url nil,
 :auth nil}
```

Since it implements IGraph and Ifn, we can make calls like the following, describing let's say Barack Obama, whose Q-number in Wikidata happens to be Q76.

```
(client :wd/Q76) 
;; -> 
{:p/P4985 #{:wds/Q76-62b91a68-499a-47db-6786-87cdda9ff578},
 :rdfs/label
 #{:ugForm/باراك_ئوباما :mznForm/باراک_اوباما :pihForm/Barack_Obama
   :mkForm/Барак_Обама :nahForm/Barack_Obama :gvForm/Barack_Obama
   :nds-nlForm/Barack_Obama :urForm/بارک_اوباما :kaaForm/Barak_Obama
   ...
   }
 :wdt/P6385 #{"istoriya/OBAMA_BARAK_HUSEN.html"},
 :wdt/P4159 #{"Barack_Obama_(2)"},
 :p/P4515 #{:wds/Q76-b5be51e2-470e-138e-1401-3a66bfb71c53},
 ...
 )
```
This returns map with large number of wikidata properties indicated by rdfs:label links to a wide array of languages, and P-numbers which Wikidata uses to uniquely identify a wide array of relationships. See the Wikidata documentation for details.

Let's say we're just interested in the labels...

```
(client :wd/Q76 :rdfs/label)
;; ->
#{:ugForm/باراك_ئوباما :mznForm/باراک_اوباما :pihForm/Barack_Obama
   :mkForm/Барак_Обама :nahForm/Barack_Obama :gvForm/Barack_Obama
   :nds-nlForm/Barack_Obama :urForm/بارک_اوباما :kaaForm/Barak_Obama
   :en-caForm/Barack_Obama :asForm/বাৰাক_অ'বামা :rwForm/Barack_Obama
   :zuForm/Barack_Obama :tgForm/Барак_Ҳусейн_Обама
   :dsbForm/Barack_Obama :yiForm/באראק_אבאמא :brForm/Barack_Obama
   :anForm/Barack_Obama :orForm/ବରାକ_ଓବାମା :sr-ecForm/Барак_Обама
   :rmyForm/Barack_Obama :sr-elForm/Barak_Obama :bxrForm/Барак_Обама
   :uzForm/Barack_Obama :fiForm/Barack_Obama :myvForm/Обамань_Барак
   ...
}


```
This returns the set of labels associated with the former president.

```
(def barry-labels (client :wd/Q76 :rdfs/label)

;; English...
(filter (comp #(re-find #"^enForm" %) name) barry-labels)
;; ->
(:enForm/Barack_Obama)

;; Chinese ...
(filter (comp #(re-find #"^zhForm" %) name) barry-labels)
;; ->
(:zhForm/巴拉克·奧巴馬)

```

We can use a traversal function as the `p` argument (see IGraph docs
for a discussion of traversal functions) ...

```
^{:traversal-fn true
  :wd-equivalent "wdt:P31/wdt:P279*"
 }
(defn isa->subClassOf* [g context acc queue]
  "Traverses a single P31 link, then aggregates all P279 links, for every member of the queue. Returns also the context unchanged and an empty queue."
  [context
   (->> queue 
        (traverse g
                  (traverse-link :wdt/P31)
                  #{})
                         
        (traverse g
                  (transitive-closure :wdt/P279)
                  #{}))
   []])

;; Is Barry a human?...
(client :wd/Q76 isa->subClassOf* :wd/Q5)
->
:wd/Q5 ;; yep

```
### Querying


The native query format is of course SPARQL:

```
(def barry-query
    "
SELECT ?label
WHERE
{
  wd:Q76 rdfs:label ?label; 
  Filter (Lang(?label) = \"en\")
  }")
```

#### The `prefixed` function

If there are proper ont-app/vocabulary namespace declarations, we can
automatically assign prefixes to a query using the `prefixed`
function:

```
(println (prefixed barry-query))
;; ->
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
SELECT ?label
WHERE
{
  wd:Q76 rdfs:label ?label; 
  Filter (Lang(?label) = "en")
  }
```
This works because metadata has been assigned to the metadata of namespaces associated with `wd` and `rdfs` ...

```
(voc/ns-to-prefix) ;; Searches ns metadata for prefix declarations
;; ->
{
 ...
 "wd" #namespace[org.naturallexicon.lod.wikidata.wd],
  ...
 "rdfs" #namespace[org.naturallexicon.lod.rdf-schema],
 ...
}

(meta (find-ns 'org.naturallexicon.lod.wikidata.wd))
;; -> 
{:dc/title "Wikibase/EntityData",
 :foaf/homepage "https://www.mediawiki.org/wiki/Wikibase/EntityData",
 :vann/preferredNamespaceUri "http://www.wikidata.org/entity/",
 :vann/preferredNamespacePrefix "wd"}
 
(meta (find-ns 'org.naturallexicon.lod.rdf-schema))
;; ->
{:dc/title "The RDF Schema vocabulary (RDFS)",
 :vann/preferredNamespaceUri "http://www.w3.org/2000/01/rdf-schema#",
 :vann/preferredNamespacePrefix "rdfs",
 :foaf/homepage "https://www.w3.org/TR/rdf-schema/",
 :dcat/downloadURL "http://www.w3.org/2000/01/rdf-schema#",
 :voc/appendix
 [["http://www.w3.org/2000/01/rdf-schema#"
   :dcat/mediaType
   "text/turtle"]]}
 
```
The only annotations required to resolve prefixes appropriately are
the `:vann/preferredNamespaceUri` and `:vann/preferredNamespacePrefix`
annotations. See ont-app/vocabulary for more details about annotating
namespaces.

#### Binding translation

By default, bindings in the result set are simplified as follows:

* values tagged `xsd:type` (integers, time stamps, etc.) are parsed and interpreted
* URIs are interned as namespaced keywords using `ont-app/vocabulary` 
* values with language tags <lang> are interned as kewords of the form
  `:<lang>Form/<string>`, with whitespace translated to underscores,
  per namespaces defined in `vocabulary.linguistics`.  E.g. `"Barack
  Obama"@en` becomes `:enForm/Barack_Obama`.

See [ont-app/sparql-endpoint](https://github.com/ont-app/sparql-endpoint) for documentation on SPARQL binding simplification.
See [ont-app/vocabulary](https://github.com/ont-app/vocabulary) for documentation on how namespaces may be annotated with metadata to inform URI translations.


Given the above, we can query the client thus:
```
(query client (prefixed barry-query))

;; ->
({:label :enForm/Barack_Obama})

```

## Updating

In order to update a client, when making the grpah you must specify an :update-url parameter to an endpoint with a functioning update endpoint. There is an :auth parameter for handling passwords, but that is not yet implemented.

```
(def g (make-graph
        :graph-uri ::test-graph
        :query-url "localhost:3030/my_dataset/query"
        :update-url "localhost:3030/my_dataset/update"))

```
We add/subtract in the usual way:
```
(ns example-ns
  {
  :vann/preferredNamespacePrefix "eg"
  :vann/preferredNamespaceUri "http://rdf.example.org#"
  }
  (require ....)
)

(def g (make-graph...))

(normal-form (add g [[::A ::B ::C]]...))
;; ->
{:eg/A {:eg/B #{:eg/C}}}

(normal-form (subtract g [[::A]]...))
;;->
{}

```

Ordinary SPARQL updateS can also be posed:

```
(update-endoint g "DROP ALL") # careful now!
;; ->
"<html>\n<head>\n</head>\n<body>\n<h1>Success</h1>\n<p>\nUpdate succeeded\n</p>\n</body>\n</html>\n"

(g)
;; ->
{}
```


### Mutability
SPARQL endpoints are mutable databases, and so update operations are
destructive for this implementation of IGraph.

### Future work

* Add/subtract should accommodate triple specifications with any odd number of members. At present each triple must have only 3 members.
* Authorization tokens still need to be implemented.
* Literal object types are not infered and encoded with ^^xsd:* datatypes.
* There may yet be a reasonably performant way to deal with the
  (im)mutability issue.

## License

Copyright © 2019 Eric D. Scott

Distributed under the Eclipse Public License.
