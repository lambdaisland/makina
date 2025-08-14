(ns repl-sessions.scratch
  (:require [lambdaisland.makina.system :as sys]))

(sys/system
 {:my.app/db {:jdbc-url "..."}
  :my.app/http-server {:db #makina/ref :my.app/db}})

(class
 #makina/ref :foo)


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



(def system-config
  {:a {:makina/type :foo
       :bar 1
       :baz (->Ref :b)}
   :b {:cfg :x}
   :d (->Ref :e)
   :c [(->Ref :d) (->Ref :a)]
   :e 1})

(expand-keys (system system-config) [:c :a])

(start system-config {:default {:default (fn [o]
                                           (tap> o)
                                           o)}})

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
