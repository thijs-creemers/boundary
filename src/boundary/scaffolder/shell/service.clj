(ns boundary.scaffolder.shell.service
  "Scaffolder service implementation for module generation.
   
   Orchestrates template rendering and file generation."
  (:require [boundary.scaffolder.ports :as ports]
            [boundary.scaffolder.schema :as schema]
            [boundary.scaffolder.core.template :as template]
            [boundary.scaffolder.core.generators :as generators]
            [boundary.platform.shell.adapters.filesystem.protocols :as fs-ports]
            [malli.core :as m]))

(defrecord ScaffolderService [file-system]
  ports/IScaffolderService

  (generate-module [this request]
    (try
      ;; Validate request
      (when-not (m/validate schema/ModuleGenerationRequest request)
        (throw (ex-info "Invalid module generation request"
                        {:type :validation-error
                         :errors (m/explain schema/ModuleGenerationRequest request)})))

      ;; Build template context
      (let [ctx (template/build-module-context request)
            module-name (:module-name ctx)
            entity (first (:entities ctx))
            entity-kebab (:entity-kebab entity)
            dry-run? (:dry-run request false)

            ;; Generate source file contents
            schema-content (generators/generate-schema-file ctx)
            ports-content (generators/generate-ports-file ctx)
            core-content (generators/generate-core-file ctx)
            migration-content (generators/generate-migration-file ctx "005")
            service-content (generators/generate-service-file ctx)
            persistence-content (generators/generate-persistence-file ctx)
            http-content (generators/generate-http-file ctx)
            web-handlers-content (generators/generate-web-handlers-file ctx)
            ui-content (generators/generate-ui-file ctx)

            ;; Generate test file contents
            core-test-content (generators/generate-core-test-file ctx)
            persistence-test-content (generators/generate-persistence-test-file ctx)
            service-test-content (generators/generate-service-test-file ctx)

            ;; Define file paths
            files [{:path (format "src/boundary/%s/schema.clj" module-name)
                    :content schema-content
                    :action :create}
                   {:path (format "src/boundary/%s/ports.clj" module-name)
                    :content ports-content
                    :action :create}
                   {:path (format "src/boundary/%s/core/%s.clj" module-name entity-kebab)
                    :content core-content
                    :action :create}
                   {:path (format "src/boundary/%s/core/ui.clj" module-name)
                    :content ui-content
                    :action :create}
                   {:path (format "src/boundary/%s/shell/service.clj" module-name)
                    :content service-content
                    :action :create}
                   {:path (format "src/boundary/%s/shell/persistence.clj" module-name)
                    :content persistence-content
                    :action :create}
                   {:path (format "src/boundary/%s/shell/http.clj" module-name)
                    :content http-content
                    :action :create}
                   {:path (format "src/boundary/%s/shell/web_handlers.clj" module-name)
                    :content web-handlers-content
                    :action :create}
                   {:path (format "migrations/005_create_%s.sql" (:entity-plural-snake entity))
                    :content migration-content
                    :action :create}
                   {:path (format "test/boundary/%s/core/%s_test.clj" module-name entity-kebab)
                    :content core-test-content
                    :action :create}
                   {:path (format "test/boundary/%s/shell/%s_repository_test.clj" module-name entity-kebab)
                    :content persistence-test-content
                    :action :create}
                   {:path (format "test/boundary/%s/shell/service_test.clj" module-name)
                    :content service-test-content
                    :action :create}]]

        ;; Write files (unless dry-run)
        (when-not dry-run?
          (doseq [{:keys [path content]} files]
            (fs-ports/write-file file-system path content)))

        ;; Return result
        {:success true
         :module-name module-name
         :files files
         :warnings (if dry-run?
                     ["Dry run - no files were written"]
                     [])})

      (catch Exception e
        {:success false
         :module-name (:module-name request)
         :files []
         :errors [(str "Generation failed: " (.getMessage e))]}))))

(defn create-scaffolder-service
  "Create a new scaffolder service.
   
   Args:
     file-system - IFileSystemAdapter implementation
   
   Returns:
     ScaffolderService instance"
  [file-system]
  (->ScaffolderService file-system))
