(ns my-org.example
  (:require
   [clojure.pprint :as pprint]
   [lambdaisland.cli :as cli]
   [my-org.example.config :as config]))

(defn run-cmd [_]
  (config/start!))

(defn show-config-cmd [_]
  (config/load!)
  (pprint/print-table
   (for [[k {:keys [val source]}] (config/entries)]
     {"key" k "value" val "source" source})))

(def commands
  ["run" #'run-cmd
   "show-config" #'show-config-cmd])

(def flags
  ["--port=<port>" {:key :http/port
                    :doc "Set the HTTP port"}])

(defn -main [& args]
  (cli/dispatch
   {:name     "my-org.example"
    :doc      "This is my cool CLI tool. Use it well."
    :commands commands
    :flags    flags
    :middleware [(fn [cmd]
                   (fn [opts]
                     (reset! config/cli-opts opts)
                     (config/reload!)
                     (cmd opts)))]}
   args))
