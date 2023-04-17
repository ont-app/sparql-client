# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).
# 0.2.2
  - Integrating changes from ont-app/rdf v. 0.3.2

# 0.2.1
- Fixing lib name in the build script

# 0.2.0
- project.clj -> deps.edn
- Adding support for bnode round-tripping
- Using ont-app/rdf I/O instead of v 0.1 I/O functions, which are deprecated
- Integrating test support from IGraph and RDF modules

# 0.1.1
- BREAKING: language tagged strings are now encoded with #langStr,
  rather than a raw string.
- BREAKING: all the main xsd datatypes are now interpreted; this includes most
  standard scalar datatypes (long, double, #inst, ...)
- BREAKING: non-xsd datatypes are reified as objects with .asString
  and metadata, rather than a raw string.
- Adding a regime to handle a datatype encoded as ^^transit:json.
- Vectors, maps, and seqs are encoded/decoded as transit:json.
- Added a load-rdf-file method for uri, path, or string.
- Auth arguments to the endpoint are now supported
- Added a check-ns-metadata function, called in check-qname. This will
  issue a warning if a kwi is being interned in a namespace with no
  metadata.
- Blank nodes and LangStr's are now properly supported in add! operations.
- There is now a customizable `render-literal` method
- There is a function `property-path`, which takes a SPARQL property
  path string and return a traversal function suitable as a `p`
  argument to accessor functions.
