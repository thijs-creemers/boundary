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

;; =============================================================================
;; File System Ports
;; =============================================================================

(defprotocol IFileSystemAdapter
  "File system operations for module generation.
   
   This port abstracts file system operations to enable testing
   and different implementations (real FS, in-memory, etc.)."

  (file-exists? [this path]
    "Check if file exists at path.
     
     Args:
       path: String file path
     
     Returns:
       Boolean indicating if file exists
     
     Example:
       (file-exists? adapter \"src/boundary/customer/schema.clj\")")

  (read-file [this path]
    "Read file content from path.
     
     Args:
       path: String file path
     
     Returns:
       String file content or nil if not found
     
     Example:
       (read-file adapter \"src/boundary/customer/schema.clj\")")

  (write-file [this path content]
    "Write content to file at path.
     
     Args:
       path: String file path
       content: String content to write
     
     Returns:
       Boolean indicating success
       Creates parent directories if needed
     
     Example:
       (write-file adapter \"src/boundary/customer/schema.clj\" content)")

  (delete-file [this path]
    "Delete file at path.
     
     Args:
       path: String file path
     
     Returns:
       Boolean indicating success
     
     Example:
       (delete-file adapter \"src/boundary/customer/schema.clj\")")

  (list-files [this directory]
    "List files in directory.
     
     Args:
       directory: String directory path
     
     Returns:
       Vector of file path strings
     
     Example:
       (list-files adapter \"src/boundary/customer\")"))
