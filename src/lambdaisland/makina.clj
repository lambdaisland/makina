(ns lambdaisland.makina
  (:require
   [clojure.walk :as walk]
   [lambdaisland.data-printers :as dp]))

(defrecord Ref [id])
(defn ref? [o] (instance? Ref o))

(dp/register-print Ref 'makina/ref :id)
(dp/register-pprint Ref 'makina/ref :id)

(defn find-refs [o]
  (cond
    (ref? o)
    [(:id o)]
    (map? o)
    (find-refs (vals o))
    (coll? o)
    (mapcat find-refs o)))

(defn topo-sort-keys [sys]
  (let [recurse (fn recurse [acc k]
                  (let [v (get sys k)
                        refs (find-refs v)]
                    (reduce recurse (cons k (remove #{k} acc)) refs)))]
    (loop [acc ()
           sys sys]
      (if (seq sys)
        (let [k (key (first sys))
              acc (recurse acc k)]
          (recur acc (apply dissoc sys acc)))
        acc))))

(defn signal-key [sys handlers src-state sig dest-state k]
  (if (= dest-state (get-in sys [k :makina/state]))
    sys
    (let [c    (get sys k)
          v    (:makina/value c)
          refs (find-refs v)
          sys  (reduce
                (fn [sys k]
                  (signal-key sys handlers src-state sig dest-state k))
                sys
                (remove (set (map key (filter (comp src-state val) sys))) refs))
          v    (walk/postwalk
                (fn [o]
                  (if (ref? o)
                    (get-in sys [(:id o) :makina/value])
                    o))
                v)]
      (-> sys
          (assoc-in [k :makina/state] dest-state)
          (assoc-in [k :makina/value]
                    (let [f (or (get-in handlers [(:makina/type c) sig])
                                (get-in handlers [(:makina/type c) :default])
                                (get-in handlers [:default sig])
                                identity)
                          v (f (if (map? v)
                                 (assoc v :makina/signal sig)
                                 v))]
                      (if (map? v)
                        (dissoc v :makina/signal)
                        v)))))))

(defn signal [sys handlers src-state sig dest-state]
  (reduce (fn [sys k]
            (signal-key sys handlers src-state sig dest-state k))
          sys
          (topo-sort-keys sys)))

(defn system [config]
  (into {}
        (map (fn [[k v]]
               (if (:makina/value v)
                 [k v]
                 [k {:makina/state :stopped
                     :makina/config v
                     :makina/value (if (map? v)
                                     (-> v
                                         (assoc :makina/type (:makina/type v k))
                                         (assoc :makina/id k))
                                     v)
                     :makina/type (:makina/type v k)}])))
        config))

(defn value [sys]
  (update-vals sys :makina/value))

(defn start
  ([sys handlers]
   (signal (system sys) handlers :stopped :start :started))
  ([sys handlers ks]
   (if (coll? ks)
     (reduce #(start %1 handlers %2) sys ks)
     (signal-key (system sys) handlers :stopped :start :started ks))))

(defn stop
  ([sys handlers]
   (signal (system sys) handlers :started :stop :stopped))
  ([sys handlers ks]
   (if (coll? ks)
     (reduce #(start %1 handlers %2) sys ks)
     (signal-key (system sys) handlers :started :stop :stopped ks))))

(def system-config
  {:a {:makina/type :foo
       :bar 123
       :baz (->Ref :b)}
   :b {:cfg :x}
   :d (->Ref :e)
   :c (->Ref :d)
   :e 1})

(def handlers
  {:foo {:start #(update % :bar inc)
         :stop #(update % :bar dec)}
   })

(value (stop (start system-config handlers) handlers))

;; (value
;;  (->  (system system-config)
;;       (signal
;;        :stopped :start :started
;;        )))


;; ;; - start/stop individual keys
;; ;; - error handling
;; ;; - track component state

;; ;; (signal-key system-config {} )

;; ;; (system system-config)

;; ;; (set! *print-namespace-maps* false)

;; ;; (def states
;; ;;   {:stopped {:start :started}
;; ;;    :started {:stop :stopped}})
