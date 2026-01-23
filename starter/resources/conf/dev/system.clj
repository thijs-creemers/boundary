(ns conf.dev.system
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [integrant.core :as ig])
  (:import (java.io PushbackReader)))

(defn read-config [profile]
  (aero/read-config (-> (str "resources/conf/" profile "/config.edn")
                        io/file
                        io/reader
                        PushbackReader.)
                    {:profile profile}))

(defn -main [& _]
  (let [profile (or (System/getenv "BND_ENV") "development")
        config (read-config profile)]
    (ig/init config)))