(ns killbot.core
  (:require
    [killbot.discord]
    [killbot.zkb]
    [mount.core :as mount]))


(defn -main
  "Start the application"
  [] (mount/start))
