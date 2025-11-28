(ns lambdaisland.makina.test
  "Provide a system/app during testing

  "
  (:require
   [lambdaisland.makina.system :as sys]
   [lambdaisland.makina.app :as app]
   [aero.core :as aero]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]))

(def ^:dynamic *app* nil)

(defn init-app [opts]
  (let [app (-> (assoc opts :profile :test)
                app/create*
                app/load*
                (app/start* (:keys opts)))]
    (when-let [e (app/error* app)]
      (app/stop* app)
      (throw e))
    app))

(defmacro with-app
  "Start a system, bind to `*app*`, evaluate `body`, tear the system down again

  - `opts` - see [[lambdaisland.makina.application/create]]
  "
  [opts & body]
  `(binding [*app* (init-app ~opts)]
     (let [res# (do ~@body)]
       (app/stop* *app*)
       res#)))

(defn make-fixture-fn
  "Returns a function that can be used with [[clojure.test/use-fixture]]

  The function takes a collection of keys/components to start (empty to start
  all keys). `opts` are as per [[lambdaisland.makina.application/create]]

  ```
  (def wrap-system (make-fixture-fn {:prefix \"my-app\"}))
  (use-fixture :once (wrap-system [:http/handler]))
  (use-fixture :once (wrap-system {:keys [:http/handler] :handlers {...}}))
  ```
  "
  [opts]
  (fn fixture-factory
    ([]
     (fixture-factory nil))
    ([ks-or-opts]
     (let [opts (merge-with merge
                            opts
                            (if (map? ks-or-opts)
                              ks-or-opts
                              {:keys ks-or-opts}))]
       (fn [t]
         (with-app opts (t)))))))

(defn component
  "Get a single component value"
  [k]
  (sys/component (:makina/system *app*) k))

(defn start!
  "Starts an app/system and binds `*app*` permanently, meant for REPL use"
  ([opts]
   (alter-var-root #'*app* (constantly (init-app opts))))
  ([opts ks]
   (start! (assoc opts :keys ks))))
