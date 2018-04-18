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


(defn- extract-ids [involved]
  [(:ship_type_id involved)
   (:character_id involved)
   (:corporation_id involved)
   (:alliance_id involved)])


(defn- collect-ids [km-package]
  (let [victim (get-in km-package [:killmail :victim])
        final-blow (get-final-blow km-package)]
    (vec
      (distinct
        (remove nil? (flatten
                       [(extract-ids victim)
                        [(get-in km-package [:killmail :solar_system_id])
                         (get-in km-package [:killmail :constellation_id])
                         (get-in km-package [:killmail :region_id])]
                        (if
                          (not (nil? final-blow))
                          (extract-ids final-blow)
                          [])]))))))


(defn- get-names* [ids]
  (parse-string
    (:body
      (client/post
        (str (:base-url esi-vars) "/universe/names/")
        {:form-params  ids
         :content-type :json
         :query-params (:datasource esi-vars)
         :accept       :json})
      ) true))


(defn- get-names [ids]
  (let [resp (if
               (not (empty? ids))
               (try (get-names* ids)
                    (catch Exception e (payload-http-exception-handler e ids get-names 1000)))
               [])]
    (zipmap (map #(:id %) resp) (map #(:name %) resp))))


(defn- get-names-transformer [km-package]
  (log/debug "Enriching package with id " (:killID km-package) " with names")
  (merge km-package {:names (get-names (collect-ids km-package))}))


(defn- get-names-transducer [xf]
  (fn
    ([] (xf))
    ([result] (xf result))
    ([result input] (xf result (get-names-transformer input)))))


(defn- get-location* [id]
  (let [system (parse-string
                 (:body
                   (client/get
                     (str (:base-url esi-vars) "/universe/systems/" id)))
                 true)
        const (parse-string
                (:body
                  (client/get
                    (str (:base-url esi-vars) "/universe/constellations/" (:constellation_id system))))
                true)]
    {:constellation_id (:constellation_id system)
     :region_id        (:region_id const)}))


(defn- get-location [id]
  (when
    (not (nil? id))
    (try (get-location* id)
         (catch Exception e (payload-http-exception-handler e id get-location 1000)))))


(defn- get-location-transformer [km-package]
  (log/debug "Enriching package with id " (:killID km-package) " with region id")
  (merge km-package
         {:killmail (merge (:killmail km-package)
                           (get-location (get-in km-package [:killmail :solar_system_id])))}))


(defn- get-location-transducer [xf]
  (fn
    ([] (xf))
    ([result] (xf result))
    ([result input] (xf result (get-location-transformer input)))))


(defn start []
  (let [worker-count 8
        in (chan worker-count)
        out (chan worker-count)
        internal-pipeline (pipeline worker-count out (comp get-location-transducer get-names-transducer) in)]
    (log/info "Starting esi component")
    {:worker-count worker-count :in in :out out :internal-pipeline internal-pipeline}))

(defn stop [esi]
  (log/info "Stopping esi component")
  (close! (:in esi))
  (close! (:out esi))
  (close! (:internal-pipeline esi)))

(mount/defstate esi
                :start (start)
                :stop (stop esi))