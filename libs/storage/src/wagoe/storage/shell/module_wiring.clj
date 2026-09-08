(ns wagoe.storage.shell.module-wiring
  "Integrant lifecycle for the storage module.

   Config key:

   :wagoe/storage
     {:provider :local :root \"uploads\"}                ; local filesystem
     {:provider :s3  :bucket \"b\" :region \"eu-west-1\"}  ; AWS S3 / compatible
     {:provider :gcs :bucket \"b\" :project-id \"p\"}      ; Google Cloud Storage

     Returns {:provider <kw> :storage <IFileStorage> :service <IStorageService>}.
     Consumers (e.g. :wagoe/storage-routes) use :service.

   The `:local` provider accepts the catalogue's `:root` as an alias for the
   local adapter's `:base-path`."
  (:require [integrant.core :as ig]
            [wagoe.storage.shell.adapters.local :as local]
            [wagoe.storage.shell.adapters.s3 :as s3]
            [wagoe.storage.shell.adapters.gcs :as gcs]
            [wagoe.storage.shell.adapters.image-processor :as image-processor]
            [wagoe.storage.shell.service :as service]
            [wagoe.storage.shell.http-handlers :as http-handlers]
            [clojure.tools.logging :as log]))

(defn- build-file-storage
  "Construct the IFileStorage adapter for the configured provider."
  [{:keys [provider] :as config} logger]
  (case provider
    :local (local/create-local-storage
            {:base-path      (or (:root config) (:base-path config))
             :url-base       (:url-base config)
             :signing-secret (:signing-secret config)
             :logger         logger})
    :s3    (s3/create-s3-storage (assoc config :logger logger))
    :gcs   (gcs/create-gcs-storage (assoc config :logger logger))
    (throw (ex-info "Unknown storage provider"
                    {:type :unknown-provider :provider provider}))))

(defmethod ig/init-key :wagoe/storage
  [_ {:keys [provider logger] :or {provider :local} :as config}]
  (log/info "Initializing storage component" {:provider provider})
  (let [file-storage (build-file-storage (assoc config :provider provider) logger)
        processor    (image-processor/create-image-processor {:logger logger})
        svc          (service/create-storage-service
                      {:storage file-storage :image-processor processor :logger logger})]
    (log/info "Storage component initialized" {:provider provider})
    {:provider       provider
     :storage        file-storage
     :service        svc
     ;; Where the routes sit, when they are mounted. Configurable because a
     ;; signed URL has to arrive at the route that verifies it: the adapter
     ;; emits `<url-base>/<key>?expires&signature`, so `:url-base` must be
     ;; this route's public URL (BOU-346 review).
     :http-base-path (:http-base-path config)}))

(defmethod ig/halt-key! :wagoe/storage
  [_ {:keys [provider storage]}]
  (log/info "Halting storage component" {:provider provider})
  ;; Release cloud SDK clients; local has nothing to close.
  (case provider
    :s3  (s3/close-s3-storage storage)
    :gcs (gcs/close-gcs-storage storage)
    nil)
  nil)

;; =============================================================================
;; Storage Routes Component
;; =============================================================================

(defmethod ig/init-key :wagoe/storage-routes
  [_ {:keys [storage]}]
  (log/info "Initializing storage routes")
  ;; The signing secret travels with the routes: when one is configured the
  ;; URLs this module issues are signed and expiring, and the route that
  ;; serves them has to enforce that (BOU-346 review).
  {:api    (http-handlers/storage-routes
            (:service storage)
            {:signing-secret (:signing-secret (:storage storage))
             :base-path      (:http-base-path storage)})
   :web    []
   :static []})

(defmethod ig/halt-key! :wagoe/storage-routes
  [_ _routes]
  ;; Routes are pure data — no cleanup.
  nil)

;; =============================================================================
;; Module graph
;; =============================================================================

(defn ig-config
  "This module's Integrant entries, for `wagoe.platform.shell.system.config`.

   The routes component is assembled with the module (BOU-346 — it used to be
   defined and never built by anyone), but mounting it is opt-in:
   `:expose-http? true`.

   Opt-in because these endpoints carry no authorization of their own: upload,
   download, delete and signed-URL all act on any key the caller names, and
   the adapter cannot tell a private object from a public one. Mounting them
   on every application that stores a file would publish anonymous delete.
   Enable it when the routes sit behind your own auth.

   Two settings have to agree when `:signing-secret` is set, and nothing can
   check it from here: `:http-base-path` places the routes (default
   `/storage`, mounted under `/api/v1`), and `:url-base` is the public URL a
   signed link points at. The adapter emits `<url-base>/<key>`, so `:url-base`
   must resolve to this module's download route — the only thing that verifies
   the signature. Aimed at a CDN instead, the link works and is never checked
   (BOU-346 review)."
  [settings _ctx]
  (let [settings (or settings {:provider :local})
        ;; Explicitly true, not merely truthy. Config values reach this through
        ;; `#env`, which yields strings — so `:expose-http? #env …` set to
        ;; "false" would publish anonymous upload and delete. A flag that
        ;; decides whether an unauthenticated surface is reachable fails
        ;; closed on anything it does not recognise.
        expose?  (contains? #{true "true"} (:expose-http? settings))]
    (cond-> {:components
             {:wagoe/storage        settings
              :wagoe/storage-routes {:storage (ig/ref :wagoe/storage)}}}
      expose?
      (assoc :routes [(ig/ref :wagoe/storage-routes)]))))
