(ns org.red.ares.config
  (:require
    [mount.core :as mount]
    [clojure.tools.logging :as log]))

(defn start [conf]
  (log/info "Starting config component")
  (log/debug "Config map: " (get-in conf [:options :config]))
  (get-in conf [:options :config]))


(defn stop [] ())

(mount/defstate config
                :start (start (mount/args))
                :stop (stop))
