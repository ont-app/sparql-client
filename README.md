# sparql-client

Provides a view onto an arbitrary [SPARQL
endpoint](https://github.com/ont-app/sparql-endpoint) using the
[ont-app/IGraph](https://github.com/ont-app/igraph) protocol, and
incoporating the
[ont-app/vocabulary](https://github.com/ont-app/vocabulary) facility.

This revolves around two defrecords: `sparql-reader` for read-only
access to a public server, and `sparql-updater` for updating a mutable graph.

## Contents
- [Installation](#h2-installation)
- [Usage](#h2-usage)
- [Member access (both reader and updater)](#h2-member-access)
  - [Querying](#h3-querying)
    - [The `prefixed` function, and namespace metadata](#h4-the-prefixed-function)
    - [Binding translation](#h4-binding-translation)
- [sparql-updater](#h2-sparql-updater)
- [Future work](#h2-future-work)


<a name="h2-installation"></a>
## Installation

[![Clojars Project](https://img.shields.io/clojars/v/ont-app/sparql-client.svg)](https://clojars.org/ont-app/sparql-client)

Additional documentation is available at https://cljdoc.org/d/ont-app/sparql-client/0.1.0 .

```
(defproject ...
  :dependencies 
  [...
   [ont-app/sparql-client "0.1.0-SNAPSHOT"]
   ...
   ])
```

<a name="h2-usage"></a>
## Usage

Require thus:
```
(ns ...
  (:require 
     ...
    [ont-app.sparql-client.core :refer :all]
    [ont-app.igraph.core :refer :all]
    [ont-app.vocabulary.core :as voc]
    ...
    ))
```

Then create sparql-reader thus:

```
(make-sparql-reader
  :graph-uri <graph name> (optional, defaulting to DEFAULT)
  :query-url <query endpoint> 
  :binding-translator <binding translator> (optional)
  :authentication <authentication> (as required by the endpoint)
  )

```

Such graphs will give you to view the contents of a read-only SPARQL
endpoint using the `IGraph` protocol to access members of the graph.

Then create sparql-updater thus:

```
(make-sparql-updater
  :graph-uri <graph name> (optional, defaulting to DEFAULT)
  :query-url <query endpoint> 
  :update-url <update endpoint> 
  :binding-translator <binding translator> (optional)
  :authentication <authentication> (as required by the endpoint)
  )
```

Where:
- `graph name` is a keyword representing the URI of the appropriate
  named graph.  if unspecified, the DEFAULT graph will be assumed.
- `query-endpoint` is a string indicating the URL of a SPARQL query
  endpoint
- `binding-translator` is a function that takes the bindings returned
  in the standard SPARQL query response format, and returns a
  simplified key/value map.
- `update-endpoint` is a string indicating the URL of a SPARQL update
  query endpoint (sparql-updater only)
- `authentication` is a password (as needed; defaults to  `nil`)

Each of these will produce a record that implements
[ont-app/IGraph](https://github.com/ont-app/igraph).

Keywords in any namespaces with the appropriate [Linked Open
Data](https://en.wikipedia.org/wiki/Linked_data) (LOD) constructs
described in
[ont-app/vocabulary](https://github.com/ont-app/vocabulary) will be
interpreted as
[URI](https://en.wikipedia.org/wiki/Uniform_Resource_Identifier)s.


<a name="h2-member-access"></a>
## Member access (both reader and updater)

Let's say we want to reference subjects in
[Wikidata](https://www.wikidata.org/wiki/Wikidata:Main_Page). We can
define the query endpoint...

```
(def wikidata-endpoint
  "https://query.wikidata.org/bigdata/namespace/wdq/sparql")
```

We can define a read-only SPARQL client to that endpoint...

```
(def client (make-sparql-reader :query-url wikidata-endpoint)) 
```

This will produce an instance of a SparqlReader

```
client
;; -> 
{:graph-uri nil,
 :query-url
 "https://query.wikidata.org/bigdata/namespace/wdq/sparql",
 :binding-translator
 {:uri #function[ont-app.sparql-client.core/uri-translator],
  :lang #function[ont-app.sparql-client.core/form-translator],
  :datatype #function[ont-app.sparql-endpoint.core/parse-xsd-value],
  :bnode #function[clojure.core/partial/fn--5826]},
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
> (def barry-labels (client :wd/Q76 :rdfs/label)]
> ;; English...
> (filter (comp #(re-find #"^enForm" (namespace %))) barry-labels)
(:enForm/Barack_Obama)
>
> ;; Chinese ...
> (filter (comp #(re-find #"^zhForm" (namespace %))) barry-labels)
(:zhForm/巴拉克·奧巴馬)
>
```

We can use a traversal function as the `p` argument (see IGraph docs
for a discussion of traversal functions) ...

```
> (def instance-of (t-comp [:wdt/P31 (transitive-closure :wdt/P279)]))
instance-of
> ;; Is Barry a human?...
> (client :wd/Q76 instance-of :wd/Q5)
:wd/Q5 ;; yep
>
```

<a name="h3-querying"></a>
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

<a name="h4-the-prefixed-function"></a>
#### The `prefixed` function, and namespace metadata

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
> (voc/prefix-to-ns)
{
 ...
 "wd" #namespace[org.naturallexicon.lod.wikidata.wd],
  ...
 "rdfs" #namespace[org.naturallexicon.lod.rdf-schema],
 ...
}
> (meta (find-ns 'org.naturallexicon.lod.wikidata.wd))
{:dc/title "Wikibase/EntityData",
 :foaf/homepage "https://www.mediawiki.org/wiki/Wikibase/EntityData",
 :vann/preferredNamespaceUri "http://www.wikidata.org/entity/",
 :vann/preferredNamespacePrefix "wd"}
> 
> (meta (find-ns 'org.naturallexicon.lod.rdf-schema))
{:dc/title "The RDF Schema vocabulary (RDFS)",
 :vann/preferredNamespaceUri "http://www.w3.org/2000/01/rdf-schema#",
 :vann/preferredNamespacePrefix "rdfs",
 :foaf/homepage "https://www.w3.org/TR/rdf-schema/",
 :dcat/downloadURL "http://www.w3.org/2000/01/rdf-schema#",
 :voc/appendix
 [["http://www.w3.org/2000/01/rdf-schema#"
   :dcat/mediaType
   "text/turtle"]]}
> 
```

The only annotations required to resolve prefixes appropriately are
the `:vann/preferredNamespaceUri` and `:vann/preferredNamespacePrefix`
annotations. See ont-app/vocabulary for more details about annotating
namespaces.

<a name="h4-binding-translation"></a>
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

<a name="h2-sparql-updater"></a>
## sparql-updater

SPARQL endpoints are mutable databases, and so update operations are
destructive.

When you have access to a SPARQL update endpoint, we use
`make-sparql-updater`:

```
(def g (make-sparql-updater
        :graph-uri ::test-graph
        :query-url "localhost:3030/my_dataset/query"
        :update-url "localhost:3030/my_dataset/update"))

```

This has the same parameters as `make-sparql-reader`, plus an
`:update-url` parameter.

This implements the [IGraphMutable](https://github.com/ont-app/igraph/tree/develop#IGraphMutable) protocol, with methods `add!` and `subtract!`:

```
(ns example-ns
  {
  :vann/preferredNamespacePrefix "eg"
  :vann/preferredNamespaceUri "http://rdf.example.org#"
  }
  (require ....)
)

(def g (make-sparql-updater ...))

(normal-form (add! g [[::A ::B ::C]]...))
;; ->
{:eg/A {:eg/B #{:eg/C}}}

(normal-form (subtract! g [[::A]]...))
;;->
{}

```


Ordinary SPARQL updates can also be posed:

```
(update-endoint g "DROP ALL") # careful now!
;; ->
"<html>\n<head>\n</head>\n<body>\n<h1>Success</h1>\n<p>\nUpdate succeeded\n</p>\n</body>\n</html>\n"

(g)
;; ->
{}
```

<a name="h2-future-work"></a>
## Future work

* Authorization tokens still need to be implemented.
* Literal object types are not infered and encoded with ^^xsd:* datatypes.

## License

Copyright © 2019 Eric D. Scott

Distributed under the Eclipse Public License.
