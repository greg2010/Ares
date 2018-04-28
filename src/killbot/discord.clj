(ns killbot.discord
  (:require [clj-http.client :as client]
            [mount.core :as mount]
            [clojure.core.async
             :refer [>! <! >!! <!! go chan buffer close! thread
                     alts! alts!! timeout]]
            [clojure.tools.logging :as log]
            [cheshire.core :refer :all]
            [killbot.util :refer :all]
            [killbot.config :refer [config]]))

(def ^{:private true} discord-vars {:base-url        "https://zkillboard.com/kill"
                                    :img-eve-baseurl "http://imageserver.eveonline.com"
                                    :colors          {
                                                      :red   (long 0x990000)
                                                      :green (long 0x009900)
                                                      }})



(defn- get-final-blow [km-package] (first (filter #(:final_blow %) (get-in km-package [:killmail :attackers]))))


(defn- generate-title [victim solar-system-id names]

  (cond
    (nil? (:character_id victim)) (format "%s | %s | %s"
                                          (names (:corporation_id victim))
                                          (names (:ship_type_id victim))
                                          (names solar-system-id))
    :else (format "%s | %s | %s"
                  (names (:character_id victim))
                  (names (:ship_type_id victim))
                  (names solar-system-id))))

(defn- generate-footer [km-package names]
  (let [final-blow (get-final-blow km-package)
        entity-character (if
                           (nil? (:character_id final-blow))
                           (:corporation_id final-blow)
                           (:character_id final-blow))
        entity-group (if
                       (nil? (:alliance_id final-blow))
                       (:corporation_id final-blow)
                       (:alliance_id final-blow))
        invloved-count (count (get-in km-package [:killmail :attackers]))]
    (cond (nil? final-blow) nil
          (nil? (:character_id final-blow)) (format "Final blow by %s in %s (%d involved)"
                                                    (names entity-character)
                                                    (names (:ship_type_id final-blow))
                                                    invloved-count)
          :else (format "Final blow by %s (%s) in %s (%d involved)"
                        (names entity-character)
                        (names entity-group)
                        (names (:ship_type_id final-blow))
                        invloved-count))))


(defn- generate-url [km-package]
  (str (:base-url discord-vars) "/" (:killID km-package)))

(defn- generate-image-by-id [id] (str (:img-eve-baseurl discord-vars) "/type/" id "_64.png"))


(defn generate-embed [km-package]
  (let [names (:names km-package)
        victim (get-in km-package [:killmail :victim])
        solar-system-id (get-in km-package [:killmail :solar_system_id])
        region-id (get-in km-package [:killmail :region_id])
        title (generate-title victim solar-system-id names)
        url (generate-url km-package)
        thumbnail-url (generate-image-by-id (:ship_type_id victim))
        timestamp (get-in km-package [:killmail :killmail_time])
        footer (generate-footer km-package names)
        footer-thumbnail (generate-image-by-id (:ship_type_id (get-final-blow km-package)))]
    {:title     title
     :url       url
     :thumbnail {:url thumbnail-url}
     :timestamp timestamp
     :color     (if (:friendly km-package) (get-in discord-vars [:colors :red]) (get-in discord-vars [:colors :green]))
     :fields    (filter #(not (nil? %))
                        [(if (contains? victim :character_id)
                           {
                            :name   "Character"
                            :value  (names (:character_id victim))
                            :inline true
                            })
                         {
                          :name   "Corporation"
                          :value  (names (:corporation_id victim))
                          :inline true
                          }
                         (if (contains? victim :alliance_id)
                           {
                            :name   "Alliance"
                            :value  (names (:alliance_id victim))
                            :inline true
                            })
                         {
                          :name   "Ship"
                          :value  (names (:ship_type_id victim))
                          :inline true
                          }
                         {
                          :name   "Location"
                          :value  (str (names solar-system-id) " | "
                                       (names region-id))
                          :inline true
                          }
                         {
                          :name   "Total Value"
                          :value  (format "%,.2f ISK" (double (if (not (nil? (get-in km-package [:zkb :totalValue])))
                                                                (get-in km-package [:zkb :totalValue])
                                                                0)))
                          :inline true
                          }])

     :footer    {
                 :text     footer
                 :icon_url footer-thumbnail
                 }
     }))

(defn- post-to-wh! [dest data] (client/post
                                 (get-in dest [:discord-wh :url])
                                 {:form-params  (merge data
                                                       {:username (get-in dest [:discord-wh :bot-name])}
                                                       {:avatar_url (get-in dest [:discord-wh :bot-picture])})
                                  :content-type :json}))

(defn post-embed! [dest embed] (post-to-wh! dest {:embeds [embed]}))

(defn- process*! [dest km-package]
  (try
    (log/debug (str "Processing km package " (:killID km-package)))
    (post-embed! dest (generate-embed km-package))
    (log/debug (str "Processed km package " (:killID km-package)))
    (catch Exception e (payload-http-exception-handler e km-package (partial process*! dest) 1000))))


(defn- chan-process! [dest mailbox] (while true (process*! dest (<!! mailbox))))



(defn- pull-and-route! [mailbox channel-map]
  (while true
    (let
      [km-package (<!! mailbox)
       send-to (select-keys channel-map (map #(get-in % [:discord-wh :url]) (:destinations km-package)))]
      (doseq [[_ ch] send-to] (>!! (:channel ch) km-package)))))


(defn- channel-map-generator [workers-per-channel]
  (into {}
        (map
          #(hash-map
             (get-in % [:discord-wh :url])
             {
              :destination %
              :thread-pool (get-thread-pool workers-per-channel)
              :channel     (chan workers-per-channel)
              })
          (:destinations config))))

(defn start []
  (let [workers-per-channel 4
        worker-count (* workers-per-channel (count (:destinations config)))
        in (chan worker-count)
        channel-map (channel-map-generator workers-per-channel)
        router-thread-pool (get-thread-pool worker-count)]
    (log/info "Starting discord component")
    (doseq
      [[_ channel-with-tp] channel-map]
      (log/info "Starting discord worker job")
      (submit-to-thread-pool (:thread-pool channel-with-tp)
                             (fn [] (chan-process! (:destination channel-with-tp) (:channel channel-with-tp)))
                             workers-per-channel))
    (log/info "Starting discord router job")
    (submit-to-thread-pool router-thread-pool (fn [] (pull-and-route! in channel-map)) worker-count)
    {:worker-count worker-count :in in :rp router-thread-pool :cm channel-map}))

(defn stop [discord]
  (log/info "Stopping discord component")
  (doseq [c (:cm discord)]
    (close! (:channel c))
    (shutdown-thread-pool (:thread-pool c)))
  (shutdown-thread-pool (:rp discord)))

(mount/defstate discord
                :start (start)
                :stop (stop discord))