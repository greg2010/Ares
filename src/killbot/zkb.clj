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


(defn- friendly? [km-package]
  (let [char-id (get-in km-package [:killmail :victim :character_id])
        corp-id (get-in km-package [:killmail :victim :corporation_id])
        alliance-id (get-in km-package [:killmail :victim :alliance_id])]
    (not (nil?
           (or (some #(= char-id %) (get-in config [:relevant-entities :character-ids]))
               (some #(= corp-id %) (get-in config [:relevant-entities :corporation-ids]))
               (some #(= alliance-id %) (get-in config [:relevant-entities :alliance-ids])))))))

(defn- enrich-friendly [km-package] (when (not (nil? km-package)) (merge km-package {:friendly (friendly? km-package)})))

(defn- poll* [] (:package (parse-string (:body (client/get (:redisq-url zkb-vars) {:accept :json})) true)))

(defn poll [_]
  (try (log/debug "Polling from zkb")
       (poll*)
       (catch Exception e (payload-http-exception-handler e nil poll 10000))))

(defn- poll-and-push! [mailbox]
  (while true
    (let [km-package (enrich-friendly (poll nil))]
      (when (not (nil? km-package))
        (log/debug (str "Pushing payload to mailbox with killID " (:killID km-package)))
        (>!! mailbox km-package)))))

(defn start []
  (let [worker-count 1
        out (chan worker-count)
        worker-thread-pool (get-thread-pool worker-count)]
    (log/info "Starting zkb component")
    (submit-to-thread-pool worker-thread-pool (fn [] (poll-and-push! out)) worker-count)
    {:worker-count worker-count :out out :tp worker-thread-pool}))

(defn stop [zkb]
  (log/info "Stopping zkb component")
  (close! (:out zkb))
  (shutdown-thread-pool (:tp zkb)))

(mount/defstate zkb
                :start (start)
                :stop (stop zkb))