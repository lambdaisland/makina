(ns lambdaisland.makina.kaocha-plugin
  "Kaocha plugin for `lambdaisland.makina.test`

  Bind `*app*` to a started app value during tests. Relies on seeing a
  `:makina/settings` in tests.edn, either at the top level, or on a test suite.
  This location determines the scope of the binding. See the Makina README for
  details."
  (:require
   [lambdaisland.makina.test :as mt]
   [lambdaisland.makina.app :as app]
   [kaocha.plugin :as p]))

(defn- find-settings [testable]
  (when-let [settings (:makina/settings testable)]
    (let [settings (cond-> settings (symbol? settings) (-> requiring-resolve deref))]
      (if (fn? settings)
        (settings)
        settings))))

(p/defplugin lambdaisland.makina/kaocha-plugin
  ;; Settings declared at top level
  (pre-run [test-plan]
    (when-let [settings (find-settings test-plan)]
      (push-thread-bindings {#'mt/*app* (mt/init-app settings)}))
    test-plan)

  (post-run [result]
    (when (find-settings result)
      (app/stop* mt/*app*)
      (pop-thread-bindings))
    result)

  ;; Settings declared on test suite
  (pre-test [test test-plan]
    (when-let [settings (find-settings test)]
      (push-thread-bindings {#'mt/*app* (mt/init-app settings)}))
    test)

  (post-test [test test-plan]
    (when (find-settings test)
      (app/stop* mt/*app*)
      (pop-thread-bindings))
    test))
