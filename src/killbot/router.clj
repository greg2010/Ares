(ns killbot.router
  (:require [mount.core :as mount]
            [killbot.config :refer [config]]
            [killbot.zkb :refer [zkb]]
            [killbot.esi :refer [esi]]
            [killbot.discord :refer [discord]]
            [clojure.core.async
             :refer [>! <! >!! <!! go go-loop chan buffer close! thread
                     alts! alts!! timeout pipeline]]
            [killbot.util :refer :all]
            [clojure.tools.logging :as log]))

(defn- relevant?
  ([km-package destination]
   (let [victim-char-id (get-in km-package [:killmail :victim :character_id])
         victim-corp-id (get-in km-package [:killmail :victim :corporation_id])
         victim-alliance-id (get-in km-package [:killmail :victim :alliance_id])
         attackers-char-ids (vec (map :character_id (get-in km-package [:killmail :attackers])))
         attackers-corp-ids (vec (map :corporation_id (get-in km-package [:killmail :attackers])))
         attackers-alliance-ids (vec (map :alliance_id (get-in km-package [:killmail :attackers])))]
     (not (nil?
            (or
              (get-in destination [:relevant-entities :all])
              (some (set (get-in destination [:relevant-entities :region-ids]))
                    [(get-in km-package [:killmail :region_id])])
              (some (set (get-in destination [:relevant-entities :character-ids]))
                    (flatten (conj [victim-char-id] attackers-char-ids)))
              (some (set (get-in destination [:relevant-entities :corporation-ids]))
                    (flatten (conj [victim-corp-id] attackers-corp-ids)))
              (some (set (get-in destination [:relevant-entities :alliance-ids]))
                    (flatten (conj [victim-alliance-id] attackers-alliance-ids)))))))))


(defn- km-package-router [km-package]
  (let
    [destinations (filter #(relevant? km-package %) (:destinations config))]
    (when (not (empty? destinations))
      (log/debug "Determined that package with id "
                 (:killID km-package)
                 " goes to destinations with names"
                 (map #(str " " (get-in % [:discord-wh :bot-name])) destinations))
      (merge km-package {:destinations destinations}))))


(defn- km-package-router-transducer [xf]
  (fn
    ([] (xf))
    ([result] (xf result))
    ([result input] (let [output (km-package-router input)]
                      (if
                        (not (nil? output))
                        (xf result output)
                        (xf result))))))

(defn- log-route! [from to id]
  (when (not (nil? id))
    (log/debug "Routed package from " from " to " to " with ID " id)))


(defn- router-logger-transducer [from to xf]
  (fn
    ([] (xf))
    ([result] (xf result))
    ([result input] (xf result (do (log-route! from to (:killID input)) input)))))

(defn start []
  (let [zkb-esi-pipeline (pipeline
                           (:worker-count discord)
                           (:in esi)
                           (partial router-logger-transducer "zkb" "esi")
                           (:out zkb))
        esi-discord-pipeline (pipeline
                               (:worker-count discord)
                               (:in discord)
                               (comp km-package-router-transducer (partial router-logger-transducer "esi" "discord"))
                               (:out esi))]
    (log/info "Starting router component")
    {:zkb-esi-pipeline     zkb-esi-pipeline
     :esi-discord-pipeline esi-discord-pipeline}))


(defn stop [router]
  (log/info "Stopping router component")
  (close! (:zkb-esi-pipeline router))
  (close! (:esi-discord-pipeline router)))

(mount/defstate router
                :start (start)
                :stop (stop esi))