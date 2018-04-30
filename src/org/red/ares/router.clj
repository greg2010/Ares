(ns org.red.ares.router
  (:require [mount.core :as mount]
            [org.red.ares.config :refer [config]]
            [org.red.ares.zkb :refer [zkb]]
            [org.red.ares.esi :refer [esi]]
            [org.red.ares.discord :refer [discord]]
            [org.red.ares.util :refer :all]
            [clojure.core.async
             :refer [>! <! >!! <!! go go-loop chan buffer close! thread
                     alts! alts!! timeout pipeline]]
            [clojure.tools.logging :as log]))

(defn relevant?
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
                    (flatten (conj attackers-char-ids victim-char-id)))
              (some (set (get-in destination [:relevant-entities :corporation-ids]))
                    (flatten (conj attackers-corp-ids victim-corp-id)))
              (some (set (get-in destination [:relevant-entities :alliance-ids]))
                    (flatten (conj attackers-alliance-ids victim-alliance-id)))))))))


(defn friendly? [km-package destination]
  (let [char-id (get-in km-package [:killmail :victim :character_id])
        corp-id (get-in km-package [:killmail :victim :corporation_id])
        alliance-id (get-in km-package [:killmail :victim :alliance_id])]
    (not (nil?
           (or (some #(= char-id %) (get-in destination [:relevant-entities :character-ids]))
               (some #(= corp-id %) (get-in destination [:relevant-entities :corporation-ids]))
               (some #(= alliance-id %) (get-in destination [:relevant-entities :alliance-ids])))))))


(defn- km-package-router [km-package]
  (let
    [destinations (filter #(relevant? km-package %) (:destinations config))
     friendly (into {}
                    (map #(hash-map (get-in % [:discord-wh :url]) (friendly? km-package %)) destinations))]
    (when (not (empty? destinations))
      (log/debug "Determined that package with id "
                 (:killID km-package)
                 " goes to destinations with names"
                 (map #(str " " (get-in % [:discord-wh :bot-name])) destinations))
      (merge km-package {:destinations destinations} {:friendlies friendly}))))


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