(ns httpkit.stub
  (:require [org.httpkit.client :as http]
            [clojure.math.combinatorics :refer :all]
            [stub.shared :as shared]))

(defn- find-matching-route [routes request]
  (first
    (for [[url handlers] routes
          :when (shared/matches url (:method request) request)
          :let [method (:method request)
                handler (or (get handlers method)
                          (get handlers :any))
                times (or (get-in handlers [:times method])  ; Get method-specific times
                         (:times handlers))]                 ; Or global times
          :when handler]
      [url (fn [req] 
            ;; Set up expected counts if :times is specified
            (when times
              (swap! shared/*expected-counts* assoc (str url ":" (name method)) times))
            (handler (merge req 
                          {:url (:url request)
                           :method method
                           :query-params (:query-params request)})))])))

(defn wrap-request-with-stub [client]
  (fn [req callback]
    (let [request (shared/normalize-request req)
          matching-route (find-matching-route shared/*stub-routes* request)
          [url response] matching-route]
      (when url
        (swap! shared/*call-counts* update (str url ":" (name (:method request))) (fnil inc 0)))
      (let [response-promise (promise)]
        (if matching-route
          (deliver response-promise (shared/create-response response request))
          (if shared/*in-isolation*
            (throw (Exception. (str "No matching stub route found for " (:method request) " "
                                  (:url request))))
            (client req #(deliver response-promise %))))
        (callback @response-promise)
        response-promise))))

(defmacro with-http-stub
  [routes & body]
  `(with-redefs [http/request (wrap-request-with-stub http/request)]
     (shared/with-stub-bindings ~routes (fn [] ~@body))))

(defmacro with-http-stub-in-isolation
  [routes & body]
  `(binding [shared/*in-isolation* true]
     (with-http-stub ~routes ~@body)))

(defmacro with-global-http-stub
  [routes & body]
  `(shared/with-global-http-stub-base ~routes
     (with-redefs [http/request (wrap-request-with-stub http/request)]
       ~@body)
     ~@body))

(defmacro with-global-http-stub-in-isolation
  [routes & body]
  `(with-redefs [shared/*in-isolation* true]
     (with-global-http-stub ~routes ~@body)))
