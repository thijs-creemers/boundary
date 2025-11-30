(ns boundary.inventory.core.item
  "Pure business logic for item domain.
   
   All functions in this namespace are pure - they have no side effects,
   don't perform I/O, and always return the same output for the same input."
  (:require [boundary.inventory.schema :as schema]))

;; =============================================================================
;; Entity Creation
;; =============================================================================

(defn prepare-new-item
  "Prepare data for creating a new item.
   
   Args:
     data - Input data map
     current-time - java.time.Instant for timestamps
   
   Returns:
     Prepared item entity map
   
   Pure: true"
  [data current-time]
  (merge data
         {:id (java.util.UUID/randomUUID)
          :created-at current-time
          :updated-at current-time}))

;; =============================================================================
;; Entity Updates
;; =============================================================================

(defn apply-item-update
  "Apply updates to existing item entity.
   
   Args:
     existing - Current item entity
     updates - Map of fields to update
     current-time - java.time.Instant for updated-at
   
   Returns:
     Updated item entity map
   
   Pure: true"
  [existing updates current-time]
  (merge existing
         updates
         {:updated-at current-time}))

;; =============================================================================
;; Validation
;; =============================================================================

(defn validate-item
  "Validate item entity data.
   
   Args:
     data - item data to validate
   
   Returns:
     Vector of [valid? errors data]
   
   Pure: true"
  [data]
  (if (schema/validate-item data)
    [true nil data]
    [false (schema/explain-item data) nil]))
