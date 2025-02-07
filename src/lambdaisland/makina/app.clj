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
         :makina/handlers handlers
         :makina/data-readers (merge
                               {'ref    sys/->Ref
                                'refset sys/->Refset}
                               data-readers)}))

(defn load* [{:makina/keys [state config-source data-readers handlers] :as app}]
  (if-let [sys-edn (io/resource config-source)]
    (binding [*data-readers* (merge *data-readers* data-readers)]
      (let [system   (sys/system (aero.core/read-config sys-edn))
            handlers (merge
                      (into {}
                            (comp
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
                      handlers)]
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

(defn reset
  [!app])

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
