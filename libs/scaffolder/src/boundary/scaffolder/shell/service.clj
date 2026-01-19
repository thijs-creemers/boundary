(ns boundary.scaffolder.shell.service
  "Scaffolder service implementation for module generation.
   
   Orchestrates template rendering and file generation."
(:require [boundary.scaffolder.ports :as ports]
            [boundary.scaffolder.schema :as schema]
            [boundary.scaffolder.core.template :as template]
            [boundary.scaffolder.core.generators :as generators]
            [boundary.platform.shell.adapters.filesystem.protocols :as fs-ports]
            [clojure.string :as str]
            [malli.core :as m]))

(defn- get-next-migration-number
  "Get the next migration number based on existing migrations."
  [file-system]
  (try
    (let [files (fs-ports/list-files file-system "migrations")
          numbers (keep #(when-let [m (re-find #"^(\d+)" %)] 
                          (Integer/parseInt (second m))) 
                       (or files []))
          max-num (if (seq numbers) (apply max numbers) 0)]
      (format "%03d" (inc max-num)))
    (catch Exception _
      "006")))

(defrecord ScaffolderService [file-system]
  ports/IScaffolderService

  (generate-module [_ request]
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
         :errors [(str "Generation failed: " (.getMessage e))]})))

  (add-field [this request]
    (try
      (let [{:keys [module-name entity field dry-run]} request
            migration-number (get-next-migration-number (:file-system this))
            
            ;; Generate migration content
            migration-content (generators/generate-add-field-migration 
                               module-name entity field migration-number)
            
            ;; Generate schema instructions
            schema-instructions (generators/generate-add-field-schema-comment
                                 module-name entity field)
            
            ;; Define files
            field-name-snake (template/kebab->snake (name (:name field)))
            table-name (template/kebab->snake (template/pluralize (str/lower-case entity)))
            files [{:path (format "migrations/%s_add_%s_to_%s.sql" 
                                  migration-number field-name-snake table-name)
                    :content migration-content
                    :action :create}
                   {:path (format "src/boundary/%s/schema.clj" module-name)
                    :content schema-instructions
                    :action :update}]]
        
        ;; Write migration file (unless dry-run)
        (when-not dry-run
          (fs-ports/write-file (:file-system this) 
                               (:path (first files)) 
                               (:content (first files))))
        
        {:success true
         :module-name module-name
         :files files
         :warnings (if dry-run
                     ["Dry run - no files were written"
                      "Manual schema update required - see instructions in output"]
                     ["Manual schema update required - see instructions above"])})
      
      (catch Exception e
        {:success false
         :module-name (:module-name request)
         :files []
         :errors [(str "Add field failed: " (.getMessage e))]})))
  
  (add-endpoint [_this request]
    (try
      (let [{:keys [module-name path method handler-name dry-run]} request
            
            ;; Generate endpoint definition instructions
            endpoint-content (generators/generate-endpoint-definition
                              module-name path method handler-name)
            
            files [{:path (format "src/boundary/%s/shell/http.clj" module-name)
                    :content endpoint-content
                    :action :update}]]
        
        {:success true
         :module-name module-name
         :files files
         :warnings ["Manual code update required - see instructions in output"
                    (when dry-run "Dry run - showing what to add")]})
      
      (catch Exception e
        {:success false
         :module-name (:module-name request)
         :files []
         :errors [(str "Add endpoint failed: " (.getMessage e))]})))
  
  (add-adapter [this request]
    (try
      (let [{:keys [module-name port adapter-name methods dry-run]} request
            
            ;; Generate adapter file content
            adapter-content (generators/generate-adapter-file
                             module-name port adapter-name 
                             (or methods [{:name "example-method" :args ["arg1"]}]))
            
            adapter-path (format "src/boundary/%s/shell/adapters/%s.clj" 
                                 module-name adapter-name)
            files [{:path adapter-path
                    :content adapter-content
                    :action :create}]]
        
        ;; Write adapter file (unless dry-run)
        (when-not dry-run
          (fs-ports/write-file (:file-system this) adapter-path adapter-content))
        
        {:success true
         :module-name module-name
         :files files
         :warnings (if dry-run
                     ["Dry run - no files were written"]
                     ["Implement TODO methods in the generated adapter"])})
      
      (catch Exception e
        {:success false
         :module-name (:module-name request)
         :files []
         :errors [(str "Add adapter failed: " (.getMessage e))]}))))

(defn create-scaffolder-service
  "Create a new scaffolder service.
   
   Args:
     file-system - IFileSystemAdapter implementation
   
   Returns:
     ScaffolderService instance"
  [file-system]
  (->ScaffolderService file-system))
