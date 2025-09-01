(ns lambdaisland.makina.app
  "Convenience layer with specific policy conventions"
  {:clojure.tools.namespace.repl/load false}
  (:require
   [lambdaisland.makina.system :as sys]
   [aero.core :as aero]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]))

(defn try-resolve [sym]
  (try
    (requiring-resolve sym)
    (catch java.io.FileNotFoundException _)))

(defn create [{:keys [prefix ns-prefix config-source data-readers handlers]}]
  (atom (cond-> {:makina/state :not-loaded
                 :makina/config-source (or config-source (str prefix "/system.edn"))
                 :makina/extra-handlers handlers
                 :makina/data-readers (merge
                                       {'ref    sys/->Ref
                                        'refset sys/->Refset}
                                       data-readers)}
          ns-prefix
          (assoc :makina/ns-prefix ns-prefix))))

(defn prefix-ns
  ([p s]
   (symbol
    (str
     (when p (str p "."))
     s)))
  ([p n s]
   (symbol
    (str
     (when p (str p "."))
     n)
    s)))

(defn load-handlers
  [system ns-prefix extra-handlers]
  (merge
   (into {:default {:stop identity}}
         (comp
          (remove (comp (set (keys extra-handlers)) :makina/type val))
          (remove (comp (set (keys extra-handlers)) :makina/id val))
          (map val)
          (mapcat (juxt :makina/type :makina/id))
          (mapcat (fn [t]
                    (cond
                      (or (qualified-symbol? t)
                          (qualified-keyword? t))
                      (when-let [comp (or (try-resolve (prefix-ns ns-prefix t))
                                          (try-resolve (prefix-ns ns-prefix
                                                                  (str (namespace t) "." (name t))
                                                                  "component")))]
                        [[t comp]])

                      (or (simple-symbol? t)
                          (simple-keyword? t))
                      (when-let [comp (try-resolve (prefix-ns ns-prefix (name t) "component"))]
                        [[t comp]]))))
          (remove (comp nil? second)))
         system)
   extra-handlers))

(defn load* [{:makina/keys [state config-source
                            data-readers extra-handlers
                            ns-prefix] :as app}]
  (if-let [sys-edn (io/resource config-source)]
    (binding [*data-readers* (merge *data-readers* data-readers)]
      (let [system (sys/system (aero.core/read-config sys-edn))]
        (try
          (let [handlers (load-handlers system ns-prefix extra-handlers)]
            (assoc app
                   :makina/state :loaded
                   :makina/system system
                   :makina/handlers handlers))
          (catch Exception e
            (throw (ex-info "Failed to load handlers"
                            (assoc app
                                   :makina/state :load-error
                                   :makina/error e
                                   :makina/system system)
                            e))))))
    (throw (IllegalArgumentException. (str "System EDN file " config-source " not found on classpath.")))))

(defn load! [app]
  (swap! app load*))

(defn start!
  ([!app]
   (start! !app nil))
  ([!app ks]
   (let [v (swap! !app
                  (fn [{:makina/keys [state] :as app}]
                    (let [{:makina/keys [handlers system] :as app} (if (= :not-loaded state) (load* app) app)
                          ks (if (seq ks) ks (keys system))
                          started-app (update app :makina/system sys/start handlers ks)]
                      (assoc started-app :makina/state (if (sys/error (:makina/system started-app))
                                                         :error
                                                         :started)))))]
     (if-let [{:makina/keys [id type config error]} (some #(when (:makina/error (val %))
                                                             (val %))
                                                          (:makina/system v))]
       (throw (ex-info "Component failed to start"
                       {:id type :type type :config config}
                       error))
       (sys/value (:makina/system v))))))

(defn stop!
  ([!app]
   (stop! !app nil))
  ([!app ks]
   (let [v (swap! !app
                  (fn [{:makina/keys [handlers system] :as app}]
                    (let [ks (if (seq ks) ks (keys system))]
                      (assoc app
                             :makina/system
                             (sys/stop (update-vals system #(dissoc % :makina/error))
                                       handlers
                                       ks)))))]
     (if-let [e (some (comp :makina/error val) (:makina/system v))]
       (throw e)
       (sys/value (:makina/system v))))))

(defn value
  "System value, a map of all component values"
  [!app]
  (-> @!app :makina/system sys/value))

(defn component
  "Retrieve component value"
  [!app id]
  (-> @!app :makina/system (sys/component id)))

(defn state
  "System or component state"
  ([!app]
   (-> @!app :makina/system sys/state))
  ([!app id]
   (-> @!app :makina/system (sys/state id))))

(defn error
  "If a component is in the error state, return the Error.

  Generally there is never more than one, since system startup stops when an
  error is encountered."
  [!app]
  (-> @!app :makina/system sys/error))

(defn started-keys [app]
  (->> (:makina/system app)
       vals
       (filter (comp #{:started} :makina/state))
       (map :makina/id)))

(defn prep-refresh [app-var-sym]
  (let [ns-name (gensym "lambdaisland.makina.temp")
        ns (create-ns ns-name)
        !app @(resolve app-var-sym)
        ks (started-keys @!app)]
    ((requiring-resolve 'clojure.tools.namespace.repl/disable-reload!) ns)
    (intern ns 'after-load (fn after-load []
                             ((requiring-resolve `start!) @(requiring-resolve app-var-sym) ks)
                             (remove-ns ns-name)))
    (stop! !app)
    (swap! !app assoc :makina/state :not-loaded :makina/system nil)
    (symbol (str ns-name) "after-load")))

(defn refresh
  [app-var-sym]
  ;; java.lang.IllegalStateException: Can't change/establish root binding of: *ns* with set
  ;; [clojure.tools.namespace.repl$refresh_scanned invokeStatic "repl.clj" 136]
  (binding [*ns* (the-ns 'user)]
    ((requiring-resolve 'clojure.tools.namespace.repl/refresh) :after (prep-refresh app-var-sym))))

(defn refresh-all
  [app-var-sym]
  ;; java.lang.IllegalStateException: Can't change/establish root binding of: *ns* with set
  ;; [clojure.tools.namespace.repl$refresh_scanned invokeStatic "repl.clj" 136]
  (binding [*ns* (the-ns 'user)]
    ((requiring-resolve 'clojure.tools.namespace.repl/refresh-all) :after (prep-refresh app-var-sym))))

(defn restart! [!app ks]
  (let [{:makina/keys [system state]} @!app
        ks (if (seq ks) ks (keys system))]
    (if (= :started state)
      (let [started-ks (started-keys @!app)]
        (stop! !app ks)
        (start! !app started-ks))
      (start! !app ks))))

(defn print-table
  "Show a table with the components in the system with their state, in the order
  they were started."
  [!app]
  (let [app @!app
        components (vals (:makina/system app))]
    (print "  System state =" (:makina/state app))
    (->> (concat
          (->> (remove (comp #{:stopped} :makina/state) components)
               (sort-by :makina/timestamp))
          (filter (comp #{:stopped} :makina/state) components))
         (map #(select-keys % [:makina/id :makina/state]))
         pprint/print-table)

    (when-let [e (error !app)]
      (println "  Error in" (str (some #(when (:makina/error %) (:makina/id %)) components) ":") (.getClass e))
      (println "  Message:" (.getMessage e))
      (when-let [ed (ex-data e)]
        (println "  ex-data:" (pr-str ed))))))
