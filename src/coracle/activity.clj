(ns coracle.activity
  (:require [clojure.tools.logging :as log]
            [ring.util.response :as r]
            [cheshire.core :as json]
            [coracle.db :as db]
            [coracle.marshaller :as m]))

(defn- unsigned-activity-response [activities]
  (-> (r/response activities)
      (r/content-type "application/activity+json")
      (r/charset "utf-8")))

(defn- signed-activity-response [external-jwk-set-url jws-generator activities]
  (let [activities-signed-and-encoded-payload (-> activities
                                                  json/generate-string
                                                  jws-generator)]
    (-> (r/response {:jws-signed-payload activities-signed-and-encoded-payload
                     :jku                external-jwk-set-url})
        (r/content-type "application/jose+json")
        (r/charset "utf-8"))))

(defn- bad-request-response [body]
  (-> (r/response body) (r/status 400)))

(defn- activity-from-request [req]
  (->> req :body m/validate-and-parse-activity))

(defn add-activity [db req]
  (log/info "adding activity with request: " req)
  (let [data (activity-from-request req)]
    (if (empty? (:error data))
      (do (db/add-activity db data)
          (-> (r/response {:status :success}) (r/status 201)))
      (bad-request-response (:error data)))))

(defn- descending [a b]
  (compare b a))

(defn- published [activity-json]
  (get activity-json "published"))

(defn surround-with-coll-object [activities]
  {(keyword "@context") "http://www.w3.org/ns/activitystreams"
   :type                "Collection"
   :name                "Activity stream"
   :totalItems          (count activities)
   :items               activities})

(defn- generate-activity-response [db external-jwk-set-url jws-generator query-params]
  (let [activities (->> (db/fetch-activities db query-params)
                        (sort-by published descending)
                        (map m/stringify-activity-timestamp)
                        surround-with-coll-object)]
    (if (= "true" (:signed query-params))
      (signed-activity-response external-jwk-set-url jws-generator activities)
      (unsigned-activity-response activities))))

(defn get-activities [db external-jwk-set-url jws-generator req]
  (let [query-params (-> req :params m/marshall-query-params)
        error-m (:error query-params)]
    (if (empty? error-m)
      (generate-activity-response db external-jwk-set-url jws-generator query-params)
      (bad-request-response error-m))))

(defn latest-published-timestamp [db _]
  (let [latest-published-activity (db/fetch-latest-published-activity db)
        jsonified-activity (m/stringify-activity-timestamp latest-published-activity)
        response-body (if latest-published-activity
                        {:latest-published-timestamp (published jsonified-activity)}
                        {})]
    (-> (r/response response-body)
        (r/content-type "application/json"))))

