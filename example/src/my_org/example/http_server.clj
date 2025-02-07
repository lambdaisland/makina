(ns my-org.example.http-server)

(def component
  {:start (fn [config]
            (println (str "Starting server at http://localhost:" (:port config)))
            (assoc config :started true))})
