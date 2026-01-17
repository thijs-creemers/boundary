(ns ecommerce.product.ports
  "Port definitions for the product module.")

;; =============================================================================
;; Repository Port
;; =============================================================================

(defprotocol IProductRepository
  "Repository interface for product persistence."
  
  (find-by-id [this product-id]
    "Find a product by UUID. Returns product map or nil.")
  
  (find-by-slug [this slug]
    "Find a product by slug. Returns product map or nil.")
  
  (find-by-ids [this product-ids]
    "Find multiple products by IDs. Returns vector of products.")
  
  (list-products [this options]
    "List products with filtering.
     Options: :limit, :offset, :active
     Returns {:products [...] :total n}")
  
  (save! [this product]
    "Save a product (insert or update). Returns saved product.")
  
  (delete! [this product-id]
    "Delete a product by ID. Returns true if deleted."))

;; =============================================================================
;; Service Port
;; =============================================================================

(defprotocol IProductService
  "Service interface for product operations."
  
  (get-product [this product-id]
    "Get product by ID. Returns {:ok product} or {:error :not-found}")
  
  (get-product-by-slug [this slug]
    "Get product by slug. Returns {:ok product} or {:error :not-found}")
  
  (list-active-products [this options]
    "List active products. Returns {:ok {:products [...] :total n}}")
  
  (create-product [this input]
    "Create a new product. Returns {:ok product} or {:error ...}")
  
  (update-product [this product-id input]
    "Update a product. Returns {:ok product} or {:error ...}")
  
  (update-stock [this product-id quantity-delta]
    "Adjust product stock. Returns {:ok product} or {:error ...}")
  
  (deactivate-product [this product-id]
    "Deactivate a product. Returns {:ok product} or {:error :not-found}"))
