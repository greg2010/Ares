(ns org.red.ares.zkb
  (:require [clj-http.client :as client]
            [cheshire.core :refer :all]
            [mount.core :as mount]
            [org.red.ares.util :refer :all]
            [org.red.ares.config :refer [config]]
            [overtone.at-at :as scheduler]
            [clojure.core.async
             :refer [>! <! >!! <!! go go-loop chan buffer close! thread
                     alts! alts!! timeout]]
            [clojure.tools.logging :as log]))


(def ^{:private true} zkb-vars {:redisq-url "https://redisq.zkillboard.com/listen.php"
                                :base-url   "https://zkillboard.com/kill"})


(defn- poll* [] (:package (parse-string (:body (client/get (:redisq-url zkb-vars) {:accept :json})) true)))

(defn poll [_]
  (try (log/debug "Polling from zkb")
       (poll*)
       (catch Exception e (payload-http-exception-handler e nil poll 10000))))

(defn- poll-and-push! [mailbox scheduler-pool]
  (scheduler/interspaced 100
    #(let [km-package (poll nil)]
      (when (not (nil? km-package))
        (log/debug (str "Pushing payload to mailbox with killID " (:killID km-package)))
        (>!! mailbox km-package))) scheduler-pool))

(defn start []
  (let [worker-count 1
        out (chan worker-count)
        worker-thread-pool (get-thread-pool worker-count)
        scheduler-pool (scheduler/mk-pool)]
    (log/info "Starting zkb component")
    (submit-to-thread-pool worker-thread-pool (fn [] (poll-and-push! out scheduler-pool)) worker-count)
    {:worker-count worker-count :out out :tp worker-thread-pool :scheduler-pool scheduler-pool}))

(defn stop [zkb]
  (log/info "Stopping zkb component")
  (close! (:out zkb))
  (scheduler/stop-and-reset-pool! (:scheduler-pool zkb))
  (shutdown-thread-pool (:tp zkb)))

(mount/defstate zkb
                :start (start)
                :stop (stop zkb))