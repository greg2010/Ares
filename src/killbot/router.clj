(ns killbot.router
  (:require [com.stuartsierra.component :as component]
            [clojure.core.async
             :refer [>! <! >!! <!! go chan buffer close! thread
                     alts! alts!! timeout pipeline]]))

(comment
(defrecord Router [zkb discord]
  component/Lifecycle
  (start [component]
    (let [zkb-discord (pipeline 16 (:mailbox discord) #((let [_ (println %)] %)) (:mailbox zkb))]
      (-> component
          (assoc :zkb-discord zkb-discord))))
  (stop [component]
    (close! (:zkb-discord component))))

(defn new-router [zkb discord]
  (map->Router {zkb discord})))