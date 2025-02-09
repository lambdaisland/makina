(ns lambdaisland.makina.app
  (:require
   [lambdaisland.makina.system :as sys]
   [aero.core :as aero]
   [clojure.java.io :as io]))

(defn try-resolve [sym]
  (try
    (requiring-resolve sym)
    (catch Exception _)))

(defn create [{:keys [prefix config-source data-readers handlers]}]
  (atom {:makina/state :not-loaded
         :makina/config-source (or config-source (str prefix "/system.edn"))
         :makina/extra-handlers handlers
         :makina/data-readers (merge
                               {'ref    sys/->Ref
                                'refset sys/->Refset}
                               data-readers)}))

(defn load* [{:makina/keys [state config-source data-readers extra-handlers] :as app}]
  (if-let [sys-edn (io/resource config-source)]
    (binding [*data-readers* (merge *data-readers* data-readers)]
      (let [system   (sys/system (aero.core/read-config sys-edn))
            handlers (merge
                      (into {}
                            (comp
                             (remove (comp (set (keys extra-handlers)) :makina/type val))
                             (map val)
                             (map :makina/type)
                             (mapcat (fn [t]
                                       (if (or (qualified-symbol? t)
                                               (qualified-keyword? t))
                                         (when-let [comp (or (try-resolve (symbol t))
                                                             (try-resolve (symbol (str (namespace t) "." (name t))
                                                                                  "component")))]
                                           [[t comp]]))))
                             (remove (comp nil? second)))
                            system)
                      extra-handlers)]
        (assoc app
               :makina/state :loaded
               :makina/system system
               :makina/handlers handlers)))
    (throw (IllegalArgumentException. (str "System EDN file " config-source " not found on classpath.")))))

(defn load! [app]
  (swap! app load*))

(defn start!
  ([!app]
   (start! !app nil))
  ([!app ks]
   (let [v (swap! !app
                  (fn [{:makina/keys [state] :as app}]
                    (let [{:makina/keys [handlers system] :as app} (if (= :loaded state) app (load* app))]
                      (update app :makina/system sys/start handlers (if (seq ks) ks (keys system))))))]
     (if-let [{:makina/keys [id type config error]} (some #(when (:makina/error (val %))
                                                             (val %))
                                                          (:makina/system v))]
       (throw (ex-info {:id type :type type :config config}
                       "Component failed to start"
                       error))
       (sys/value (:makina/system v))))))

(defn stop!
  ([!app]
   (stop! !app (keys (:makina/system @!app))))
  ([!app ks]
   (let [v (swap! !app
                  (fn [{:makina/keys [handlers system] :as app}]
                    (assoc app
                           :makina/system
                           (sys/stop (update-vals system #(dissoc % :makina/error))
                                     handlers
                                     ks))))]
     (if-let [e (some (comp :makina/error val) (:makina/system v))]
       (throw e)
       (sys/value (:makina/system v))))))

(defn value [!app]
  (-> @!app :makina/system sys/value))

(defn component [!app id]
  (-> @!app :makina/system (sys/component id)))

(defn prep-refresh [app-var-sym]
  (let [ns-name (gensym "lambdaisland.makine.temp")
        ns (create-ns ns-name)
        !app @(resolve app-var-sym)]
    ((requiring-resolve 'clojure.tools.namespace.repl/disable-reload!) ns)
    (intern ns 'after-load (fn after-load []
                             ((requiring-resolve `start!) @(requiring-resolve app-var-sym))
                             (remove-ns ns-name)))
    (stop! !app)
    (swap! !app assoc :makina/state :not-loaded :makina/system nil)
    (symbol (str ns-name) "after-load")))

(defn refresh
  [app-var-sym]
  ((requiring-resolve 'clojure.tools.namespace.repl/refresh) :after (prep-refresh app-var-sym)))

(defn refresh-all
  [app-var-sym]
  ((requiring-resolve 'clojure.tools.namespace.repl/refresh-all) :after (prep-refresh app-var-sym)))

;; ;; this
;; {:http/server {:makina/config {:port 8080}
;;                :makina/value #<jetty instance>}}

;; ;; vs this
;; {:makina/components
;;  {:http/server {:makina/config {:port 8080}
;;                 :makina/value #<jetty instance>}}

;;  :makina/handlers
;;  {:http/server {:start jetty/run-jetty
;;                 :stop #(.stop %)}}}
