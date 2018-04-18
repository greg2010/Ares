(ns killbot.util
  (:require [clojure.tools.logging :as log]
            [cheshire.core :refer :all])
  (:import (java.util.concurrent Executors)))


(def default-uncaught-exception-handler
  (Thread/setDefaultUncaughtExceptionHandler
    (reify Thread$UncaughtExceptionHandler
      (uncaughtException [_ thread ex]
        (log/error ex "Uncaught exception on" (.getName thread))))))

(defn payload-http-exception-handler [e payload fn init-delay]
  (case
    (:status (ex-data e))
    429 (let [body (parse-string (:body (ex-data e)) true)
              retry-after (:retry_after body)
              delay (+ 100 (if (nil? retry-after) init-delay retry-after))]
          (log/debug (str "Encountered rate limit for "
                          retry-after
                          " type: "
                          (if (:global body) "global" "local")))
          (log/info (str "Delaying thread for " delay "ms"))
          (Thread/sleep delay)
          (fn payload))
    400 (log/warn (str "Got 400, offending payload:\n" (.toString payload)))
    (log/error (str "Got unknown exception, status code: " (:status (ex-data e)) " payload:\n" payload "\n" e))))

(defn get-thread-pool [thread-count] (Executors/newFixedThreadPool thread-count))

(defn submit-to-thread-pool [tp fn count]
  (dotimes [_ count]
    (.execute (do (Thread/setDefaultUncaughtExceptionHandler default-uncaught-exception-handler) tp) fn)))

(defn shutdown-thread-pool [tp] (.shutdown tp))
