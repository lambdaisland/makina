(ns lambdaisland.makina.app
  "Convenience layer with specific policy conventions

  Rather than working on 'system' values (map with component values), it works
  on 'app' values, which contain a system value (:makina/system), as well as
  additional bookkeeping (:makina/state, :makina/config, etc).

  This 'app value' is in turn wrapped in an atom, so we have an persistent
  identity to operate on.
  "
  {:clojure.tools.namespace.repl/load false}
  (:require
   [lambdaisland.makina.system :as sys]
   [aero.core :as aero]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]))

(defn- try-resolve [sym]
  (try
    (requiring-resolve sym)
    (catch java.io.FileNotFoundException _)))

(defn create*
  "Functional version of [[create!]], returns the system value, rather than the
  application atom."
  [{:keys [prefix ns-prefix config data-readers handlers profile]}]
  (cond-> {:makina/state :not-loaded
           :makina/config (or config (str prefix "/system.edn"))
           :makina/extra-handlers handlers
           :makina/data-readers (merge
                                 {'ref    sys/->Ref
                                  'refset sys/->Refset}
                                 data-readers)}
    ns-prefix
    (assoc :makina/ns-prefix ns-prefix)
    profile
    (assoc :makina/profile profile)))

(defn create
  "Create a new Makina app

  This is an atom containing a Makina system value, in the `:not-loaded` state.
  The system configuration is loaded from `<prefix>/system.edn` on the
  classpath, e.g. the `resources` folder.

  - `:prefix` - classpath prefix to find `system.edn`
  - `:config` - override where the config is loaded from, map, function, or classpath location of an EDN file
  - `:ns-prefix` - when auto-loading handlers, prefix each component name before loading
  - `:data-readers` - like [[clojure.core/*data-readers*]], used while loading `system.edn`
  - `:handlers` - explicit handlers, overrides the auto-load/auto-resolve functionality for specific components
  "
  [{:keys [prefix ns-prefix config data-readers handlers] :as opts}]
  (atom (create* opts)))

(defn- prefix-ns
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

(defn- system* [{:makina/keys [state extra-handlers ns-prefix] :as app} config]
  (let [system (sys/system config)]
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

(defn load*
  "Functional version of [[load!]], takes the system value, rather than the
  application atom."
  [{:makina/keys [config data-readers extra-handlers profile] :as app}]
  (system*
   app
   (cond
     (map? config)
     config

     (and (var? config) (map? @config))
     @config

     (or (fn? config) (var? config))
     (config)

     (instance? java.io.File config)
     (if (.exists ^java.io.File config)
       (binding [*data-readers* (merge *data-readers* data-readers)]
         (aero/read-config config {:profile (or profile :default)}))
       (throw (IllegalArgumentException. (str "System EDN file " config " does not exist."))))

     (instance? java.net.URL config)
     (binding [*data-readers* (merge *data-readers* data-readers)]
       (aero/read-config config {:profile (or profile :default)}))

     (string? config)
     (if-let [sys-edn (io/resource config)]
       (binding [*data-readers* (merge *data-readers* data-readers)]
         (aero/read-config sys-edn {:profile (or profile :default)}))
       (throw (IllegalArgumentException. (str "System EDN file " config " not found on classpath."))))
     :else
     (throw (IllegalArgumentException. (str ":makina/config should be map, function, or string, got " config))))))

(defn load! [app]
  (swap! app load*))

(defn start*
  "Functional version of [[start!]], takes the system value, rather than the
  application atom."
  ([app]
   (start* app nil))
  ([{:makina/keys [state] :as app} ks]
   (let [{:makina/keys [handlers system] :as app} (if (= :not-loaded state) (load* app) app)
         ks (if (seq ks) ks (keys system))
         started-app (update app :makina/system sys/start handlers ks)]
     (assoc started-app :makina/state (if (sys/error (:makina/system started-app))
                                        :error
                                        :started)))))

(defn start!
  "Start the application

  Move the app to the `:started` state, if it's not started yet. If the current
  state is `:not-loaded`, first attempt to auto-load and resolve any missing
  handlers. If the app is partially started (e.g. in an `:error` state), will
  attempt to start any components that are stopped or have errored."
  ([!app]
   (start! !app nil))
  ([!app ks]
   (let [v (swap! !app start* ks)]
     (if-let [{:makina/keys [id type config error]} (some #(when (:makina/error (val %))
                                                             (val %))
                                                          (:makina/system v))]
       (throw (ex-info "Component failed to start"
                       {:id type :type type :config config}
                       error))
       (sys/value (:makina/system v))))))

(defn stop*
  "Functional version of [[stop!]], takes the system value, rather than the
  application atom."
  ([sys]
   (stop* sys nil))
  ([{:makina/keys [handlers system] :as app} ks]
   (let [ks (if (seq ks) ks (keys system))]
     (assoc app
            :makina/system
            (sys/stop (update-vals system #(dissoc % :makina/error))
                      handlers
                      ks)))))

(defn stop!
  "Stop the application

  Move to the `:stopped` state, stopping any previously started components"
  ([!app]
   (stop! !app nil))
  ([!app ks]
   (let [v (swap! !app stop* ks)]
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

(defn error*
  "Functional version of [[error]]"
  [app]
  (-> app :makina/system sys/error))

(defn error
  "If a component is in the error state, return the Error.

  Generally there is never more than one, since system startup stops when an
  error is encountered."
  [!app]
  (error* @!app))

(defn started-keys
  "Sequence of keys of components that are in the `:started` state"
  [app]
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
  "Stop the app, call [[clojure.tools.namespace.repl/refresh]], then restart

  Reloads any namespaces that have changed since the last call to `refresh`.
  This gives you a fresh state. `tools.namespace` needs to be added manually as
  a dependency, Makina does not automatically pull it in.

  - `app-var-sym` - a fully qualified symbol, a var with this name must exist,
    and contain a Makina app (atom)"
  [app-var-sym]
  ;; java.lang.IllegalStateException: Can't change/establish root binding of: *ns* with set
  ;; [clojure.tools.namespace.repl$refresh_scanned invokeStatic "repl.clj" 136]
  (binding [*ns* (the-ns 'user)]
    ((requiring-resolve 'clojure.tools.namespace.repl/refresh) :after (prep-refresh app-var-sym))))

(defn refresh-all
  "Stop the app, call [[clojure.tools.namespace.repl/refresh-all]], then restart

  Reloads all namespaces, this may include namespaces that were not loaded
  before. Use [[clojure.tools.namespace.repl/set-refresh-dirs]] to limit the
  source directories that get scanned for namespaces.

  `tools.namespace` needs to be added manually as a dependency, Makina does not
  automatically pull it in.

  - `app-var-sym` - a fully qualified symbol, a var with this name must exist,
    and contain a Makina app (atom)"
  [app-var-sym]
  ;; java.lang.IllegalStateException: Can't change/establish root binding of: *ns* with set
  ;; [clojure.tools.namespace.repl$refresh_scanned invokeStatic "repl.clj" 136]
  (binding [*ns* (the-ns 'user)]
    ((requiring-resolve 'clojure.tools.namespace.repl/refresh-all) :after (prep-refresh app-var-sym))))

(defn restart!
  "Restart either a specific set of keys, or all keys/components that are
  currently in the `:started` state."
  ([!app]
   (restart! !app nil))
  ([!app ks]
   (let [{:makina/keys [system state]} @!app
         ks (if (seq ks) ks (keys system))]
     (if (= :started state)
       (let [started-ks (started-keys @!app)]
         (stop! !app ks)
         (start! !app started-ks))
       (start! !app ks)))))

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
