(defproject ont-app/sparql-client "0.1.0-SNAPSHOT"
  :description "Provides an IGraph-compliant view onto an arbitrary SPARQL endpoint, informed by ont-app/vocabulary"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [selmer "1.11.8"]
                 ;;
                 [com.taoensso/timbre "4.10.0"]
                 [org.clojure/tools.reader "1.2.2"]
                 ;; ... see https://github.com/ptaoussanis/timbre/issues/263
                 ;; included per docs in slf4j-timbre...
                 ;;[org.slf4j/log4j-over-slf4j "1.7.14"]
                 ;;[org.slf4j/jul-to-slf4j "1.7.14"]
                 ;;[org.slf4j/jcl-over-slf4j "1.7.14"]
                 ;; ont-app
                 [ont-app/graph-log "0.1.0-SNAPSHOT"]
                 [ont-app/igraph "0.1.4-SNAPSHOT"]
                 [ont-app/sparql-endpoint "0.1.1-SNAPSHOT"]
                 [ont-app/vocabulary "0.1.0-SNAPSHOT"]
                 
                 ]
  :main ^:skip-aot sparql-client.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
