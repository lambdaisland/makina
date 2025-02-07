(ns lambdaisland.makina.system
  "Purely functional API for starting/stopping systems of components.

  This is the Makina base layer, and is at your disposal when adapting it to
  niche use cases. See the other namespaces for common use cases."
  (:require
   [clojure.walk :as walk]
   [lambdaisland.data-printers.auto :as dp]))

(defrecord Ref [id]) ;; Reference a specific component by its id
(defrecord Refset [t]) ;; Reference a set of components by type

(defn ref? [o] (instance? Ref o))
(defn refset? [o] (instance? Refset o))

(dp/register-printer Ref 'makina/ref :id)
(dp/register-printer Refset 'makina/refset :t)

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

(defn expand-keys
  "Add any dependent keys to the key sequence `ks`. The result is topoligically
  sorted, so that if key `:a` has a reference to `:b`, then `:b` will occur in
  the result before `:a`."
  [sys ks]
  (let [recurse (fn recurse [acc k]
                  (let [v (get sys k)
                        ref-ids (map :id (find-by-pred ref? (:makina/config v)))
                        refset-ids (->> (:makina/config v)
                                        (find-by-pred refset?)
                                        (mapcat (comp (partial ids-by-type sys) :t)))]
                    (reduce recurse (cons k (remove #{k} acc)) (distinct (concat ref-ids refset-ids)))))]
    (loop [[k & ks] ks
           acc ks]
      (if k
        (let [acc (recurse acc k)]
          (recur ks acc))
        acc))))

(defn replace-refs [sys v]
  (walk/postwalk
   (fn [o]
     (cond
       (ref? o)    (get-in sys [(:id o) :makina/value])
       (refset? o) (values-by-type sys (:t o))
       :else       o))
   v))

(defn find-handler
  "Find a handler for a component of type `t` and a given signal. The map of
  handlers is structured as `t -> sig -> fn`. For both the type `t` or the
  signal `sig` the key `:default` is checked as a fallback.

  For a given type (or for the `:default` type) instead of the `sig -> fn`
  mapping a function may be supplied, which is equivalent to `{:start fn}`."
  [handlers t sig]
  (let [handlers (walk/prewalk #(if (var? %) @% %) handlers)]
    (def hhh handlers)
    (or (when (= :start sig)
          (or (let [f (get handlers t)]
                (when (fn? f) f))
              (let [f (get handlers :default)]
                (when (fn? f) f))))
        (get-in handlers [t sig])
        (get-in handlers [t :default])
        (get-in handlers [:default sig])
        (get-in handlers [:default :default])
        identity)))

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
          f (find-handler handlers (:makina/type c) sig)
          v (try
              (f (if (map? v)
                   (assoc v :makina/signal sig)
                   v))
              (catch Throwable t
                [::error t]))]
      (if (= ::error (when (vector? v) (first v)))
        (reduced
         (-> sys
             (assoc-in [k :makina/state] :error)
             (assoc-in [k :makina/error] (second v))))
        (-> sys
            (assoc-in [k :makina/state] dest-state)
            (assoc-in [k :makina/value] (if (map? v)
                                          (dissoc v :makina/signal)
                                          v))
            (dissoc :makina/error))))))

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
   (update-vals sys :makina/value))
  ([sys id]
   (get (value sys) id)))

(defn start
  "Start the system, running the `start` handler for some/all keys/components, in
  topological order."
  ([sys handlers]
   (start sys handlers (keys sys)))
  ([sys handlers ks]
   (signal-keyseq (system sys) handlers :start :started (expand-keys sys ks))))

(defn stop
  "Stop the system, running the `stop` handler for some/all keys/components, in
  reverse topological order."
  ([sys handlers]
   (stop sys handlers (keys sys)))
  ([sys handlers ks]
   (update-vals
    (signal-keyseq (system sys) handlers :stop :stopped (reverse (expand-keys sys ks)))
    (fn [{:makina/keys [state config] :as comp}]
      (if (= :stopped state)
        (assoc comp :makina/value config)
        comp)))))
