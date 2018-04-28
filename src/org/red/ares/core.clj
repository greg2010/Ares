(ns org.red.ares.core
  (:require
    [org.red.ares.config]
    [org.red.ares.discord]
    [org.red.ares.zkb]
    [org.red.ares.esi]
    [org.red.ares.router]
    [mount.core :as mount]
    [clojure.tools.logging :as log]
    [clojure.java.io :as io]
    [clojure.tools.cli :as cli])
  (:gen-class))



(def cli-options
  ;; An option with a required argument
  [["-c" "--config CONFIG" "Config"
    :default {}
    :parse-fn #(clojure.edn/read-string (slurp (io/file %)))]])

(defn -main
  "Start the application"
  [& args]
  (let [signal (java.util.concurrent.CountDownLatch. 1)]
    (log/info "Starting Ares...")
    (mount/start-with-args (cli/parse-opts args cli-options))
    (log/info "All Ares services have started...")
    (.await signal)))