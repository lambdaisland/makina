(ns user)

(defmacro jit [sym]
  `(requiring-resolve '~sym))

(defn go
  ([]
   ((jit my-org.example.config/start!)))
  ([& ks]
   (apply (jit my-org.example.config/start!) ks)))

(defn stop! []
  ((jit my-org.example.config/stop!)))
