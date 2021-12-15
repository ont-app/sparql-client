(defproject ont-app/sparql-client "0.1.1-SNAPSHOT"
  :description "Provides an IGraph-compliant view onto an arbitrary SPARQL endpoint in clojure, informed by ont-app/vocabulary."
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [;; deps disambiguation
                 [commons-codec "1.15"]
                 [com.fasterxml.jackson.core/jackson-core "2.13.0"]
                 [com.fasterxml.jackson.core/jackson-databind "2.13.0"]
                 ;; clojure
                 [org.clojure/clojure "1.10.3"]
                 [org.clojure/spec.alpha "0.3.218"]
                 [org.clojure/core.async "1.5.648"]
                 ;; 3rd party
                 [cheshire "5.10.1"]
                 [com.cognitect/transit-clj "1.0.324"]
                 [com.taoensso/timbre "5.1.2"]
                 [selmer "1.12.45"]
                 ;; ont-app
                 [ont-app/graph-log "0.1.5-SNAPSHOT"]
                 [ont-app/igraph "0.1.8-SNAPSHOT"]
                 [ont-app/sparql-endpoint "0.1.4-SNAPSHOT"]
                 [ont-app/vocabulary "0.1.5-SNAPSHOT"]
                 ]
  :target-path "target/%s"
  :plugins [[lein-codox "0.10.6"]]
  :codox {:output-path "doc"}
  :profiles {:uberjar {}}
  :test {:resource-paths ["test/resources"]}
  )
