(ns boundary.inventory.ports
  "Inventory module port definitions (abstract interfaces).")

;; =============================================================================
;; Repository Ports
;; =============================================================================

(defprotocol IItemRepository
  "Repository interface for item persistence operations."

  (find-by-id [this id]
    "Find item by ID.")

  (find-all [this options]
    "Find all items with pagination and filtering.")

  (create [this entity]
    "Create new item.")

  (update-item [this entity]
    "Update existing item.")

  (delete [this id]
    "Delete item by ID."))

;; =============================================================================
;; Service Ports
;; =============================================================================

(defprotocol IItemService
  "Item service interface for business operations."

  (get-item [this id]
    "Get item by ID.")

  (list-items [this options]
    "List items with pagination.")

  (create-item [this data]
    "Create new item.")

  (update-item-data [this id data]
    "Update item.")

  (delete-item [this id]
    "Delete item."))
