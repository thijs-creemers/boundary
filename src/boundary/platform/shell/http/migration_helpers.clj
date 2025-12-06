(ns boundary.platform.shell.http.migration-helpers
  "DEPRECATED: Migration helpers to convert Reitit routes to normalized format.
   
   **Status**: No longer needed - all modules have been migrated to normalized format.
   
   This namespace was created as a temporary bridge during the migration from
   Reitit-specific route definitions to framework-agnostic normalized route
   specifications. As of the current version, all modules (user, inventory)
   use normalized format directly.
   
   **Kept for**:
   - Historical reference
   - Documentation of the migration approach
   - Potential future module migrations
   
   **Migration History**:
   - User module: Migrated to normalized format (user-routes-normalized)
   - Inventory module: Migrated to normalized format (inventory-routes-normalized)
   - System wiring: Updated to use normalized routes directly
   
   Usage (if needed for new modules):
     ;; Convert existing Reitit routes
     (def reitit-routes
       [[\"/users\" {:get {:handler ...} :post {:handler ...}}]
        [\"/users/:id\" {:get {:handler ...}}]])
     
     (def normalized-routes
       (reitit-routes->normalized reitit-routes))"
  (:require [clojure.string :as str]))

;; =============================================================================
;; Reitit -> Normalized Conversion
;; =============================================================================

(defn- reitit-route-data->methods
  "Extract HTTP methods and their configs from Reitit route data.
   
   Args:
     route-data - Reitit route data map
     
   Returns:
     Map of HTTP method keywords to handler configs, or nil if no methods"
  [route-data]
  (let [http-methods #{:get :post :put :delete :patch :head :options}
        methods (select-keys route-data http-methods)]
    (when (seq methods)
      methods)))

(defn- reitit-route-data->meta
  "Extract non-method metadata from Reitit route data.
   
   Args:
     route-data - Reitit route data map
     
   Returns:
     Map of metadata (everything except HTTP methods)"
  [route-data]
  (let [http-methods #{:get :post :put :delete :patch :head :options}]
    (apply dissoc route-data http-methods)))

(defn- reitit-route->normalized
  "Convert single Reitit route to normalized format.
   
   Reitit format:
     [\"/users\" 
      {:middleware [...] :get {:handler ...} :post {:handler ...}}
      [\"/: id\" {:get {:handler ...}}]]
   
   Normalized format:
     {:path \"/users\"
      :methods {:get {:handler ...} :post {:handler ...}}
      :meta {:middleware [...]}
      :children [{:path \"/:id\" :methods {:get {:handler ...}}}]}
   
   Args:
     reitit-route - Reitit route vector [path data & children]
     
   Returns:
     Normalized route map"
  [[path route-data & children]]
  (let [methods (reitit-route-data->methods route-data)
        meta (reitit-route-data->meta route-data)
        normalized-children (when (seq children)
                              (mapv reitit-route->normalized children))]
    (cond-> {:path path}
      (seq methods)
      (assoc :methods methods)
      
      (seq meta)
      (assoc :meta meta)
      
      (seq normalized-children)
      (assoc :children normalized-children))))

(defn reitit-routes->normalized
  "Convert vector of Reitit routes to normalized format.
   
   Args:
     reitit-routes - Vector of Reitit route vectors
     
   Returns:
     Vector of normalized route maps
     
   Example:
     (reitit-routes->normalized
       [[\"/users\" {:get {:handler ...}}]
        [\"/items\" {:post {:handler ...}}]])
     => [{:path \"/users\" :methods {:get {:handler ...}}}
         {:path \"/items\" :methods {:post {:handler ...}}}]"
  [reitit-routes]
  (mapv reitit-route->normalized reitit-routes))

;; =============================================================================
;; Validation
;; =============================================================================

(defn validate-normalized-routes
  "Validate that normalized routes have required structure.
   
   Checks:
   - Each route has :path
   - Paths are strings starting with /
   - Methods are maps with valid HTTP verbs
   - Handlers are present for each method
   
   Args:
     routes - Vector of normalized route maps
     
   Returns:
     {:valid? boolean
      :errors vector of error messages}
     
   Example:
     (validate-normalized-routes
       [{:path \"/users\" :methods {:get {:handler (fn [_] ...)}}}])
     => {:valid? true :errors []}"
  [routes]
  (let [errors (atom [])]
    (doseq [[idx route] (map-indexed vector routes)]
      ;; Check path
      (when-not (:path route)
        (swap! errors conj (str "Route at index " idx " missing :path")))
      
      (when-not (and (string? (:path route))
                     (str/starts-with? (:path route) "/"))
        (swap! errors conj (str "Route at index " idx " path must start with /")))
      
      ;; Check methods if present
      (when (:methods route)
        (when-not (map? (:methods route))
          (swap! errors conj (str "Route at index " idx " :methods must be a map")))
        
        (doseq [[method config] (:methods route)]
          (when-not (#{:get :post :put :delete :patch :head :options} method)
            (swap! errors conj (str "Route at index " idx " invalid HTTP method: " method)))
          
          (when-not (:handler config)
            (swap! errors conj (str "Route at index " idx " method " method " missing :handler")))))
      
      ;; Recursively validate children
      (when (:children route)
        (let [child-result (validate-normalized-routes (:children route))]
          (when-not (:valid? child-result)
            (doseq [err (:errors child-result)]
              (swap! errors conj (str "Route at index " idx " child: " err)))))))
    
    {:valid? (empty? @errors)
     :errors @errors}))

(comment
  ;; Example: Convert Reitit routes
  (def example-reitit-routes
    [["/users" 
      {:middleware [:auth]
       :get {:handler (fn [_] {:status 200 :body "list"})
             :summary "List users"}
       :post {:handler (fn [_] {:status 201 :body "created"})
              :summary "Create user"}}
      ["/:id"
       {:get {:handler (fn [_] {:status 200 :body "user"})
              :summary "Get user"}}]]])
  
  (reitit-routes->normalized example-reitit-routes)
  ;; => [{:path "/users"
  ;;      :methods {:get {:handler ... :summary "List users"}
  ;;                :post {:handler ... :summary "Create user"}}
  ;;      :meta {:middleware [:auth]}
  ;;      :children [{:path "/:id"
  ;;                  :methods {:get {:handler ... :summary "Get user"}}}]}]
  
  ;; Validate converted routes
  (validate-normalized-routes
    (reitit-routes->normalized example-reitit-routes)))
  ;; => {:valid? true :errors []}
