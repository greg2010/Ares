(ns killbot.esi
  (:require [clj-http.client :as client]
            [cheshire.core :refer :all]
            [clojure.tools.logging :as log]
            [killbot.discord :refer [discord]]
            [killbot.zkb :refer [zkb]]
            [killbot.util :refer :all]
            [clojure.core.async
             :refer [>! <! >!! <!! go go-loop pipeline chan buffer close! thread
                     alts! alts!! timeout]]
            [mount.core :as mount]))


(def ^{:private true} esi-vars {:base-url   "https://esi.tech.ccp.is/latest"
                                :datasource {"datasource" "tranquility"}})

(defn get-final-blow [km-package] (first (filter #(:final_blow %) (get-in km-package [:killmail :attackers]))))

(defn extract-ids [involved]
  [(:ship_type_id involved)
   (:character_id involved)
   (:corporation_id involved)
   (:alliance_id involved)])

(defn collect-ids [km-package]
  (let [victim (get-in km-package [:killmail :victim])
        final-blow (get-final-blow km-package)]
    (vec
      (distinct
        (remove nil? (flatten
                       [(extract-ids victim)
                        [(get-in km-package [:killmail :solar_system_id])]
                        (if
                          (not (nil? final-blow))
                          (extract-ids final-blow)
                          [])]))))))

(defn get-names* [ids]
  (parse-string
    (:body
      (client/post
        (str (:base-url esi-vars) "/universe/names/")
        {:form-params  ids
         :content-type :json
         :query-params (:datasource esi-vars)
         :accept       :json})
      ) true))

(defn get-names [ids]
  (let [resp (if
               (not (empty? ids))
               (try (get-names* ids)
                    (catch Exception e (payload-http-exception-handler e ids get-names)))
               [])]
    (zipmap (map #(:id %) resp) (map #(:name %) resp))))


(defn transform-km-package [km-package]
  (log/debug "Transforming km package" (:killID km-package))
  (merge km-package {:names (get-names (collect-ids km-package))}))


(defn start []
  (let [pipeline (pipeline 4 (:mailbox discord) (map transform-km-package) (:mailbox zkb))]
    (log/info "Starting esi component")
    {:pipeline pipeline}))

(defn stop [esi]
  (log/info "Stopping esi component")
  (close! (:pipeline esi)))

(mount/defstate esi
                :start (start)
                :stop (stop esi))