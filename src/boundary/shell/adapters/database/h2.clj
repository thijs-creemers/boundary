(ns boundary.shell.adapters.database.h2
  "H2 database adapter - main entry point.

   This namespace serves as the main entry point for H2 database
   functionality. It delegates to the modular h2.core namespace
   which coordinates specialized modules for different aspects of H2 operations.

   The adapter has been refactored into 5 specialized namespaces:
   - h2.connection: Connection management and H2-specific settings
   - h2.query: H2-specific query building and boolean handling
   - h2.metadata: Table introspection and schema information
   - h2.utils: Utility functions and DDL helpers
   - h2.core: Main adapter implementation and coordination

   This namespace maintains backward compatibility by re-exporting
   the core functionality."
  (:require [boundary.shell.adapters.database.h2.core :as h2-core]))

;; =============================================================================
;; Main Adapter Functions (Re-exported from core)
;; =============================================================================

(def new-adapter h2-core/new-adapter)

;; =============================================================================
;; H2-Specific Utilities (Re-exported from core)
;; =============================================================================

;; Connection and URL building
(def in-memory-url h2-core/in-memory-url)
(def file-url h2-core/file-url)

;; DDL helpers
(def boolean-column-type h2-core/boolean-column-type)
(def uuid-column-type h2-core/uuid-column-type)
(def timestamp-column-type h2-core/timestamp-column-type)
(def auto-increment-primary-key h2-core/auto-increment-primary-key)

;; Performance and maintenance
(def explain-query h2-core/explain-query)
(def analyze-table h2-core/analyze-table)

;; Database information
(def show-tables h2-core/show-tables)
(def show-indexes h2-core/show-indexes)

;; Development and testing utilities
(def create-test-context h2-core/create-test-context)

;; =============================================================================
;; Transaction Management (Re-exported from core)
;; =============================================================================

(defmacro with-transaction
  "Macro for H2 transaction management with consistent error handling.

   Args:
     binding: [tx-var datasource]
     body: Expressions to execute within transaction

   Example:
     (with-transaction [tx datasource]
       (execute-update! tx query1)
       (execute-update! tx query2))"
  [binding & body]
  `(h2-core/with-transaction ~binding ~@body))
