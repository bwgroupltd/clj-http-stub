(ns clj-http.stub
  (:require [clj-http.core]
            [stub.shared :as shared]
            [robert.hooke :refer [add-hook]]
            [clojure.math.combinatorics :as combo]
            [clojure.string :refer [join split]])
  (:import (java.nio.charset StandardCharsets)))

(defmacro with-http-stub
  [routes & body]
  `(shared/with-stub-bindings ~routes (fn [] ~@body)))

(defmacro with-http-stub-in-isolation
  [routes & body]
  `(binding [shared/*in-isolation* true]
     (with-http-stub ~routes ~@body)))

(defmacro with-global-http-stub
  [routes & body]
  `(shared/with-global-http-stub-base ~routes (do ~@body)))

(defmacro with-global-http-stub-in-isolation
  [routes & body]
  `(with-redefs [shared/*in-isolation* true]
     (with-global-http-stub ~routes ~@body)))

(defn utf8-bytes
  "Returns the UTF-8 bytes corresponding to the given string."
  ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn- byte-array?
  "Is `obj` a java byte array?"
  [obj]
  (instance? (Class/forName "[B") obj))

(defn body-bytes
  "If `obj` is a byte-array, return it, otherwise use `utf8-bytes`."
  [obj]
  (if (byte-array? obj)
    obj
    (utf8-bytes obj)))

(defn- process-handler [method address handler]
  (let [route-key (str address method)]
    (if (fn? handler)
      (if-let [times (:times (meta handler))]
        (do
          (swap! shared/*expected-counts* assoc route-key times)
          [method address {:handler handler}])
        [method address {:handler handler}])
      (if (map? handler)
        (do
          (when-let [times (:times handler)]
            (swap! shared/*expected-counts* assoc route-key times))
          [method address {:handler (:handler handler)}])
        [method address {:handler handler}]))))

(defn- flatten-routes [routes]
  (->> routes
       (mapcat (fn [[address handlers]]
                (if (map? handlers)
                  (keep (fn [[method handler]]
                         (when-not (= method :times)
                           (let [times (get-in handlers [:times method] (:times handlers))]
                             (process-handler method address 
                                            (cond-> handler
                                              times (with-meta {:times times}))))))
                       (dissoc handlers :times))
                  [[:any address {:handler handlers}]])))
       (map #(zipmap [:method :address :handler] %))))

(defn- get-matching-route
  [request]
  (->> shared/*stub-routes*
       flatten-routes
       (filter #(shared/matches (:address %) (:method %) request))
       first))

(defn- handle-request-for-route
  [request route]
  (let [route-handler (:handler route)
        handler-fn (if (map? route-handler) (:handler route-handler) route-handler)
        route-key (str (:address route) (:method route))
        _ (swap! shared/*call-counts* update route-key (fnil inc 0))
        response (shared/create-response handler-fn (shared/normalize-request request))]
    (update response :body body-bytes)))

(defn- throw-no-stub-route-exception
  [request]
  (throw (Exception.
           ^String
           (apply format
                  "No matching stub route found to handle request. Request details: \n\t%s \n\t%s \n\t%s \n\t%s \n\t%s "
                  (select-keys request [:scheme :request-method :server-name :uri :query-string])))))

(defn try-intercept
  ([origfn request respond raise]
   (if-let [matching-route (get-matching-route request)]
     (future
       (try (respond (handle-request-for-route request matching-route))
            (catch Exception e (raise e)))
       nil)
     (if shared/*in-isolation*
       (try (throw-no-stub-route-exception request)
            (catch Exception e
              (raise e)
              (throw e)))
       (origfn request respond raise))))
  ([origfn request]
   (if-let [matching-route (get-matching-route request)]
     (handle-request-for-route request matching-route)
     (if shared/*in-isolation*
       (throw-no-stub-route-exception request)
       (origfn request)))))

(defn initialize-request-hook []
  (add-hook
   #'clj-http.core/request
   #'try-intercept))

(initialize-request-hook)
