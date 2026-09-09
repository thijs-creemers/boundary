(ns wagoe.audience.shell.module-wiring
  "Integrant lifecycle management for the audience module.

   Config keys:

   :wagoe/audience-db-schema
     {:db-ctx (ig/ref :wagoe/db-context)}   ; creates the module's tables

   :wagoe/audience-user-source
     {:db-ctx (ig/ref :wagoe/db-context)
      :table  :users}                 ; the users table this app segments on

   :wagoe/audience
     {:db-ctx           (ig/ref :wagoe/db-context)
      :cache-service    (ig/ref :wagoe/cache)
      :user-data-source (ig/ref :wagoe/audience-user-source)}

     Returns {:store <IAudienceRepository> :resolver <IAudienceResolver> :cache <IAudienceCache>}

   :wagoe/audience-routes
     {:audience-service (ig/ref :wagoe/audience)}

     Returns {:api [...] :web [...]} for composition
     by the HTTP handler."
  (:require [integrant.core :as ig]
            [wagoe.audience.shell.adapters.user-sql :as user-sql]
            [wagoe.audience.shell.persistence :as persistence]
            [wagoe.audience.shell.cache :as cache]
            [wagoe.audience.shell.service :as service]
            [wagoe.audience.shell.http :as audience-http]
            [clojure.tools.logging :as log]))

(defmethod ig/init-key :wagoe/audience-db-schema
  [_ {:keys [db-ctx]}]
  (persistence/initialize-audience-schema! (:datasource db-ctx))
  {:status :initialized})

(defmethod ig/halt-key! :wagoe/audience-db-schema
  [_ _]
  nil)

(defmethod ig/init-key :wagoe/audience-user-source
  [_ {:keys [db-ctx table]}]
  (log/info "Initializing audience user data source" {:table (or table :users)})
  (user-sql/create-sql-user-data-source (:datasource db-ctx) table))

(defmethod ig/halt-key! :wagoe/audience-user-source
  [_ _source]
  nil)

(defmethod ig/init-key :wagoe/audience
  [_ {:keys [db-ctx cache-service user-data-source db-schema]}]
  (log/info "Initializing audience component")
  (when-not db-schema
    (log/warn "Audience component started without :db-schema — its tables may not exist"))
  (let [datasource (:datasource db-ctx)
        store      (persistence/create-audience-store datasource)
        acache     (cache/create-audience-cache datasource cache-service)
        resolver   (service/create-audience-service
                    {:repository       store
                     :cache            acache
                     :user-data-source user-data-source})]
    (when-not user-data-source
      (throw (ex-info "Audience component requires :user-data-source. Wire an IUserDataSource implementation via Integrant config."
                      {:type :configuration-error :missing-key :user-data-source})))
    {:store    store
     :resolver resolver
     :cache    acache}))

(defmethod ig/halt-key! :wagoe/audience
  [_ _component]
  (log/info "Halting audience component")
  nil)

;; =============================================================================
;; Audience Routes Component
;; =============================================================================

(defmethod ig/init-key :wagoe/audience-routes
  [_ {:keys [audience-service]}]
  (log/info "Initializing audience routes")
  {:api (audience-http/audience-api-routes (:resolver audience-service) (:store audience-service))
   :web (audience-http/audience-web-routes (:resolver audience-service) (:store audience-service))})

(defmethod ig/halt-key! :wagoe/audience-routes
  [_ _routes]
  ;; Routes are pure data — no cleanup needed
  nil)

;; =============================================================================
;; Module graph
;; =============================================================================

(defn ig-config
  "This module's Integrant entries, for `wagoe.platform.shell.system.config`.

   There was no `ig-config` here at all, so the platform fell back to emitting
   `:wagoe/audience` as a bare settings map: the service was never built and the
   routes were never mounted. Under that fallback the module could not have
   booted anyway — its `:user-data-source` had no implementation (BOU-419).

   `:cache-service` is passed only when the cache module is on, the way
   user-service takes its optional cache: a ref to a component the config does
   not contain is a dangling ref, and the boot fails on it."
  [settings {:keys [enabled]}]
  (let [settings (or settings {})]
    {:components
     {:wagoe/audience-db-schema   {:db-ctx (ig/ref :wagoe/db-context)}
      :wagoe/audience-user-source {:db-ctx (ig/ref :wagoe/db-context)
                                   :table  (:users-table settings)}
      :wagoe/audience             (cond-> {:db-ctx           (ig/ref :wagoe/db-context)
                                           :db-schema        (ig/ref :wagoe/audience-db-schema)
                                           :user-data-source (ig/ref :wagoe/audience-user-source)}
                                    (contains? (or enabled #{}) :wagoe/cache)
                                    (assoc :cache-service (ig/ref :wagoe/cache)))
      :wagoe/audience-routes      {:audience-service (ig/ref :wagoe/audience)}}
     :routes [(ig/ref :wagoe/audience-routes)]}))
