(ns makina.repl-sessions.walkthrough
  (:require
   [lambdaisland.makina.system :as sys]
   [lambdaisland.makina.app :as app]))

(def config
  {:http/server {:port 1234
                 :handler (sys/->Ref :http/handler)}
   :http/handler {}})
;;=>
{:http/server {:port 1234, :handler #makina/ref :http/handler},
 :http/handler {}}

(def system (sys/system config))
;;=>
{:http/server
 {:makina/id     :http/server
  :makina/type   :http/server
  :makina/state  :stopped
  :makina/config {:port    1234
                  :handler #makina/ref :http/handler}
  :makina/value  {:port        1234
                  :handler     #makina/ref :http/handler
                  :makina/type :http/server
                  :makina/id   :http/server}}
 :http/handler
 {:makina/id     :http/handler
  :makina/type   :http/handler
  :makina/state  :stopped
  :makina/config {}
  :makina/value  {:makina/type :http/handler
                  :makina/id   :http/handler}}}

(defn http-handler [req]
  {:status 200 :body "OK"})

(defn start-web-server [{:keys [port handler]}]
  ;; some ring adapter, e.g. ring.adapter.jetty/run-jetty
  (println "Starting server on port" port)
  (reify java.io.Closeable
    (close [this] (println "Stopping server..."))))

(defn stop-web-server [server]
  (.close server))

(def handlers
  {:http/handler {:start (constantly http-handler)}
   ;; or: (constantly http-handler)
   :http/server {:start start-web-server
                 :stop  stop-web-server}
   :default {:stop identity}})

(def started-system
  (sys/start system handlers))

(sys/stop started-system handlers)

;;;;; app

(def app (app/create {:config config :handlers handlers}))
(app/start! app)
;;=>
{:http/server #object[,,,]
 :http/handler #function[my.app.http.handler/http-handler]}

@app
;;=>
{:makina/state :started
 ;; ,,, data-readers, extra-handlers, config ,,,
 :makina/system
 {:http/server
  {:makina/id     :http/server
   :makina/state  :started
   :makina/config {:port 1234 :handler #makina/ref :http/handler}
   :makina/value  #object[,,,]
   :makina/type   :http/server}
  :http/handler
  {:makina/id     :http/handler
   :makina/state  :started
   :makina/config {}
   :makina/value  #function[my.app.http.handler/http-handler]
   :makina/type   :http/handler}}
 :makina/handlers
 {:default      {:stop #function[clojure.core/identity]}
  :http/handler #'my.app.http.handler/component
  :http/server  #'my.app.http.server/component}}
