(defproject ont-app/sparql-client "0.1.1-SNAPSHOT"
  :description "Provides an IGraph-compliant view onto an arbitrary SPARQL endpoint in clojure, informed by ont-app/vocabulary."
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [;; deps disambiguation
                 [commons-codec "1.13"]
                 [org.apache.httpcomponents/httpcore "4.4.12"]
                 [org.apache.httpcomponents/httpclient "4.5.10"]
                 [org.apache.httpcomponents/httpclient-cache "4.5.10"
                  :exclusions [commons-logging]]
                 ;; clojure
                 [org.clojure/clojure "1.10.1"]
                 [org.clojure/spec.alpha "0.2.176"]
                 [org.clojure/tools.reader "1.3.2"]
                 [org.clojure/core.async "1.2.603"]
                 ;; 3rd party
                 [cheshire "5.10.0"]
                 [com.cognitect/transit-clj "1.0.324"]
                 [com.taoensso/timbre "4.11.0-alpha1"]
                 [selmer "1.12.23"]
                 ;; ont-app
                 [ont-app/graph-log "0.1.2"]
                 [ont-app/igraph "0.1.5"]
                 [ont-app/sparql-endpoint "0.1.2-SNAPSHOT"]
                 [ont-app/vocabulary "0.1.2"]
                 ]
  :target-path "target/%s"
  :plugins [[lein-codox "0.10.6"]]
  :codox {:output-path "doc"}
  :profiles {:uberjar {}}
  :test {:resource-paths ["test/resources"]}
  )
