(ns lambdaisland.makina.system
  "Purely functional API for starting/stopping systems of components.

  This is the Makina base layer, and is at your disposal when adapting it to
  custom use cases. See the `app` namespace for more convenience."
  {:clojure.tools.namespace.repl/load false} ; Don't redefine `Ref`/`Refset` or things break
  (:require
   [clojure.walk :as walk]
   #?(:clj [lambdaisland.data-printers.auto :as dp])))

(defrecord Ref [id])   ; Reference a specific component by its id
(defrecord Refset [t]) ; Reference a set of components by type

(defn ref? [o] (instance? Ref o))
(defn refset? [o] (instance? Refset o))

#?(:clj (dp/register-printer Ref 'makina/ref :id))
#?(:clj (dp/register-printer Refset 'makina/refset :t))

(defn find-by-pred
  "Find all reference (`Ref` instances) in o"
  [pred? o]
  (cond
    (pred? o)
    [o]
    (map? o)
    (find-by-pred pred? (vals o))
    (coll? o)
    (mapcat (partial find-by-pred pred?) o)))

(defn entries-by-type [sys t]
  (keep (fn [[k {:makina/keys [type] :as comp}]]
          (when (isa? type t)
            [k comp]))
        sys))

(def ids-by-type (comp (partial map first) entries-by-type))
(def values-by-type (comp (partial map (comp :makina/value second)) entries-by-type))

(defn comp-dependencies
  "Ids of all components this component depends on"
  [sys c]
  (let [ref-ids (map :id (find-by-pred ref? (:makina/config c)))
        refset-ids (->> (:makina/config c)
                        (find-by-pred refset?)
                        (mapcat (comp (partial ids-by-type sys) :t)))]
    (set (concat ref-ids refset-ids))))

(defn sys-graph->
  "Graph of forward dependencies in the system: `{dependent-id #{dependee-id}}`"
  [sys]
  (into {} (map (juxt key #(comp-dependencies sys (val %)))) sys))

(defn sys-graph<-
  "Graph of backward dependencies in the system: `{dependee-id #{dependent-id}}`"
  [sys]
  (let [g (sys-graph-> sys)]
    (reduce (fn [acc k]
              (assoc acc k (into #{}
                                 (keep #(when (contains? (val %) k)
                                          (key %)))
                                 g)))
            {}
            (keys sys))))

(defn subgraph [g ks]
  (loop [q (into clojure.lang.PersistentQueue/EMPTY ks)
         g' {}]
    (let [k (peek q)
          q (pop q)]
      (if-not k
        g'
        (let [ids (get g k)]
          (recur
           (into q (remove #(contains? g' %) ids))
           (assoc g' k ids)))))))

(defn no-incoming-edges [g ids]
  (if-let [vs (seq (vals g))]
    (remove (apply some-fn vs) ids)
    ids))

(defn kahn-topo-sort
  [g]
  (let [no-incoming (no-incoming-edges g (keys g))]
    (loop [g g
           l '()
           q (into '() no-incoming)]
      (let [k (peek q)]
        (if-not k
          (if (empty? g)
            l
            (throw (ex-info "Cycle detected" {:graph g :key k})))
          (let [q (pop q)
                ids (get g k)
                g (dissoc g k)]
            (recur g
                   (conj l k)
                   (into q (no-incoming-edges g ids)))))))))

(defn replace-refs [sys v]
  (walk/postwalk
   (fn [o]
     (cond
       (ref? o)    (get-in sys [(:id o) :makina/value])
       (refset? o) (values-by-type sys (:t o))
       :else       o))
   v))

(defn find-handler
  "Find a handler for a component of type `t` and id `id` and a given signal. The
  map of handlers is structured as `t -> sig -> fn`. For both the type `t` or
  the signal `sig` the key `:default` is checked as a fallback.

  For a given type (or for the `:default` type) instead of the `sig -> fn`
  mapping a function may be supplied, which is equivalent to `{:start fn}`.

  This first searches for a matching handler based on the type, then on the id,
  and then for type `:default`.
  "
  [handlers t id sig]
  (let [handlers (walk/prewalk #(if (var? %) @% %) handlers)
        start?  (= :start sig)
        handler (or
                 ;; look for type
                 (when start?
                   (let [f (get handlers t)]
                     (when (fn? f) f)))
                 (get-in handlers [t sig])
                 (get-in handlers [t :default])
                 ;; look for id
                 (when (not= t id)
                   (or
                    (when start?
                      (let [f (get handlers id)]
                        (when (fn? f) f)))
                    (get-in handlers [id sig])
                    (get-in handlers [id :default])   ))
                 ;; look for default
                 (when start?
                   (let [f (get handlers :default)]
                     (when (fn? f) f)))
                 (get-in handlers [:default sig])
                 (get-in handlers [:default :default]))]
    (when-not handler
      (throw (ex-info (str "No handler found for " [t sig])
                      {:makina/type t
                       :makina/signal sig})))
    handler))

(defn signal-key
  "Signal a single key. Does not recurse, i.e. it is the callers responsibility
  that dependent/dependee keys are signaled in topological (or reverse
  topological) order. You likely don't want to use this directly, but if you
  do [[expand-keys]] is your friend. See [[start]]/[[stop]] for higher level
  functions.

  Takes the system (should be expanded as per [[system]]), and a handlers lookup
  map (see [[find-handler]]). Then transitions the component `k` to `dest-state`
  by invoking the `sig` handler. No-op if `k` is already in the `dest-state`."
  [sys handlers sig dest-state k]
  (if (= dest-state (get-in sys [k :makina/state]))
    sys
    (let [c (get sys k)
          v (replace-refs sys (:makina/value c))
          f (find-handler handlers (:makina/type c) (:makina/id c) sig)
          v (try
              (f (if (map? v)
                   (assoc v
                          :makina/signal sig
                          :makina/id     (:makina/id c)
                          :makina/type   (:makina/type c)
                          :makina/state  (:makina/state c))
                   v))
              (catch Throwable t
                [::error t]))]
      (if (= ::error (when (vector? v) (first v)))
        (reduced
         (-> sys
             (assoc-in [k :makina/timestamp] (java.time.Instant/now))
             (assoc-in [k :makina/state] :error)
             (assoc-in [k :makina/error] (second v))))
        (-> sys
            (assoc-in [k :makina/timestamp] (java.time.Instant/now))
            (assoc-in [k :makina/state] dest-state)
            (assoc-in [k :makina/value] (if (map? v)
                                          (dissoc v :makina/signal)
                                          v))
            (update k dissoc :makina/error))))))

(defn signal-keyseq
  "Signal a number of keys in order"
  [sys handlers sig dest-state ks]
  (reduce (fn [sys k]
            (signal-key sys handlers sig dest-state k))
          sys
          ks))

(defn system
  "Expand a config map (`id -> config`), to a system map (`id -> component`), with
  all components stopped. In a system map each component is a map with keys

  - `:makina/id` The unique identifier of this component
  - `:makina/type` The type of this component, used to find handlers or expand
     refsets, defaults to the `id`
  - `:makina/state` The current state, defaults to `:stopped`
  - `:makina/config` The initial configuration, verbatim. Refs are not yet expanded.
  - `:makina/value` The current value for this component, initially this is the
    initial config + :makina/id + :makine/type, and is then replaced/transformed
    by signals like `:start`

  Idempotent, will not expand keys that already look like components (have a `:makina/state`)
  "
  [config]
  (into {}
        (map (fn [[k v]]
               (if (:makina/state v)
                 [k v]
                 [k {:makina/id k
                     :makina/state :stopped
                     :makina/config v
                     :makina/value (if (map? v)
                                     (-> v
                                         (assoc :makina/type (:makina/type v k))
                                         (assoc :makina/id k))
                                     v)
                     :makina/type (:makina/type v k)}])))
        config))

(defn value
  "System value, map of `:makina/id -> :makina/value`"
  ([sys]
   (update-vals sys :makina/value)))

(defn component
  "Single component value"
  ([sys id]
   (get-in sys [id :makina/value])))

(defn state
  "System or component state"
  ([sys]
   (update-vals sys :makina/state))
  ([sys id]
   (get-in sys [id :makina/state])))

(defn error
  "If a component is in the error state, return the Error.

  Generally there is never more than one, since system startup stops when an
  error is encountered."
  [sys]
  (some :makina/error (vals sys)))

(defn start
  "Start the system, running the `start` handler for some/all keys/components, in
  topological order."
  ([sys handlers]
   (signal-keyseq (system sys) handlers :start :started (kahn-topo-sort (sys-graph-> sys))))
  ([sys handlers ks]
   (signal-keyseq (system sys) handlers :start :started (kahn-topo-sort (subgraph (sys-graph-> sys) ks)))))

(defn stop
  "Stop the system, running the `stop` handler for some/all keys/components, in
  reverse topological order."
  ([sys handlers]
   (stop sys handlers (keys sys)))
  ([sys handlers ks]
   (let [ks (filter (comp #{:started} (partial state sys)) ks)]
     (if-not (seq ks)
       sys
       (update-vals
        (signal-keyseq (system sys) handlers :stop :stopped (kahn-topo-sort (subgraph (sys-graph<- sys) ks)))
        (fn [{:makina/keys [state config] :as comp}]
          (if (= :stopped state)
            (assoc comp :makina/value config)
            comp)))))))
