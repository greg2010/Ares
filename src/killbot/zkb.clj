(ns killbot.zkb
  (:require [clj-http.client :as client]
            [cheshire.core :refer :all]
            [mount.core :as mount]
            [killbot.config :refer [config]]
            [clojure.core.async
             :refer [>! <! >!! <!! go go-loop chan buffer close! thread
                     alts! alts!! timeout]]
            [killbot.util :refer :all]
            [clojure.tools.logging :as log]))


(def ^{:private true} zkb-vars {:redisq-url "https://redisq.zkillboard.com/listen.php"
                                :base-url   "https://zkillboard.com/kill"})


(defn friendly? [km-package]
  (let [corp-id (get-in km-package [:killmail :victim :corporation_id])
        alliance-id (get-in km-package [:killmail :victim :alliance_id])]
    (not
      (nil?
        (or (some #(= corp-id %) (get-in config [:relevant-entities :corporation-ids]))
            (some #(= alliance-id %) (get-in config [:relevant-entities :alliance-ids])))))))

(defn poll* [] (:package (parse-string (:body (client/get (:redisq-url zkb-vars) {:accept :json})) true)))

(defn poll [_] (let [km-package (try (log/debug "Polling from zkb")
                                    (poll*)
                                    (catch Exception e (payload-http-exception-handler e nil poll)))]
                (if (not (nil? km-package)) (merge km-package {:friendly (friendly? km-package)}))))

(defn relevant?
  ([km-package]
   (let [victim-corp-id (get-in km-package [:killmail :victim :corporation_id])
         victim-alliance-id (get-in km-package [:killmail :victim :alliance_id])
         attackers-corp-ids (vec (map :corporation_id (get-in km-package [:killmail :attackers])))
         attackers-alliance-ids (vec (map :alliance_id (get-in km-package [:killmail :attackers])))]
     (not (nil?
            (or (some (set (get-in config [:relevant-entities :corporation-ids])) (flatten (conj [victim-corp-id] attackers-corp-ids)))
                (some (set (get-in config [:relevant-entities :alliance-ids])) (flatten (conj [victim-alliance-id] attackers-alliance-ids))))))))
  ([km-package skip-eval] (if skip-eval true (relevant? km-package))))

(defn poll-and-push-if-relevant! [mailbox]
  (while true
    (let [km-package (poll nil)]
      (log/trace "Pushing payload to mailbox if relevant" km-package)
      (if
        (and (relevant? km-package (get-in config [:relevant-entities :all])) (not (nil? km-package)))
        (>!! mailbox km-package)))))


(defn start []
  (let [worker-count 1
        mailbox (chan worker-count)
        worker-thread-pool (get-thread-pool worker-count)]
    (log/info "Starting zkb component")
    (submit-to-thread-pool worker-thread-pool (fn [] (poll-and-push-if-relevant! mailbox)) worker-count)
    {:mailbox mailbox :tp worker-thread-pool}))

(defn stop [zkb]
  (log/info "Stopping zkb component")
  (shutdown-thread-pool (:tp zkb)))

(mount/defstate zkb
                :start (start)
                :stop (stop zkb))