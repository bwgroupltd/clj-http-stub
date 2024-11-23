(ns clj-http.stub
  (:require [clj-http.core]
            [stub.shared :as shared])
  (:use [robert.hooke]
        [clojure.math.combinatorics]
        [clojure.string :only [join split]]))

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
    [^String s]
    (.getBytes s "UTF-8"))

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
    (cond
      ;; Handler is a function with times metadata
      (and (fn? handler) (:times (meta handler)))
      (do
        (swap! shared/*expected-counts* assoc route-key (:times (meta handler)))
        [method address {:handler handler}])

      ;; Handler is a map with :handler and :times
      (and (map? handler) (:handler handler))
      (do
        (when-let [times (:times handler)]
          (swap! shared/*expected-counts* assoc route-key times))
        [method address {:handler (:handler handler)}])

      ;; Handler is a direct function
      :else
      [method address {:handler handler}])))

(defn- flatten-routes [routes]
  (let [normalised-routes
        (reduce
         (fn [accumulator [address handlers]]
           (if (map? handlers)
             (into accumulator 
                   (map (fn [[method handler]]
                         (if (= method :times)
                           nil
                           (let [times (get-in handlers [:times method] (:times handlers))]
                             (process-handler method address 
                                            (if times
                                              (with-meta handler {:times times})
                                              handler)))))
                       (dissoc handlers :times)))
             (into accumulator [[:any address {:handler handlers}]])))
         []
         routes)]
    (remove nil? (map #(zipmap [:method :address :handler] %) normalised-routes))))

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
