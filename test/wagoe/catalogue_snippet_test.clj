(ns wagoe.catalogue-snippet-test
  "Does the config `wagoe add <module>` writes actually boot the module?

   `modules-catalogue.edn` carries a `:config-snippet` per module, injected into
   `:active` by `wagoe add`. Audience's still described the wiring BOU-419
   replaced: `#ig/ref` values, which `:active` holds settings not components,
   and a ref to `:wagoe/user-data-source` — a key that has never existed. So
   `wagoe add audience` wrote a config the application must not have (BOU-427).

   Read from the catalogue and booted, because a snippet is a claim about what
   works and only a boot settles it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.config :as config]
            [wagoe.main :as main]
            [wagoe.system-config :as sys-config]))

(defn- catalogue []
  (edn/read-string {:readers {'ig/ref (fn [k] (ig/ref k))}}
                   (slurp (io/file "libs/wagoe-cli/resources/wagoe/cli/modules-catalogue.edn"))))

(defn- module [name*]
  (first (filter #(= name* (:name %)) (:modules (catalogue)))))

(defn- snippet-settings
  "The `:active` entries a snippet injects, as data."
  [snippet]
  (edn/read-string {:readers {'ig/ref (fn [k] (ig/ref k))}}
                   (str "{" snippet "}")))

(deftest ^:integration the-audience-snippet-boots-the-module
  (let [entry (module "audience")]
    (testing "the catalogue was read"
      (is (some? entry) "no audience entry; this would pass vacuously")
      (is (seq (:config-snippet entry))))

    (testing "it names settings, not components"
      ;; The shape of the old one: an application writing `#ig/ref` into
      ;; `:active` is writing the wiring the module builds for itself.
      (let [settings (get (snippet-settings (:config-snippet entry)) :wagoe/audience)]
        (is (map? settings))
        (is (not-any? #(instance? integrant.core.Ref %) (vals settings))
            (str "the snippet puts Integrant refs in :active: " (pr-str settings)))))

    (testing "and a system configured from it comes up"
      (let [settings (get (snippet-settings (:config-snippet entry)) :wagoe/audience)
            cfg      (assoc-in (config/load-config {:profile :test})
                               [:active :wagoe/audience] settings)
            system   (ig/init (main/worker-ig-config (sys-config/ig-config cfg)))]
        (try
          (is (some? (:wagoe/audience system)))
          (is (some? (:wagoe/audience-user-source system)))
          (finally (ig/halt! system)))))))
