# sparql-client

Provides a view onto an arbitrary [SPARQL endpoint](https://github.com/ont-app/sparql-endpoint) using the [ont-app/IGraph](https://github.com/ont-app/igraph) protocol, and incoporating the [ont-app/vocabulary](https://github.com/ont-app/vocabulary) facility.

## Installation


Watch this space

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
    
(def client (->sparql-client <query-endpoint> 
                             <binding-translator> 
                             <update-endpoint> 
                             <authentication>)
```

Where:
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

and translate URIs and language-tagged strings into clojure keywords thus...
```
(require 'vocabulary.wikidata)
(require 'vocabulary.linguistics)

(defn- uri-translator
  "Returns <qualified-keyword> for `sparql-binding`
  Where
  <qualified-keyword> is a keyword in voc-re format
  <sparql-binding> := {?value ...}, typically returned from a query
  "
  [sparql-binding]
  (voc/keyword-for (sparql-binding "value"))
  )

(defn- form-translator [sparql-binding]
  "Returns a keyword for  `binding` as a keyword URI for a natlex form"
  (keyword (str (sparql-binding "xml:lang")
                "Form:"
                (s/replace (sparql-binding "value")
                           " "
                           "_"))))

```

Now we can update the default binding translator defined in `sparql-endpoint` to use these two translators...

```
(def translator (assoc endpoint/default-translators
                       :uri uri-translator
                       :lang form-translator))
```

    
This will continue to use xsd-value parser defined in [the sparql-endpoint module](https://github.com/ont-app/sparql-endpoint) for `:datatype` bindings, but render URIs as keywords interned in their associated namespaces as described in [the vocabulary module](https://github.com/ont-app/vocabulary). Language-tagged strings like `"Barack Obama"@en` will be translated to URI/keywords like `:enForm:Barack_Obama`, per namespaces defined in `vocabulary.linguistics`.

Now we can define the SPARQL client...

```
(def client (->SparqlClient wikidata-endpoint translator nil nil))
```

This will produce an instance of a SparqlClient

```
client
;; -> 
{:query-url
 "https://query.wikidata.org/bigdata/namespace/wdq/sparql",
 :binding-translator
 {:uri #function[sparql-client.core-test/uri-translator],
  :lang #function[sparql-client.core-test/form-translator],
  :datatype #function[sparql-endpoint.core/parse-xsd-value]},
 :update-url nil,
 :auth nil}

```

Since it implements IGraph and Ifn, we can make calls like the following, describing let's say Barack Obama, whose Q-number in Wikidata happens to be Q76.

```
(client :wd:Q76) 
;; -> 
{:p:P4985 #{:wds:Q76-62b91a68-499a-47db-6786-87cdda9ff578},
 :rdfs:label
 #{:ugForm:باراك_ئوباما :mznForm:باراک_اوباما :pihForm:Barack_Obama
   :mkForm:Барак_Обама :nahForm:Barack_Obama :gvForm:Barack_Obama
   :nds-nlForm:Barack_Obama :urForm:بارک_اوباما :kaaForm:Barak_Obama
   ...
   }
 :wdt:P6385 #{"istoriya/OBAMA_BARAK_HUSEN.html"},
 :wdt:P4159 #{"Barack_Obama_(2)"},
 :p:P4515 #{:wds:Q76-b5be51e2-470e-138e-1401-3a66bfb71c53},
 ...
 )
```
This returns map with large number of wikidata properties indicated by rdfs:label links to a wide array of languages, and P-numbers which Wikidata uses to uniquely identify a wide array of relationships. See the Wikidata documentation for details.

Let's say we're just interested in the labels...

```
(client :wd:Q76 :rdfs:label)
;; ->
#{:ugForm:باراك_ئوباما :mznForm:باراک_اوباما :pihForm:Barack_Obama
   :mkForm:Барак_Обама :nahForm:Barack_Obama :gvForm:Barack_Obama
   :nds-nlForm:Barack_Obama :urForm:بارک_اوباما :kaaForm:Barak_Obama
   :en-caForm:Barack_Obama :asForm:বাৰাক_অ'বামা :rwForm:Barack_Obama
   :zuForm:Barack_Obama :tgForm:Барак_Ҳусейн_Обама
   :dsbForm:Barack_Obama :yiForm:באראק_אבאמא :brForm:Barack_Obama
   :anForm:Barack_Obama :orForm:ବରାକ_ଓବାମା :sr-ecForm:Барак_Обама
   :rmyForm:Barack_Obama :sr-elForm:Barak_Obama :bxrForm:Барак_Обама
   :uzForm:Barack_Obama :fiForm:Barack_Obama :myvForm:Обамань_Барак
   ...
}


```
This returns the set of labels associated with the former president.

```
(def barry-labels (client :wd:Q76 :rdfs:label)

;; English...
(filter (comp #(re-find #"^enForm" %) name) barry-labels)
;; ->
(:enForm:Barack_Obama)

;; Chinese ...
(filter (comp #(re-find #"^zhForm" %) name) barry-labels)
;; ->
(:zhForm:巴拉克·奧巴馬)

```

Or we can just pose a SPARQL query directly...

```
(def barry-query
    "SELECT ?label
WHERE
{
  wd:Q76 rdfs:label ?label; 
  Filter (Lang(?label) = \"en\")
  }")

(println (voc/prepend-prefix-declarations barry-query))
;; ->
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
SELECT ?label
WHERE
{
  wd:Q76 rdfs:label ?label; 
  Filter (Lang(?label) = "en")
  }
nil

(query client (voc/prepend-prefix-declarations barry-query))

;; ->
({:label :enForm:Barack_Obama})

```

### Bugs

The update functions are not yet implemented.

...



## License

Copyright © 2019 Eric D. Scott

Distributed under the Eclipse Public License.
