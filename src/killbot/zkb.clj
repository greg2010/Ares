(ns killbot.zkb
  (:require [clj-http.client :as client]
            [cheshire.core :refer :all]
            [mount.core :as mount]
            [clojure.core.async
             :refer [>! <! >!! <!! go go-loop chan buffer close! thread
                     alts! alts!! timeout]]
            [clojure.tools.logging :as log]))

(def relevant {:alliance-ids [1 2 3] :corp-ids [4 5 6]})

(def ^{:private true} zkb-vars {:redisq-url "https://redisq.zkillboard.com/listen.php"
                                :base-url   "https://zkillboard.com/kill"})


(defn friendly? [km-package]
  (let [corp-id (get-in km-package [:killmail :victim :corporation_id])
        alliance-id (get-in km-package [:killmail :victim :alliance_id])]
    (not
      (nil?
        (or (some #(= corp-id %) (:corp-ids relevant))
            (some #(= alliance-id %) (:alliance-ids relevant)))))))

(defn poll [] (let [_ (log/debug "Polling from zkb")
                    km-package (try
                                 (:package (parse-string (:body (client/get (:redisq-url zkb-vars) {:accept :json})) true))
                                 (catch Exception e (log/error "Caught exception while trying to pull from zkb" e)))]
                (if (not (nil? km-package)) (merge km-package {:friendly (friendly? km-package)}))))

(defn relevant?
  ([km-package]
   (let [victim-corp-id (get-in km-package [:killmail :victim :corporation_id])
         victim-alliance-id (get-in km-package [:killmail :victim :alliance_id])
         attackers-corp-ids (vec (map :corporation_id (get-in km-package [:killmail :attackers])))
         attackers-alliance-ids (vec (map :alliance_id (get-in km-package [:killmail :attackers])))]
     (not (nil?
            (or (some (set (:corp-ids relevant)) (conj [victim-corp-id] attackers-corp-ids))
                (some (set (:alliance-ids relevant)) (conj [victim-alliance-id] attackers-alliance-ids)))))))
  ([km-package skip-eval] (if skip-eval true (relevant? km-package))))

(defn push-if-relevant! [payload mailbox]
  (log/trace "Pushing payload to mailbox if relevant" payload)
  (if
    (and (relevant? payload true) (not (nil? payload)))
    (>!! mailbox payload)))


(defn start []
  (let [mailbox (chan 5)
        running? (atom true)
        daemon (go (while @running? (push-if-relevant! (poll) mailbox)))]
    {:mailbox mailbox :running running? :daemon daemon}))

(defn stop [zkb] (reset! (:running zkb) false))

(mount/defstate zkb
                :start (start)
                :stop (stop zkb))