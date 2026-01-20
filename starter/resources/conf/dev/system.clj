(ns conf.dev.system
  (:require [aero.core :as aero]
            [integrant.core :as ig])
  (:import (java.io PushbackReader)))

(defn read-config [profile]
  (aero/read-config (-> (str "resources/conf/" profile "/config.edn")
                        java.io.File.
                        PushbackReader.)
                    {:profile profile}))

(defn -main [& _]
  (let [profile (or (System/getenv "BND_ENV") "development")
        config (read-config profile)]
    (ig/init config)))