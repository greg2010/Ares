(defproject killbot "0.2.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/tools.logging "0.4.0"]
                 [ch.qos.logback/logback-classic "1.2.3"]
                 [clj-http "3.8.0"]
                 [cheshire "5.8.0"]
                 [org.clojure/core.async "0.4.474"]
                 [mount "0.1.12"]
                 [org.clojure/tools.cli "0.3.6"]]
  :main ^:skip-aot killbot.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
