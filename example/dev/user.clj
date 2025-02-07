(ns user)

(defmacro jit [sym]
  `(requiring-resolve '~sym))

(defn go
  ([]
   ((jit my-org.example.config/start!)))
  ([& ks]
   ((jit my-org.example.config/start!) ks)))

(defn stop! []
  ((jit my-org.example.config/stop!)))
