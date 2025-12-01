(ns boundary.scaffolder.ports
  "Scaffolder module port definitions (abstract interfaces).")

;; =============================================================================
;; Service Layer Ports
;; =============================================================================

(defprotocol IScaffolderService
  "Scaffolder service interface for module generation operations.
   
   This port defines the service layer interface for generating new modules
   from templates. The service orchestrates template rendering, file generation,
   and validation."

  (generate-module [this request]
    "Generate a complete module from template.
     
     Args:
       request: Map conforming to schema/ModuleGenerationRequest
                {:module-name \"customer\"
                 :entities [{:name \"Customer\" :fields [...]}]
                 :interfaces {:http true :cli true :web true}
                 :dry-run false}
     
     Returns:
       Map conforming to schema/ModuleGenerationResult
       {:success true
        :module-name \"customer\"
        :files [{:path \"...\" :content \"...\" :action :create}]
        :errors []
        :warnings []}
       
     Example:
       (generate-module service {:module-name \"customer\" ...})"))
