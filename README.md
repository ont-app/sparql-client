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
      - [The `datatype-translator` binding translator](#h5-the-datatype-translator-binding-translator)
      - [The `^^transit:json` datatype tag](#h5-the-transit-json-datatype-tag)
      - [The `render-literal` multimethod](#h5-the-render-literal-multimethod)
      - [Blank nodes](#h5-blank-nodes)
      - [SPARQL property paths](#h5-sparql-property-paths)
- [sparql-updater](#h2-sparql-updater)
  - [load-rdf-file](#h3-load-rdf-file)
- [Future work](#h2-future-work)
- [Acknowledgements](#h2-acknowledgements)


<a name="h2-installation"></a>
## Installation

[![Clojars Project](https://img.shields.io/clojars/v/ont-app/sparql-client.svg)](https://clojars.org/ont-app/sparql-client)

Additional documentation is available at https://cljdoc.org/d/ont-app/sparql-client/0.1.0 .

```
(defproject ...
  :dependencies 
  [...
   [ont-app/sparql-client "0.1.1-SNAPSHOT"]
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
    [ont-app.sparql-client.core :as client]
    [ont-app.igraph.core :refer :as igraph :refer :all]
    [ont-app.vocabulary.core :as voc]
    ...
    ))
```

Then create sparql-reader thus:

```
(client/make-sparql-reader
  :graph-uri <graph name> (optional, defaulting to DEFAULT)
  :query-url <query endpoint> 
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
  simplified key/value map. (defaults are discussed [below](#h4-binding-translation))
- `authentication` is map with {_auth-key_ _auth-value_}, interpreted per [clj-http's authentication scheme](https://github.com/dakrone/clj-http#authentication)

Such graphs will give you a means to view the contents of a read-only SPARQL
endpoint using the `IGraph` protocol to access members of the graph.

You can create a sparql-updater thus:

```
(client/make-sparql-updater
  :graph-uri <graph name> (optional, defaulting to DEFAULT)
  :query-url <query endpoint> 
  :update-url <update endpoint> 
  :binding-translator <binding translator> (optional)
  :authentication <authentication> (as required by the endpoint)
  )

```
This has the same parameters as the the _sparql-reader_, plus:

- `update-endpoint` is a string indicating the URL of a SPARQL update
  query endpoint (sparql-updater only)

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

Access functions are all part of the IGraph protocol.

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
 #{#langStr "Barack Obama@jv" #langStr "贝拉克·奥巴马@zh-my"
   #langStr "Barack Obama@ga" #langStr "ബറാക്ക് ഒബാമ@ml"
   #langStr "Barack Obama@map-bms" #langStr "ბარაკ ობამა@ka"
   ...
   }
 :wdt/P6385 #{"istoriya/OBAMA_BARAK_HUSEN.html"},
 :wdt/P4159 #{"Barack_Obama_(2)"},
 :p/P4515 #{:wds/Q76-b5be51e2-470e-138e-1401-3a66bfb71c53},
 ...
 )
```

This returns map with large number of wikidata properties indicated by
rdfs:label links to a wide array of languages, and P-numbers which
Wikidata uses to uniquely identify a wide array of relationships. See
the Wikidata documentation for details.

Let's say we're just interested in the labels...

```
(client :wd/Q76 :rdfs/label)
;; ->
#{{#langStr "Barack Obama@jv" #langStr "贝拉克·奥巴马@zh-my"
  #langStr "Barack Obama@ga" #langStr "ബറാക്ക് ഒബാമ@ml"
  #langStr "Barack Obama@map-bms" #langStr "ბარაკ ობამა@ka"
  #langStr "Barack Obama@se" #langStr "贝拉克·奥巴马@zh-cn"
  #langStr "Барак Обама@ru" #langStr "巴拉克·歐巴馬@zh-tw"
  #langStr "Barack Obama@mt" #langStr "באראק אבאמא@yi"
  #langStr "বাৰাক অ'বামা@as" #langStr "𐌱𐌰𐌹𐍂𐌰𐌺 𐍉𐌱𐌰𐌼𐌰@got"
  #langStr "Барак Ҳусейн Обама@tg" #langStr "Barack Obama@tet"
  #langStr "Barack Obama@lt" #langStr "Barack Obama@lfn"
  #langStr "বারাক ওবামা@bn" #langStr "Barack Obama@ay"
   ...
}
```

This returns the set of language-tagged labels associated with the
former president. (See documentation of the _sparql-endpoint_ module for discussion of the  #langStr reader macro).

```
> (def barry-labels (client :wd/Q76 :rdfs/label)]
> ;; English...
> (filter #(re-find #"^en$" (endpoint/lang %)) barry-labels)
(#langStr "Barack Obama@en")
>
> ;; Chinese ...
> (filter #(re-find #"^zh$" (endpoint/lang %)) barry-labels)
(#langStr "巴拉克·奧巴馬@zh")
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
annotations. See
[ont-app/vocabulary](https://github.com/ont-app/vocabulary) for more
details about annotating namespaces.

<a name="h4-binding-translation"></a>
#### Binding translation

The call to the SPARQL endpoint is handled through the
[sparql-endpoint](https://github.com/ont-app/sparql-endpoint) library,
which simplifies standard SPARQL bindings using a set of _simplifiers_
keyed to each type of binding. By default, bindings in the result set
are simplified as follows:

| key | description | default |
|-----|-------------|---------|
| :uri | Value is a URI | interned as namespaced keywords using `ont-app/vocabulary` |
| :lang | Value is literal with language tag like @en | encoded as `LangStr` instances, e.g. `#langStr "Barack Obama@en"`. |
| :datatype | Value is a datatype | Interpret with [datatype-translator](#h5-the-datatype-translator-binding-translator) function |
| :bnode | Value is a blank node | Encode as described [below](#h5-blank-nodes). |

See
[ont-app/sparql-endpoint](https://github.com/ont-app/sparql-endpoint)
for documentation on SPARQL binding simplification.

See [ont-app/vocabulary](https://github.com/ont-app/vocabulary) for
documentation on how namespaces may be annotated with metadata to
inform URI translations.

Given the above, we can query the client thus:

```
(query client (prefixed barry-query))

;; ->
({:label #langStr "Barack Obama@en"})

```
<a name="h5-the-datatype-translator-binding-translator"></a>
##### The `datatype-translator` binding translator

This is the default _:datatype_ binding translator, with the following
behavior:

- xsd types will be interpreted as the usual scalar values
- literal objects with ^^transit:json tags will be supported as
  described [below](#h5-the-transit-json-datatype-tag).
- otherwise a
  [meta-tagged-literal](https://github.com/ont-app/sparql-endpoint/tree/develop#meta-tagged-literal)
  will be returned.

See also the discussion below about the
[#h5-the-render-literal-multimethod](h5-the-render-literal-multimethod)
method.

<a name="h5-the-transit-json-datatype-tag"></a>
##### The `^^transit:json` datatype tag

Literals may be tagged with the URI whose qname is `transit:json`
(corresponding to
http://rdf.naturallexicon.org/ns/cognitect.transit#json), indicating
that the value is to be encoded/decoded via the text-based JSON
[transit format](https://github.com/cognitect/transit-format). 


Vectors, Maps and Seqs are encoded as literals bearing that tag,
allowing them to be stored and retrieved transparently:

```
> (add! g 
    [:eg/TransitExample
     :eg/vector [1 2 3]
     :eg/map {:a "eh"}
     :eg/seq '(fn [] "hello")
     ])
> (unique (g :eg/TransitExample :eg/vector))
[1 2 3]
> (unique (g :eg/TransitExample :eg/map))
{:a "eh"}
> (unique (g :eg/TransitExample :eg/seq))
(fn [] "hello")
> 
```

Here's what's going on behind the scenes.

The RDF tagging is done with _render-literal-as-transit-json_:

```
> (render-literal-as-transit-json [1 2 3])
"\"[1,2,3]\"^^transit:json"
> 
``` 

Values encoded in this way will automatically be decoded when reading
the query response. When it does so, it calls _read-transit-json_:

```
> (client/read-transit-json "[1,2,3]")
[1 2 3]
>
```

Quotation marks are escaped with `&quot;`:

```
> (client/render-literal-as-transit-json #{1 2 3})
"\"[&quot;~#set&quot;,[1,3,2]]\"^^transit:json"
>
> (client/read-transit-json 
    (clojure.string/replace "[&quot;~#set&quot;,[1,3,2]]" #"&quot;" "\"" ))
#{1 3 2}
>
```

The standard clojure data structures should already be handled
by transit. Custom datatypes need to have handlers defined in the atoms
`client/transit-read-handlers` and `client/transit-write-handlers`.

Here's the transit-write-handler for LangStr:

```
(ns ...
 (:import
  ...
  [ont_app.sparql_endpoint.core LangStr]))
 ...
 
   {...
    LangStr
    (cognitect.transit/write-handler
     "ont_app.sparql_endpoint.core.LangStr"
     (fn [ls]
       {:tag (.tag ls)
        :s (.s ls)
        }))
    ...
    }
```
... and the corresponding transit-read-handler:

```
{...
 "ont_app.sparql_endpoint.core.LangStr"
    (cognitect.transit/read-handler
     (fn [from-rep]
       (endpoint/->LangStr (:s from-rep) (:tag from-rep))))
 ...
 }
```

These are both atoms, so you should be able to add your own handlers:

```
(swap! client/transit-write-handlers (assoc <MyClass> <my-handler>))
```

Several _render-literal_ methods (described in the next section) use
this transit-based approach.


<a name="h5-the-render-literal-multimethod"></a>
##### The `render-literal` multimethod

Any graph element represented as a keyword is assumed to be a KWI, and
all non-keyword graph elements are assumed to be literals. When
generating queries or UPDATE directives, literals are translated using
the `render-literal` multimethod, dispatched on the output of
`render-literal-dispatch`, which breaks out _#inst_'s and _xsd-type_s
as special cases, and otherwise returns (type-of _literal_).

The method for LangStr renders `#langStr "cat@en"` as `"\"cat\"@en"`.

Otherwise default behavior is to render the value in quotes, using the
`quote-str` function := `["str"] -> "\"str\""`.

It should be straightforward to write homo-iconic custom records as RDF:

```
> (defmethod render-literal (type (make-my-record))
   [record]
   (str (quote-str (str record)) "^^" (voc/qname-for :myNs/MyRecord)))
```

You would then need to write a custom _:datatype_ simplifier (described above
and in
[ont-app/sparql-endpoint](https://github.com/ont-app/sparql-endpoint))
to re-interpret them as compiled objects.

And of course, the _transit_ regime described above may prove useful here.

The default render-literal method for vectors is :

```
(defmethod render-literal (type [])
  [v]
  (render-literal-as-transit-json v))
```

As described above, the original value of the vector will be decoded
and read in when retrieved in a query. This approach stands in
contrast to the more canonical RDF-based approach of representing
vectors as blank nodes whose members are marked off with `rdf:_1,
rdf:_2, ...`. If you prefer that approach, you may wish to re-define
the render-literal method for vectors accordingly.

Contents of vectors may still be searchable with regular expressions
against the transit representations, but most of the use cases I have
in mind expect these vectors as the payload for searches keyed on
metadata located elsewhere in the graph.

Transit-based encoding is specified also for literal maps and seqs.

<a name="h5-blank-nodes"></a>
##### Blank nodes

Supporting RDF-based representations requires support of [blank
nodes](https://www.wikidata.org/wiki/Q3427875). 

Reading blank nodes from SPARQL results by default is done by ???,
which produces a KWI interned in a namespace bound to the hash of the
graph. There is no metadata bound to this namespace.

Each blank node KWI matches the function `bnode-kwi?`, and spec `::bnode-kwi`.

These blank nodes will be rendered when we translate the graph into
normal form, but there are limits to its effectiveness in identifying
the original blank node in the SPARQL endpoint, since blank nodes are
only really valid within the scope of a single query or update directive.

Thus we could use the following expression to define an OWL definition
for `EnglishForm`, which is a language form guaranteed to be in
English, conforming to the OWL standard requiring that [Restrictions
must be expressed using blank nodes](https://www.w3.org/TR/owl-ref/#Restrictions):

```
> (add! lexicon
        [[:en/EnglishForm
          :rdfs/subClassof :ontolex/Form
          :rdfs/subClassof :_/InEnglish]
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

So in cases where you intend to make use of blank nodes, you may want
to make use of the `property-path` traversal function descussed
[below](#h5-sparql-property-paths), or simply use the `query-endpoint`
function with a fully specified SPARQL query.

```
> (query-endpoint lexicon 
  (prefixed 
   "SELECT ?p ?o
    WHERE
    { 
      Graph uptest:owl-demo
      { 
        en:EnglishForm rdfs:subClassOf ?restriction.
        ?restriction ?p ?o.
      }
     }"
     ))
({:p :rdf/type, :o :owl/Restriction}
 {:p :owl/onProperty, :o :dct/language}
 {:p :owl/hasValue, :o :iso639/eng}) 
>
```

If you're among those who try to minimize or eliminate any use of
blank nodes, be advised that the
[mint-kwi](https://cljdoc.org/d/ont-app/igraph-vocabulary/0.1.0/api/ont-app.igraph-vocabulary.core#mint-kwi)
method is offered as an alternative.

<a name="h5-sparql-property-paths"></a>
##### SPARQL property paths

One of the nice features of SPARQL is its support for [property
paths](https://www.w3.org/TR/sparql11-property-paths/), which inspired
many of igraph's traversal utilities such as _transitive-closure_.

The function `property-path` takes a string expressing a SPARQL
property path, and returns a traversal function that applies it.

For example in the blank nodes example above:

```
> (lexicon :en/EnglishForm (property-path "rdfs:subClassOf/owl:hasValue"))
#{:iso639/eng}
> 
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

<a name="h3-load-rdf-file"></a>
### `load-rdf-file`

The multimethod `load-rdf-file` takes an updater and a path, and loads
that path into the graph associated with the graph. The path can be a string, a java File, or a java URI object. Returned on success is the URI for the path.

```
> (load-rdf-file g "test/resources/dummy.ttl")
INFO - Loading <yadda/yadda/test/resources/dummy.ttl> into uptest:dummy-test
#object[java.net.URI 0x15ff92e4 "file://yadda/yadda/test/resources/dummy.ttl"]
> 
```

<a name="h2-acknowledgements"></a>
## Acknowledgements

Thanks to [Abdullah Ibrahim](https://github.com/aibrahim) for his
feedback and advice.

## License

Copyright © 2019-20 Eric D. Scott

Distributed under the Eclipse Public License.
