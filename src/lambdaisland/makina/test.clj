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

(defn init-sys [opts]
  (-> (assoc opts :profile :test)
      app/create*
      app/load*
      (app/start* (:keys opts))))

(defmacro with-app
  "Start a system, bind to `*app*`, evaluate `body`, tear the system down again

  - `opts` - see [[lambdaisland.makina.application/create]]
  "
  [opts & body]
  `(binding [*app* (init-sys ~opts)]
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
         (with-system opts (t)))))))

(defn component
  "Get a single component value"
  [k]
  (sys/component (:makina/system *app*) k))

(defn wrap-run
  "Hook for use with Kaocha

  Configure as a \"wrap-run\" hook, it will ensure during the entire test run a
  app/system is available in `*app*`. Settings (as
  per [[lambdaisland.makina.app/create]]) can be configured inside `tests.edn`
  using the `:makina/settings` key. Either set this to a map (e.g.
  `:makina/settings {:prefix \"my-app\"}`), or use a symbol to point at a var
  that can provide the settings. The var should resolve to a map or function.

  ```
  ;; tests.edn
  #kaocha/v1
  {:plugins [:hooks]
   :kaocha.hooks/wrap-run [lambdaisland.makina.test/wrap-run]
   :makina/settings my-app.config.makina-opts}
  ```
  "
  [run-fn test-plan]
  (let [settings (:makina/settings test-plan)
        settings (cond-> settings (symbol? settings) (-> requiring-resolve deref))
        settings (if (fn? settings) (settings) settings)]
    (with-app settings
      (run-fn))))

(defn start!
  "Starts an app/system and binds `*app*` permanently, meant for REPL use"
  ([opts]
   (alter-var-root #'*app* (constantly (init-sys))))
  ([opts ks]
   (start! (assoc opts :keys ks))))
