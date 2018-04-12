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



(defn get-final-blow [km-package] (first (filter #(:final_blow %) (get-in km-package [:killmail :attackers]))))


(defn generate-title [victim solar-system-id names]

  (cond
    (nil? (:character_id victim)) (format "%s | %s | %s"
                                          (names (:corporation_id victim))
                                          (names (:ship_type_id victim))
                                          (names solar-system-id))
    :else (format "%s | %s | %s"
                  (names (:character_id victim))
                  (names (:ship_type_id victim))
                  (names solar-system-id))))

(defn generate-footer [km-package names]
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


(defn generate-url [km-package]
  (str (:base-url discord-vars) "/" (:killID km-package)))

(defn generate-image-by-id [id] (str (:img-eve-baseurl discord-vars) "/type/" id "_64.png"))


(defn generate-embed [km-package]
  (let [names (:names km-package)
        victim (get-in km-package [:killmail :victim])
        solar-system-id (get-in km-package [:killmail :solar_system_id])
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
                          :name   "Solar System"
                          :value  (names solar-system-id)
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

(defn post-to-wh [data] (client/post (:discord-wh config) {:form-params data :content-type :json}))

(defn post-embed [embed] (post-to-wh {:embeds [embed]}))

(defn process! [km-package]
  (try
    (log/debug (str "Processing km package " (:killID km-package)))
    (post-embed (generate-embed km-package))
    (log/debug (str "Processed km package " (:killID km-package)))
    (catch Exception e (payload-http-exception-handler e km-package process!))))

(defn pull-and-process! [mailbox]
  (while true (process! (<!! mailbox))))

(defn start []
  (let [worker-count 8
        mailbox (chan worker-count)
        worker-thread-pool (get-thread-pool worker-count)]
    (log/info "Starting discord component")
    (submit-to-thread-pool worker-thread-pool (fn [] (pull-and-process! mailbox)) worker-count)
    {:mailbox mailbox :tp worker-thread-pool}))

(defn stop [discord]
  (log/info "Stopping discord component")
  (shutdown-thread-pool (:tp discord)))

(mount/defstate discord
                :start (start)
                :stop (stop discord))