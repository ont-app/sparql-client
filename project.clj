(defproject ont-app/sparql-client "0.1.1-SNAPSHOT"
  :description "Provides an IGraph-compliant view onto an arbitrary SPARQL endpoint in clojure, informed by ont-app/vocabulary."
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 ;; 3rd party 
                 [selmer "1.12.18"]
                 [com.taoensso/timbre "4.10.0"]
                 [org.clojure/tools.reader "1.3.2"]
                 ;; ont-app
                 [ont-app/graph-log "0.1.1-SNAPSHOT"]
                 [ont-app/igraph "0.1.5-SNAPSHOT"]
                 [ont-app/sparql-endpoint "0.1.2-SNAPSHOT"]
                 [ont-app/vocabulary "0.1.1-SNAPSHOT"]
                 ]
  :target-path "target/%s"
  :plugins [[lein-codox "0.10.6"]]
  :codox {:output-path "doc"}
  :profiles {:uberjar {}})
