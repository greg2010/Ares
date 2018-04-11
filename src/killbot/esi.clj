(ns killbot.esi
  (:require [clj-http.client :as client]
            [cheshire.core :refer :all]
            [clojure.tools.logging :as log]))


(def ^{:private true} esi-vars {:base-url "https://esi.tech.ccp.is/latest"
                                :datasource {"datasource" "tranquility"}})

(defn get-names [ids]
  (let [resp (if (not (empty? ids)) (parse-string
                                      (:body
                                        (try
                                          (client/post
                                            (str (:base-url esi-vars) "/universe/names/")
                                            {:form-params ids
                                             :content-type :json
                                             :query-params (:datasource esi-vars)
                                             :accept :json})
                                          (catch Exception e (log/error "Caught exception while trying to resolve names" e)))) true)
                                    [])]
    (zipmap (map #(:id %) resp) (map #(:name %) resp))))