# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

# 0.1.1
- BREAKING: language tagged strings are now encoded with #langStr
- BREAKING: xsd datatypes are now interpreted; this includes most
  standard scalar datatypes (long, double, #inst, ...)
- BREAKING: non-xsd datatypes are reified as objects with .asString
  and metadata
- Added a load-rdf-file method for uri, path, or string.
- Auth arguments to the endpoint are now supported
- Added a check-ns-metadata function, called in check-qname. This will
  issue a warning if a kwi is being interned in a namespace with no
  metadata.
