# clj-http-stub 
[![MIT License](https://img.shields.io/badge/license-MIT-brightgreen.svg?style=flat)](https://www.tldrlegal.com/l/mit) 
[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.bigsy/clj-http-stub.svg)](https://clojars.org/org.clojars.bigsy/clj-http-stub)

This is a library for stubbing out HTTP requests in Clojure. It supports both clj-http and http-kit clients with a consistent API.

## Usage

### With clj-http

```clojure
(ns myapp.test.core
   (:require [clj-http.client :as c])
   (:use clj-http.stub))
```

### With http-kit

```clojure
(ns myapp.test.core
   (:require [org.httpkit.client :as http])
   (:use httpkit.stub))
```

The public interface consists of macros:

* ``with-http-stub`` - lets you override HTTP requests that match keys in the provided map
* ``with-http-stub-in-isolation`` - does the same but throws if a request does not match any key
* ``with-global-http-stub`` 
* ``with-global-http-stub-in-isolation``

'Global' counterparts use ``with-redefs`` instead of ``binding`` internally so they can be used in
a multi-threaded environment (only available for clj-http).

### Examples

The API is identical for both clj-http and http-kit, with the only difference being that http-kit uses callbacks/promises while clj-http is synchronous:

```clojure
;; With clj-http:
(with-http-stub
  {"https://api.weather.com/v1/current"
   (fn [request] {:status 200 :headers {} :body "Sunny, 72°F"})}
  (c/get "https://api.weather.com/v1/current"))

;; With http-kit:
(with-http-stub
  {"https://api.weather.com/v1/current"
   (fn [request] {:status 200 :headers {} :body "Sunny, 72°F"})}
  @(http/get "https://api.weather.com/v1/current"))

;; Route matching examples (works the same for both clients):
(with-http-stub
  {;; Exact string match:
   "https://api.github.com/users/octocat"
   (fn [request] {:status 200 :headers {} :body "{\"name\": \"The Octocat\"}"})

   ;; Exact string match with query params:
   "https://api.spotify.com/v1/search?q=beethoven&type=track"
   (fn [request] {:status 200 :headers {} :body "{\"tracks\": [...]}"})

   ;; Regexp match:
   #"https://([a-z]+).stripe.com/v1/customers"
   (fn [req] {:status 200 :headers {} :body "{\"customer\": \"cus_123\"}"})

   ;; Match based on HTTP method:
   "https://api.slack.com/api/chat.postMessage"
   {:post (fn [req] {:status 200 :headers {} :body "{\"ok\": true}"})}

   ;; Match multiple HTTP methods:
   "https://api.dropbox.com/2/files"
   {:get    (fn [req] {:status 200 :headers {} :body "{\"entries\": [...]}"})
    :delete (fn [req] {:status 401 :headers {} :body "{\"error\": \"Unauthorized\"}"})
    :any    (fn [req] {:status 200 :headers {} :body "{\"status\": \"success\"}"})}

   ;; Match using query params as a map
   {:address "https://api.openai.com/v1/chat/completions" :query-params {:model "gpt-4"}}
   (fn [req] {:status 200 :headers {} :body "{\"choices\": [...]}"})

   ;; If not given, the stub response status will be 200 and the body will be "".
   "https://api.twilio.com/2010-04-01/Messages"
   (constantly {})}

 ;; Your tests with requests here
 )

### Call Count Validation

You can specify and validate the number of times a route should be called using the `:times` option. There are two supported formats:

#### Simple Format
The `:times` option can be specified as a sibling of the HTTP methods:

```clojure
;; With clj-http:
(with-http-stub
  {"https://api.example.com/data"
   {:get (fn [_] {:status 200 :body "ok"})
    :times 2}}
  
  ;; This will pass - route is called exactly twice as expected
  (c/get "https://api.example.com/data")
  (c/get "https://api.example.com/data"))

;; With http-kit:
(with-http-stub
  {"https://api.example.com/data"
   {:get (fn [_] {:status 200 :body "ok"})
    :times 2}}
  
  ;; This will pass - route is called exactly twice as expected
  @(http/get "https://api.example.com/data")
  @(http/get "https://api.example.com/data"))

;; Multiple methods with shared count
(with-http-stub
  {"https://api.example.com/data"
   {:get (fn [_] {:status 200 :body "ok"})
    :post (fn [_] {:status 201 :body "created"})
    :times 1}}
  (c/get "https://api.example.com/data")
  (c/post "https://api.example.com/data"))
```

#### Per-Method Format
For more granular control, `:times` can be a map specifying counts per HTTP method:

```clojure
(with-http-stub
  {"https://api.example.com/data"
   {:get (fn [_] {:status 200 :body "ok"})
    :post (fn [_] {:status 201 :body "created"})
    :times {:get 2 :post 1}}}
  
  ;; This will pass - GET called twice, POST called once
  (c/get "https://api.example.com/data")
  (c/get "https://api.example.com/data")
  (c/post "https://api.example.com/data"))
```

The `:times` option allows you to:
- Verify a route is called exactly the expected number of times
- Ensure endpoints aren't called more times than necessary
- Specify different call counts for different HTTP methods

If the actual number of calls doesn't match the expected count, an exception is thrown with a descriptive message. 
If `:times` is not supplied, the route can be called any number of times.

### URL Matching Details

The library provides the following URL matching capabilities:

1. Default ports:
   ```clojure
   ;; These are equivalent:
   "http://example.com:80/api"
   "http://example.com/api"
   ```

2. Trailing slashes:
   ```clojure
   ;; These are equivalent:
   "http://example.com/api/"
   "http://example.com/api"
   ```

3. Default schemes:
   ```clojure
   ;; These are equivalent:
   "http://example.com"
   "example.com"
   ```

4. Query parameter order independence:
   ```clojure
   ;; These are equivalent:
   "http://example.com/api?a=1&b=2"
   "http://example.com/api?b=2&a=1"
   ```
