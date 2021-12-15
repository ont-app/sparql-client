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
                 ;; 3rd party
                 [com.cognitect/transit-clj "1.0.324"]
                 [selmer "1.12.45"]
                 ;; ont-app
                 [ont-app/graph-log "0.1.5-SNAPSHOT"
                  :exclusions [[org.clojure/clojurescript]
                               ]
                  ]
                 [ont-app/sparql-endpoint "0.1.4-SNAPSHOT"]
                 ]
  :target-path "target/%s"
  :plugins [[lein-codox "0.10.6"]]
  :codox {:output-path "doc"}
  :profiles {:uberjar {}}
  :test {:resource-paths ["test/resources"]}
  )
