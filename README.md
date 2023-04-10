# <img src="http://ericdscott.com/NaturalLexiconLogo.png" alt="NaturalLexicon logo" :width=100 height=100/> ont-app/sparql-client

Provides a view onto an arbitrary [SPARQL
endpoint](https://github.com/ont-app/sparql-endpoint) using the
[ont-app/IGraph](https://github.com/ont-app/igraph) protocol, and
incoporating the
[ont-app/vocabulary](https://github.com/ont-app/vocabulary) facility.

This revolves around two defrecords: `sparql-reader` for read-only
access to a public server, and `sparql-updater` for updating a mutable graph.

## Contents
- [Installation](#installation)
- [Basic usage](#basic-usage)
  - [make-sparql-reader](#make-sparql-reader)
  - [make-sparql-updater](#make-sparql-updater)
- [Member access (both reader and updater)](#member-access)
  - [Querying](#querying)
    - [The `prefixed` function, and namespace metadata](#the-prefixed-function)
    - [SPARQL binding simplifiers](#sparql-binding-simplifiers)
      - [default-binding-translators](#default-binding-translators)
    - [Blank nodes](#blank-nodes)
      - [property path](#property-path)
      - [Blank node round-tripping support](#round-tripping)
        - [decode-bnode-kwi-name](#decode-bnode-kwi-name)
- [sparql-updater](#sparql-updater)
  - [update-endpoint!](#update-endpoint)
  - [drop-client!](#drop-client)
- [I/O](#i-o)
  - [standard-write-context](#standard-write-context)
  - [standard-read-context](#standard-read-context)
  - [create-load-context](#create-load-context)
- [Acknowledgements](#acknowledgements)


## Installation

This is deployed to [clojars](https://clojars.org/ont-app/sparql-client):

[![Clojars Project](https://img.shields.io/clojars/v/ont-app/sparql-client.svg)](https://clojars.org/ont-app/sparql-client)

Dependencies can be declared in the usual way using your favorite deps tool.

## Basic usage

Require thus:
```
(ns ...
  (:require 
     ...
    [ont-app.igraph.core :refer :as igraph :refer :all]
    [ont-app.vocabulary.core :as voc]
    [ont-app.rdf.core :as rdf]
    [ont-app.sparql-client.core :as client]
    ...
    ))
```

Generally speaking, we create an instance of the client by providing
endpoints, graph names and perhaps authoriazation specs, then use
[IGraph accessor
functions](https://github.com/ont-app/igraph#Member_access) as we
would with other IGraph implementations. Endpoints may differ in
whether or not they provide update capabilities, and the two records
`SparqlReader` and `SparqlUpdater` cover these cases.

Keywords in any namespaces with the appropriate [Linked Open
Data](https://en.wikipedia.org/wiki/Linked_data) (LOD) constructs
described in
[ont-app/vocabulary](https://github.com/ont-app/vocabulary) will be
interpreted as
[URI](https://en.wikipedia.org/wiki/Uniform_Resource_Identifier)s.


### `make-sparql-reader`

Then create a sparql-reader thus:

```
(client/make-sparql-reader
  :graph-uri <graph name> (optional, defaulting to nil=DEFAULT)
  :query-url <query endpoint> 
  :authentication <authentication> (as required by the endpoint)
  :binding-translator <binding translator> (optional)
  )

```
Where:
- `graph name` is a keyword representing the URI of the appropriate
  named graph.  Defaults to nil whereby the DEFAULT graph will be assumed.
- `query-endpoint` is a string indicating the URL of a SPARQL query
  endpoint
- `authentication` is a map with {_auth-key_ _auth-value_}, interpreted per [clj-http's authentication scheme](https://github.com/dakrone/clj-http#authentication)
- `binding-translator` is a function that takes the bindings returned
  in the standard SPARQL query response format, and returns a
  simplified key/value map. This uses reasonable defaults for most
  cases. See [below](#binding-translation) for a discussion of how
  to override them.

Such graphs will give you a means to view the contents of a read-only SPARQL
endpoint using the `IGraph` protocol to access members of the graph.

You may want to enable bnode round-tripping support as discussed [below](#round-tripping).

### `make-sparql-updater`

You can create a sparql-updater thus:

```
(client/make-sparql-updater
  :graph-uri <graph name> (optional, defaulting to DEFAULT)
  :query-url <query endpoint> 
  :update-url <update endpoint> 
  :authentication <authentication> (as required by the endpoint)
  :binding-translator <binding translator> (optional)
  )

```
This has the same parameters as the the _sparql-reader_, plus:

- `update-url` is a string indicating the URL of a SPARQL update query
  endpoint

You may want to enable bnode round-tripping support as discussed
[below](#round-tripping).



<a name="member-access"></a>
## Member access (both reader and updater)

Access functions are all part of the
[IGraph](https://github.com/ont-app/igraph) protocol.

Let's say we want to reference data published in
[Wikidata](https://www.wikidata.org/wiki/Wikidata:Main_Page). We can
define the query endpoint thus...

```
(require '[ont-app.vocabulary.wikidata :as wd])
;; This brings in metadata to inform `ont-app/vocabulary` of wikidata namespacess

(def wikidata-endpoint wd/sparql-endpoint)
;; "https://query.wikidata.org/bigdata/namespace/wdq/sparql"
```

and define a read-only SPARQL client to that endpoint...

```
(def wd-client (make-sparql-reader :query-url wikidata-endpoint)) 
```

This will produce an instance of a SparqlReader

```
wd-client
;; -> 
{:graph-uri nil,
 :query-url
 "https://query.wikidata.org/bigdata/namespace/wdq/sparql",
 :binding-translator
 {:uri #function[ont-app.sparql-client.core/uri-translator],
  :lang #function[ont-app.sparql-client.core/form-translator],
  :datatype #function[ont-app.sparql-endpoint.core/parse-xsd-value],
  :bnode #function[clojure.core/partial/fn--5826]},
 :auth nil
 :bnodes nil
 }
```

Since it implements IGraph and Ifn, we can make calls like the
following, describing let's say Barack Obama, whose Q-number in
Wikidata happens to be Q76.

```
(wd-client :wd/Q76) 
;; -> 
{:p/P4985 #{:wds/Q76-62b91a68-499a-47db-6786-87cdda9ff578},
 :rdfs/label
 #{#voc/lstr "Barack Obama@jv" #voc/lstr "贝拉克·奥巴马@zh-my"
   #voc/lstr "Barack Obama@ga" #voc/lstr "ബറാക്ക് ഒബാമ@ml"
   #voc/lstr "Barack Obama@map-bms" #voc/lstr "ბარაკ ობამა@ka"
   ...
   }
 :wdt/P6385 #{"istoriya/OBAMA_BARAK_HUSEN.html"},
 :wdt/P4159 #{"Barack_Obama_(2)"},
 :p/P4515 #{:wds/Q76-b5be51e2-470e-138e-1401-3a66bfb71c53},
 ...
 )
```

This returns a map with large number of wikidata properties indicated
by rdfs:label links to many languages, and
[P-numbers](https://www.wikidata.org/wiki/Wikidata:Database_reports/List_of_properties/all)
which Wikidata uses to uniquely identify a wide array of
relationships. See the [Wikidata
documentation](https://www.wikidata.org/wiki/Wikidata:Introduction)
for details.

Let's say we're just interested in the labels. We we add another argument....

```
(wd-client :wd/Q76 :rdfs/label)
;; ->
#{{#voc/lstr "Barack Obama@jv" #voc/lstr "贝拉克·奥巴马@zh-my"
  #voc/lstr "Barack Obama@ga" #voc/lstr "ബറാക്ക് ഒബാമ@ml"
  #voc/lstr "Barack Obama@map-bms" #voc/lstr "ბარაკ ობამა@ka"
  #voc/lstr "Barack Obama@se" #voc/lstr "贝拉克·奥巴马@zh-cn"
  #voc/lstr "Барак Обама@ru" #voc/lstr "巴拉克·歐巴馬@zh-tw"
  #voc/lstr "Barack Obama@mt" #voc/lstr "באראק אבאמא@yi"
  #voc/lstr "বাৰাক অ'বামা@as" #voc/lstr "𐌱𐌰𐌹𐍂𐌰𐌺 𐍉𐌱𐌰𐌼𐌰@got"
  #voc/lstr "Барак Ҳусейн Обама@tg" #voc/lstr "Barack Obama@tet"
  #voc/lstr "Barack Obama@lt" #voc/lstr "Barack Obama@lfn"
  #voc/lstr "বারাক ওবামা@bn" #voc/lstr "Barack Obama@ay"
   ...
}
```

This returns the set of language-tagged labels associated with the
former president. (See documentation of the [vocabulary
module](https://github.com/ont-app/vocabulary#h2-language-tagged-strings)
for discussion of the #voc/lstr reader tag).

```
> (def barry-labels (wd-client :wd/Q76 :rdfs/label)]
> ;; English...
> (filter #(re-find #"^en$" (lang %)) barry-labels)
(#voc/lstr "Barack Obama@en")
>
> ;; Chinese ...
> (filter #(re-find #"^zh$" (lang %)) barry-labels)
(#voc/lstr "巴拉克·奧巴馬@zh")
>
```

We can use a [traversal
function](https://github.com/ont-app/igraph#Traversal) as the `p`
argument ...

```clojure
> (def instance-of (property-path "wdt:P31/wdt:P279*"))
;; this is the WD equivalent of rdf:type/rdfs/subClassOf*
> (wd-client :wd/Q76 instance-of )
#{:wd/Q110551885
  :wd/Q5
  ...
  :wd/Q159344}
```

Or get a truthy response with 3 arguments...

```clojure
> ;; Is Barry a human?...
> (wd-client :wd/Q76 instance-of :wd/Q5)
:wd/Q5 ;; yep
>
```

See [below](#property-path) for a discussion of the `property-path` function.


### Querying

The native query format is of course SPARQL. Let's use this as an example:

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


<a name="the-prefixed-function"></a>
#### The `prefixed` function, and namespace metadata

If there are proper
[ont-app/vocabulary](https://github.com/ont-app/igraph-vocabulary)
namespace declarations, we can automatically assign prefixes to a
query using the `prefixed` function:

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

This works because metadata has been assigned to the metadata of
namespaces associated with `wd` and `rdfs` ...

```
> (require '[ont-app/vocabulary :as voc])
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
annotations. See
[ont-app/vocabulary](https://github.com/ont-app/vocabulary) for more
details about annotating namespaces.

#### SPARQL binding simplifiers

The call to the SPARQL endpoint is handled through the
[sparql-endpoint](https://github.com/ont-app/sparql-endpoint) library,
which simplifies standard SPARQL bindings using a set of
[_simplifiers_](https://github.com/ont-app/sparql-endpoint#h2-simplifiers)
keyed to each type of binding.

This library defines a reasonable set of simplifiers for SPARQL
results. URIs are translated to keyword identifiers (KWIs), language
tags are translated to `voc/lstr` reader tags, XSD dtatypes are
interpreted as the appropriate clojure values, and bnodes are interned
into keywords in graph-specific namespaces.  These defaults can be
overridden as described in the documentation for `sparql-endpoint`,
working off of `default-binding-translators`.

##### `default-binding-translators`

This function returns a map of the default SPARQL binding translators used by `sparql-client`. You can merge with a map of overriding translators as needed:

```clojure
> (core/default-binding-translators "http://my/endpoint/" "http://my/graph/name")
{:uri #function[ont-app.sparql-client.core/uri-translator],
 :lang #function[ont-app.sparql-endpoint.core/literal->LangStr],
 :datatype #function[ont-app.sparql-client.core/datatype-translator],
 :bnode #function[clojure.core/partial/fn--5910]}
```

The endoint and graph name are needed to generate unique bnode namespaces.

#### Blank nodes

Supporting RDF-based representations requires support of [blank
nodes](https://www.wikidata.org/wiki/Q3427875). 

Reading blank nodes from SPARQL results by default is done by `(:bnode
default-binding-translators)` which produces a KWI interned in a
namespace bound to the hash of the graph. There is no metadata bound
to this namespace.

Each blank node KWI matches the function
`rdf/bnode-kwi?`, and spec `::bnode-kwi`.

These blank nodes will be rendered when we translate the graph into
normal form, but there are limits to its effectiveness in identifying
the original blank node in the SPARQL endpoint, since blank nodes are
only really valid within the scope of a single query.

Thus we could use the following expression to define in Clojure an OWL
definition for `EnglishForm`, which is a language form whose
`dct:language` is `iso639:eng` conforming to the OWL standard
requiring that [Restrictions must be expressed using blank
nodes](https://www.w3.org/TR/owl-ref/#Restrictions):

```
> (add! lexicon
        [[:en/EnglishForm
          :rdfs/subClassOf :ontolex/Form
          :rdfs/subClassOf :_/InEnglish]
          [:_/InEnglish 
           :rdf/type :owl/Restriction
           :owl/onProperty :dct/language
           :owl/hasValue :iso639/eng]])

> (lexicon)
{...
  :en/EnglishForm
     #:rdfs{:subClassOf #{:ontolex/Form :_-1352721862/b0}},
  :_-1352721862/b0
    {:rdf/type #{:owl/Restriction},
     :owl/onProperty #{:dct/language},
     :owl/hasValue #{:iso639/eng}},
  ...
>
}
```
But this makes accessor functions against blank nodes problematic:

```
> (lexicon :en/EnglishForm)
#:rdfs{:subClassOf #{:ontolex/Form :_-1352721862/b0}}
>
> (lexicon  :_-1352721862/b0)
--> ERROR
> 
```

So in cases where you intend to make use of blank nodes, we provide
the `property-path` traversal function descussed
[below](#sparql-property-paths), or you can use the round-tripping
support facility discussed [below](#round-tripping)


##### `property path`

One of the nice features of SPARQL is its support for [property
paths](https://www.w3.org/TR/sparql11-property-paths/), which inspired
many of igraph's traversal utilities such as
[_transitive-closure_](https://github.com/ont-app/igraph#h4-transitive-closure).

The function `property-path` takes a string expressing a SPARQL
property path, and returns a traversal function that applies it, which
can be used in 'p' position in IGraph accessor functions.

For example in the blank nodes example above:

```
> (lexicon :en/EnglishForm (property-path "rdfs:subClassOf/owl:hasValue"))
#{:iso639/eng}
> 
```

`(property-path "rdfs:subClassOf/owl:hasValue")` is equivalent to
`(t-comp [:rdfs/subClassOf :owl/hasValue])`, but the latter would
require hitting the endpoint with two separate queries, while the
former executes this logic in one hop on the server side.

<a name=round-tripping></a>
##### Bnode round-tripping support

Let's say we have the following contents in `test/resources/jack.ttl`, which we've loaded
into a client 'jack':

```turtle
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>.
@base <http://rdf.naturallexicon.org/ont-app/sparql-client/test>.

@prefix : <#>.

:Jack
    a :Person ;
    :built _:house .

_:house a :House .

[
        a :Dog ;
        rdfs:label "The dog that chased the cat that ate the mouse that lived in the house that Jack built." ;
        :chased [
            a :Cat ;
            :ate [
                a :Mouse ;
                :livedIn _:house ;
            ] ;
        ] ;
].
```

We would initialize and load it thus (see [below](#i-o) for discussion of I/O):

```clojure
(require '[clojure.java.io :as io])
(require '[ont-app.igraph.core :as igraph :refer :all])
(require '[ont-app.rdf.core :as rdf])
(require '[ont-app.sparql-client.core :refer :all])

(def load-context (partial create-load-context "http://path/to/endpoint/query" "http://path/to/endpoint/update"))

(def jack (rdf/load-rdf (load-context ::jack-graph) (io/resource "jack.ttl")))
```

Then we could access the sparql-updater `jack` with IGraph access
functions

```clojure
> (subjects jack)
(:sparql-client-test/Jack
 :_-615919603/b_39653
 :_-615919603/b_39654
 :_-615919603/b_39655
 :_-615919603/b_39656)
>
```

So we have the KWI for Jack, but all the other subjects are blank nodes for the dog, cat, mouse and house. Which is which? We don't know, and can't query to find out.

Some RDF stores like
[Jena](https://www.reddit.com/r/semanticweb/comments/qke4gu/til_how_to_roundtrip_blank_nodes_in_jenafuseki/)
provide for platform specific ways to round-trip bnodes, but there is
no way that I know of to do this across SPARQL implementations.


This makes it hard to work with bnodes in a REPL.

So `sparql-client` provides a way to annotate blank nodes in such a
way that bnodes can be round-trippable.

```clojure
> (def round-trippable-jack (core/reset-annotation-graph jack))
```

Now when we look for subjects, each of the bnodes is rendered in such a way that it contains a description of the node, which in the vast majority of cases can be used to retrieve that same node in a follow-up query:

```
> (subjects round-trippable-jack)

(:sparql-client-test/Jack
 :_-615919603/%5Brdf:type%20sparql-client-test:Cat%3B%20sparql-client-test:ate%20%5Brdf:type%20sparql-client-test:Mouse%3B%20sparql-client-test:livedIn%20%5Brdf:type%20sparql-client-test:House%3B%20%5Esparql-client-test:built%20sparql-client-test:Jack%5D%5D%3B%20%5Esparql-client-test:chased%20%5Brdf:type%20sparql-client-test:Dog%5D%5D
 :_-615919603/%5Brdf:type%20sparql-client-test:Dog%3B%20sparql-client-test:chased%20%5Brdf:type%20sparql-client-test:Cat%3B%20sparql-client-test:ate%20%5Brdf:type%20sparql-client-test:Mouse%3B%20sparql-client-test:livedIn%20%5Brdf:type%20sparql-client-test:House%3B%20%5Esparql-client-test:built%20sparql-client-test:Jack%5D%5D%5D%5D
 :_-615919603/%5Brdf:type%20sparql-client-test:House%3B%20%5Esparql-client-test:built%20sparql-client-test:Jack%3B%20%5Esparql-client-test:livedIn%20%5Brdf:type%20sparql-client-test:Mouse%3B%20%5Esparql-client-test:ate%20%5Brdf:type%20sparql-client-test:Cat%3B%20%5Esparql-client-test:chased%20%5Brdf:type%20sparql-client-test:Dog%5D%5D%5D%5D
 :_-615919603/%5Brdf:type%20sparql-client-test:Mouse%3B%20sparql-client-test:livedIn%20%5Brdf:type%20sparql-client-test:House%3B%20%5Esparql-client-test:built%20sparql-client-test:Jack%5D%3B%20%5Esparql-client-test:ate%20%5Brdf:type%20sparql-client-test:Cat%3B%20%5Esparql-client-test:chased%20%5Brdf:type%20sparql-client-test:Dog%5D%5D%5D)
>
```

Decoding and reformatting the name of the bnode for the "cat" would look like this:

```turtle
[rdf:type sparql-client-test:Cat; 
 sparql-client-test:ate [
   rdf:type sparql-client-test:Mouse; 
   sparql-client-test:livedIn [
   rdf:type sparql-client-test:House; 
   ^sparql-client-test:built sparql-client-test:Jack
   ]]; 
  ^sparql-client-test:chased [
    rdf:type sparql-client-test:Dog
]]
```
... which could be inserted directly into a SPARQL query to address
the node in question.

And _sparql-client_ can interpret such bnodes:

```clojure
> (round-trippable-jack :_-615919603/%5Brdf:type%20sparql-client-test:Cat%3B%20sparql-client-test:ate%20%5Brdf:type%20sparql-client-test:Mouse%3B%20sparql-client-test:livedIn%20%5Brdf:type%20sparql-client-test:House%3B%20%5Esparql-client-test:built%20sparql-client-test:Jack%5D%5D%3B%20%5Esparql-client-test:chased%20%5Brdf:type%20sparql-client-test:Dog%5D%5D)

{:rdf/type #{:sparql-client-test/Cat},
 :sparql-client-test/ate
 #{:_-615919603/%5Brdf:type%20sparql-client-test:Mouse%3B%20sparql-client-test:livedIn%20%5Brdf:type%20sparql-client-test:House%3B%20%5Esparql-client-test:built%20sparql-client-test:Jack%5D%3B%20%5Esparql-client-test:ate%20%5Brdf:type%20sparql-client-test:Cat%3B%20%5Esparql-client-test:chased%20%5Brdf:type%20sparql-client-test:Dog%5D%5D%5D}}
```

So this is another option that may make it easier to work with bnodes,
especially in a REPL.

Some caveats:
- This works by querying the client graph for all triples in that
  graph involving a bnode, and building an annotation model for each
  of these. This will work if the bnodes are limited to a tractable
  number. If you're working with a very large graph where bnodes are
  used willy-nilly, you will need substantial memory resources for
  this to work.
- This should work in the vast majority of cases, but there may be a
  few gotchas waiting in the wings for cases where these descriptions
  will retrieve more than one bnode.
  
The main use case for this I think is working with bnodes in the REPL,
or if you're implementing [IGraph traversal
functions](https://github.com/ont-app/igraph#Traversal). When you get
down to production and have sussed out all your use cases, it may make
sense to write tailored queries, but hopefully this feature made it a
bit easier to do so.

###### `decode-bnode-kwi-name`

This funciton yields the string for the bnode KWIs described above:

```clojure
> (core/decode-bnode-kwi-name :_-615919603/%5Brdf:type%20sparql-client-test:Cat%3B%20sparql-client-test:ate%20%5Brdf:type%20sparql-client-test:Mouse%3B%20sparql-client-test:livedIn%20%5Brdf:type%20sparql-client-test:House%3B%20%5Esparql-client-test:built%20sparql-client-test:Jack%5D%5D%3B%20%5Esparql-client-test:chased%20%5Brdf:type%20sparql-client-test:Dog%5D%5D)

"[rdf:type sparql-client-test:Cat; sparql-client-test:ate [rdf:type sparql-client-test:Mouse; sparql-client-test:livedIn [rdf:type sparql-client-test:House; ^sparql-client-test:built sparql-client-test:Jack]]; ^sparql-client-test:chased [rdf:type sparql-client-test:Dog]]"
>
```

## sparql-updater

SPARQL endpoints are mutable databases, and so update operations are
destructive.

When you have access to a SPARQL update endpoint, we use
`make-sparql-updater`:

```clojure
(def g (make-sparql-updater
        :graph-uri ::test-graph
        :query-url "localhost:3030/my_dataset/query"
        :update-url "localhost:3030/my_dataset/update"))

```

This has the same parameters as `make-sparql-reader`, plus an
`:update-url` parameter.

This implements the [IGraphMutable](https://github.com/ont-app/igraph/tree/develop#IGraphMutable) protocol, with methods `add!` and `subtract!`:

```clojure
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

You can also create an updater with the
[rdf/load-rdf](https://github.com/ont-app/rdf#load-rdf) method as
discussed [below](#create-load-context).


### `update-endpoint!`

Ordinary SPARQL updates can also be posed:

```clojure
(update-endoint! g "DROP ALL") # careful now!
;; ->
"<html>\n<head>\n</head>\n<body>\n<h1>Success</h1>\n<p>\nUpdate succeeded\n</p>\n</body>\n</html>\n"

(g)
;; ->
{}
```

### `drop-client!`

You can drop the named graph associated with a client with `drop-client!`.

```clojure
> (drop-client! my-client)
(my-client)
{}
```

## I/O

Reading and writing RDF should be done using methods defined in the [ont-app/rdf](https://github.com/ont-app/rdf#i-o) library. The first argument for each of these methods relies on a [context](https://github.com/ont-app/rdf#the-context-graph) argument.

### `standard-write-context`

This can be provided as the "context" argument in a call to `rdf/write-rdf`

```clojure
> (def write-client (partial rdf/write-rdf standard-write-context))
> (write-client my-client (io/file "/tmp/my-client.ttl") :formats/Turtle)
#object[java.io.File 0x15a0727 "/tmp/my-client.ttl"]
```

### `standard-read-context`

This can be provided as the "context" argument in a call to
`rdf/read-rdf`, which will read the specified source into an existing
update client.

```clojure
> (def read-rdf! (partial rdf/read-rdf standard-read-context))
> (read-rdf! my-client (io/file "/tmp/my-data.ttl"))
{:graph-uri ..., ...}
```

<a name=create-load-context>
### `create-load-context`

This returns a value that can be provided as the "context" argument in
a call to `rdf/read-rdf`.

```clojure
> (def load-context (partial-create-load-context "http://path/to/query" "http://path/to/update"))
> (def my-client (rdf/load-rdf (load-context ::my-graph) (io/file "/tmp/my-client-data.ttl")))
my-client
```

## Miscellaneous utilities

### `kwi-for`

When the atom `warn-on-no-metadata-for-kwi?` is reset to `true`, a
warning will be issued if a URI is provided for which there is no
namespace declaration.

```clojure
> (reset! warn-on-no-ns-metadata-for-kwi? true)
> (core/kwi-for "http://no-namespace/blah")
2023-04-09T15:52:29.996Z eric-Bonobo-Extreme WARN [ont-app.sparql-client.core:?] - No ns metadata found for http://no-namespace/blah
:http:%2F%2Fno-namespace%2Fblah
```


### `quote-str`

Escapes quotes.

```clojure
(quote-str "blah")
"\"blah\""
```

### `count-subjects`

`(count-subjects <client>) submits a SPARQL query to count the number
of subjects for a client. Which may be a good way to gauge the size of
the graph at that endpoint.

## Testing

Functions which update a SPARQL endpoint will naturally need access to an endpoint into which testing data can be loaded. 

For all tests to be run, the environment variable
`ONT_APP_TEST_UPDATE_ENDPOINT` should be set, and point to a live
SPARQL endpoint with update privileges. If that endpoint requires
authentication, sparql-client will expect `ONT_APP_TEST_UPDATE_AUTH`
to be specified to a string of EDN readable as an
[`http-req`](https://github.com/ont-app/sparql-endpoint#h3-optional-argument-http-req)
paremeter, e.g `{:basic-auth "myuserName:myPassword"}`.

Failure to find live update endpoints will cause a number of tests to be skipped, but should not raise an exception.

## Acknowledgements

Thanks to [Abdullah Ibrahim](https://github.com/aibrahim) for his
feedback and advice.

## License

Copyright © 2019-23 Eric D. Scott

Distributed under the Eclipse Public License.
<table>
<tr>
<td width=75>
<img src="http://ericdscott.com/NaturalLexiconLogo.png" alt="Natural Lexicon logo" :width=50 height=50/> </td>
<td>
<p>Natural Lexicon logo - Copyright © 2020 Eric D. Scott. Artwork by Athena M. Scott.</p>
<p>Released under <a href="https://creativecommons.org/licenses/by-sa/4.0/">Creative Commons Attribution-ShareAlike 4.0 International license</a>. Under the terms of this license, if you display this logo or derivates thereof, you must include an attribution to the original source, with a link to https://github.com/ont-app, or  http://ericdscott.com. </p> 
</td>
</tr>
<table>
