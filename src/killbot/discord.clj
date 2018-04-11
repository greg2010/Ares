(ns killbot.discord
  (:require [clj-http.client :as client]
            [killbot.esi :as esi]
            [mount.core :as mount]
            [killbot.zkb :refer [zkb]]
            [clojure.core.async
             :refer [>! <! >!! <!! go chan buffer close! thread
                     alts! alts!! timeout]]
            [clojure.tools.logging :as log]))

(def ^{:private true} discord-vars {:discord-wh      "REDACTED"
                                    :base-url        "https://zkillboard.com/kill"
                                    :img-eve-baseurl "http://imageserver.eveonline.com"
                                    :colors          {
                                                      :red   (long 0x990000)
                                                      :green (long 0x009900)
                                                      }})



(defn get-final-blow [km-package] (first (filter #(:final_blow %) (get-in km-package [:killmail :attackers]))))

(defn extract-ids [involved]
  [(:ship_type_id involved)
   (:character_id involved)
   (:corporation_id involved)
   (:alliance_id involved)])

(defn collect-ids [km-package]
  (let [victim (get-in km-package [:killmail :victim])
        final-blow (get-final-blow km-package)]
    (remove nil? (flatten
                   [(extract-ids victim)
                    [(get-in km-package [:killmail :solar_system_id])]
                    (if
                      (not (nil? final-blow))
                      (extract-ids final-blow)
                      [])]))))

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
  (let [names (esi/get-names (collect-ids km-package))
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

(defn post-to-wh [data]
  (try
    (client/post (:discord-wh discord-vars) {:form-params data :content-type :json})
    (catch Exception e (log/error "Caught exception while posting data to a discord webhook" e)
                       (throw e))))

(defn post-embed [embed] (post-to-wh {:embeds [embed]}))

(defn pull-and-process! [mailbox]
  (let
    [km-package (<!! mailbox)]
    (try
      (post-embed (generate-embed km-package))
      (catch Exception _ (log/error (str "Offending km package:\n" km-package))))))

(defn start []
  (let [running? (atom true)
        daemon (go (while @running? (pull-and-process! (:mailbox zkb))))]
    {:running running? :daemon daemon}))

(defn stop [discord] (reset! (:running discord) false))

(mount/defstate discord
                :start (start)
                :stop (stop discord))
