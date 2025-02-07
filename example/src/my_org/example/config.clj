(ns my-org.example.config
  (:refer-clojure :exclude [get])
  (:require
   [lambdaisland.config :as config]
   [lambdaisland.config.cli :as config-cli]
   [lambdaisland.makina.app :as app]))

(def prefix "my_org_example")
(def cli-opts (atom {}))
(def config (config-cli/add-provider
             (config/create {:prefix prefix})
             cli-opts))

(def get (partial config/get config))
(def source (partial config/source config))
(def sources (partial config/sources config))
(def entries (partial config/entries config))
(def reload! (partial config/reload! config))

(def system
  (app/create
   {:prefix prefix
    :data-readers {'config get}}))

(def load! (partial app/load! system))
(def start! (partial app/start! system))
(def stop! (partial app/stop! system))

(get :http/port)
(entries)
