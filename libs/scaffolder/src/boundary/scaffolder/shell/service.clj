(ns boundary.scaffolder.shell.service
  "Scaffolder service implementation for module generation.
   
   Orchestrates template rendering and file generation."
(:require [boundary.scaffolder.ports :as ports]
            [boundary.scaffolder.schema :as schema]
            [boundary.scaffolder.core.template :as template]
            [boundary.scaffolder.core.generators :as generators]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]))

(defn- get-next-migration-number
  "Get the next migration number based on existing migrations."
  []
  (try
    (let [migrations-dir (io/file "resources/migrations")
          files (when (.exists migrations-dir) 
                  (map #(.getName %) (.listFiles migrations-dir)))
          numbers (keep #(when-let [m (re-find #"^(\d+)" %)] 
                          (Integer/parseInt (second m))) 
                       (or files []))
          max-num (if (seq numbers) (apply max numbers) 0)]
      (format "%03d" (inc max-num)))
    (catch Exception _
      "006")))

(defrecord ScaffolderService []
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
            (let [file (io/file path)]
              (.mkdirs (.getParentFile file))
              (spit file content))))

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

  (add-field [_this request]
    (try
      (let [{:keys [module-name entity field dry-run]} request
            migration-number (get-next-migration-number)
            
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
          (let [file (io/file (:path (first files)))]
            (.mkdirs (.getParentFile file))
            (spit file (:content (first files)))))
        
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
  
  (add-adapter [_this request]
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
          (let [file (io/file adapter-path)]
            (.mkdirs (.getParentFile file))
            (spit file adapter-content)))
        
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
         :errors [(str "Add adapter failed: " (.getMessage e))]})))

  (generate-project [_this request]
    (try
      (let [{:keys [name output-dir force dry-run]} request
            project-root (if (= output-dir ".") name (str output-dir "/" name))
            
            ;; Generate file contents
            deps-content (generators/generate-project-deps name)
            readme-content (generators/generate-project-readme name)
            config-content (generators/generate-project-config name)
            main-content (generators/generate-project-main name)
            
            files [{:path (str project-root "/deps.edn")
                    :content deps-content
                    :action :create}
                   {:path (str project-root "/README.md")
                    :content readme-content
                    :action :create}
                   {:path (str project-root "/resources/conf/dev/config.edn")
                    :content config-content
                    :action :create}
                   {:path (format "%s/src/%s/app.clj" 
                                  project-root (str/replace name "-" "/"))
                    :content main-content
                    :action :create}]]
        
        ;; Check for existing directory if not forcing
        (when (and (not force) 
                   (not dry-run)
                   (.exists (io/file project-root)))
          (throw (ex-info (str "Directory already exists: " project-root)
                          {:type :conflict :path project-root})))
        
        ;; Write files (unless dry-run)
        (when-not dry-run
          (doseq [{:keys [path content]} files]
            (let [file (io/file path)]
              (.mkdirs (.getParentFile file))
              (spit file content))))
        
        {:success true
         :name name
         :files files
         :warnings (if dry-run
                     ["Dry run - no files were written"]
                     [])})
      (catch Exception e
        {:success false
         :name (:name request)
         :files []
         :errors [(str "Project generation failed: " (.getMessage e))]}))))

(defn create-scaffolder-service
  "Create a new scaffolder service.
   
   Returns:
     ScaffolderService instance"
  []
  (->ScaffolderService))
