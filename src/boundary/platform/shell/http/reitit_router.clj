(ns boundary.platform.shell.http.reitit-router
  "Reitit router adapter - converts normalized route specs to Reitit routing.
   
   This adapter implements the IRouter protocol to translate framework-agnostic
   normalized route specifications into Reitit-specific route definitions."
  (:require [boundary.platform.ports.http :as ports]
            [boundary.platform.shell.http.interceptors :as http-interceptors]
            [reitit.coercion.malli :as malli-coercion]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [muuntaja.core :as m]))

;; =============================================================================
;; Symbol Resolution
;; =============================================================================

(defn- resolve-handler-fn
  "Resolve qualified symbol to handler function.
  
  Args:
    handler-sym - Either a function (returned as-is) or qualified symbol
    
  Returns:
    Function
    
  Throws:
    Exception if symbol cannot be resolved"
  [handler-sym]
  (if (symbol? handler-sym)
    (or (requiring-resolve handler-sym)
        (throw (ex-info "Could not resolve handler function"
                        {:handler handler-sym})))
    handler-sym))

(defn- resolve-middleware-fns
  "Resolve vector of qualified symbols to middleware functions.
   
   Args:
     middleware-syms - Vector of qualified symbols or functions
     
   Returns:
     Vector of middleware functions"
  [middleware-syms]
  (mapv resolve-handler-fn (or middleware-syms [])))

(defn- resolve-interceptors
  "Resolve vector of interceptor specs to actual interceptor instances.
   
   Interceptor specs can be:
   - Qualified symbols referencing interceptor definitions
   - Functions that return interceptors
   - Interceptor maps directly
   
   Args:
     interceptor-specs - Vector of interceptor specs
     
   Returns:
     Vector of interceptor maps"
  [interceptor-specs]
  (when (seq interceptor-specs)
    (mapv (fn [spec]
            (cond
              ;; Symbol - resolve and call if function
              (symbol? spec)
              (let [resolved (resolve-handler-fn spec)]
                (if (fn? resolved)
                  (resolved)  ; Call function to get interceptor
                  resolved))  ; Already an interceptor map
              
              ;; Function - call to get interceptor
              (fn? spec)
              (spec)
              
              ;; Map - assume it's an interceptor
              (map? spec)
              spec
              
              :else
              (throw (ex-info "Invalid interceptor spec"
                              {:spec spec
                               :type (type spec)}))))
          interceptor-specs)))

(defn- interceptors->middleware
  "Convert vector of HTTP interceptors to a Ring middleware function.
   
   This adapter allows interceptors to be used in Reitit's :middleware chain.
   The middleware function runs the interceptor pipeline and extracts the response.
   
   Args:
     interceptors - Vector of interceptor maps
     system - Observability services map {:logger :metrics-emitter :error-reporter}
     
   Returns:
     Ring middleware function"
  [interceptors system]
  (fn [handler]
    (fn [request]
      ;; Use run-http-interceptors which handles the full pipeline
      (http-interceptors/run-http-interceptors handler interceptors request system))))

;; =============================================================================
;; Route Conversion
;; =============================================================================

(defn- convert-coercion
  "Convert normalized coercion spec to Reitit parameters format.
  
  Normalized format:
    {:query SomeSchema
     :body SomeSchema
     :path SomeSchema
     :response {200 SuccessSchema 400 ErrorSchema}}
     
  Reitit format:
    {:parameters {:query [...]
                  :body [...]
                  :path [...]}
     :responses {200 {:body [...]}
                 400 {:body [...]}}}
  
  Args:
    coercion-spec - Normalized coercion map
    
  Returns:
    Map with :parameters and :responses for Reitit"
  [coercion-spec]
  (when coercion-spec
    (let [params (select-keys coercion-spec [:query :body :path])
          responses (:response coercion-spec)]
      (cond-> {}
        (seq params)
        (assoc :parameters params)
        
        (seq responses)
        (assoc :responses (into {}
                                (map (fn [[status schema]]
                                       [status {:body schema}]))
                                responses))))))

(defn- convert-handler-config
  "Convert normalized handler config to Reitit handler data.
   
   Normalized format:
     {:handler 'ns/fn
      :middleware ['ns/mw1 'ns/mw2]
      :interceptors ['ns/int1 'ns/int2]
      :coercion {:query ... :response ...}
      :summary \"Description\"
      :tags [\"tag1\"]
      :produces [\"application/json\"]
      :consumes [\"application/json\"]}
      
   Reitit format:
     {:handler (fn ...)
      :middleware [(fn ...) (fn ...)]
      :parameters {:query ...}
      :responses {200 {:body ...}}
      :summary \"Description\"
      :tags [\"tag1\"]}
   
   Notes:
   - HTTP interceptors run for every matched endpoint.
   - The framework default stack is always applied.
   - Route-specific interceptors (via :interceptors) are appended after defaults.
   - :system is optional; if omitted, it defaults to {}.
   
   Args:
     handler-config - Normalized handler config map
     system - Optional observability services map {:logger :metrics-emitter :error-reporter}
     
   Returns:
     Reitit handler data map"
  ([handler-config]
   (convert-handler-config handler-config nil))
  
  ([{:keys [handler middleware interceptors coercion summary tags produces consumes]} system]
   (let [resolved-handler (resolve-handler-fn handler)
         resolved-middleware (resolve-middleware-fns middleware)
         
         ;; Treat system services as optional; interceptors can run with {}.
         system (or system {})

         ;; Always apply default HTTP interceptors; append any route-specific interceptors.
         resolved-route-interceptors (or (resolve-interceptors interceptors) [])
         all-interceptors (vec (concat http-interceptors/default-http-interceptors
                                       resolved-route-interceptors))
         interceptor-middleware [(interceptors->middleware all-interceptors system)]

         ;; Combine regular middleware with interceptor-generated middleware.
         ;; We append interceptors last so they are closest to the resolved handler,
         ;; while still seeing a fully prepared request (session/body/coercions).
         all-middleware (concat resolved-middleware interceptor-middleware)
         coercion-data (convert-coercion coercion)]
     (cond-> {:handler resolved-handler
              :middleware (vec all-middleware)}
       coercion-data
       (merge coercion-data)
       
       summary
       (assoc :summary summary)
       
       (seq tags)
       (assoc :tags tags)
       
       (seq produces)
       (assoc :produces produces)
       
       (seq consumes)
       (assoc :consumes consumes)))))

(defn- convert-methods
  "Convert normalized methods map to Reitit format.
   
   Normalized format:
     {:get {:handler 'ns/fn ...}
      :post {:handler 'ns/fn ...}}
      
   Reitit format:
     {:get {:handler (fn ...) ...}
      :post {:handler (fn ...) ...}}
   
   Args:
     methods-map - Map of HTTP method keyword to handler config
     system - Optional observability services map {:logger :metrics-emitter :error-reporter} (defaults to {})
     
   Returns:
     Map of HTTP method keyword to Reitit handler data"
  ([methods-map]
   (convert-methods methods-map nil))
  
  ([methods-map system]
   (into {}
         (map (fn [[method handler-cfg]]
                [method (convert-handler-config handler-cfg system)]))
         methods-map)))

(defn- convert-route
  "Convert single normalized route entry to Reitit format.
   
   Handles nested routes via :children.
   
   IMPORTANT: When a route has both methods AND children, Reitit requires
   the parent methods to be defined as an empty string child route.
   
   Normalized format:
     {:path \"/users\"
      :methods {:get {...} :post {...}}
      :children [{:path \"/:id\" :methods {...}}]
      :meta {:middleware [...] :auth true}}
      
   Reitit format (when route has children):
     [\"/users\"
      {:middleware [...] :auth true}
      [\"\" {:get {...} :post {...}}]     ; Parent methods as empty string child
      [\"/:id\" {:get {...}}]]             ; Actual children
   
   Reitit format (when route has NO children):
     [\"/users\"
      {:middleware [...] :auth true
       :get {...}
       :post {...}}]
   
   Args:
     route-entry - Normalized route map
     system - Optional observability services map {:logger :metrics-emitter :error-reporter} (defaults to {})
     
   Returns:
     Reitit route vector [path data & children]"
  ([route-entry]
   (convert-route route-entry nil))
  
  ([{:keys [path methods children meta]} system]
   (let [;; Recursively convert children
         reitit-children (when (seq children)
                           (mapv #(convert-route % system) children))]
     
     (if (seq reitit-children)
       ;; Route HAS children: parent methods must be empty string child
       (let [reitit-methods (convert-methods methods system)
             ;; Create empty string child route for parent methods (if any)
             parent-child (when (seq reitit-methods)
                            ["" reitit-methods])]
         ;; Build: [path meta empty-string-child ...children]
         (into [path meta] 
               (if parent-child
                 (into [parent-child] reitit-children)
                 reitit-children)))
       
       ;; Route has NO children: methods can be on route data directly
       (let [reitit-methods (convert-methods methods system)
             route-data (merge meta reitit-methods)]
         [path route-data])))))

(defn- convert-all-routes
  "Convert vector of normalized route specs to Reitit format.
   
   Args:
     route-specs - Vector of normalized route maps
     system - Optional observability services map {:logger :metrics-emitter :error-reporter} (defaults to {})
     
   Returns:
     Vector of Reitit route vectors"
  ([route-specs]
   (convert-all-routes route-specs nil))
  
  ([route-specs system]
   (mapv #(convert-route % system) route-specs)))

;; =============================================================================
;; Router Creation
;; =============================================================================

(defn- create-default-middleware
  "Create default middleware stack for Reitit router.
  
  Returns:
    Vector of middleware for Reitit router"
  []
  [;; Query params & form params
   parameters/parameters-middleware
   ;; Content negotiation
   muuntaja/format-negotiate-middleware
   ;; Encoding response body
   muuntaja/format-response-middleware
   ;; Decoding request body
   muuntaja/format-request-middleware
   ;; Coercing request parameters
   coercion/coerce-request-middleware
   ;; Coercing response bodies
   coercion/coerce-response-middleware
   ;; Exception handling
   exception/exception-middleware])

(defn- create-router-options
  "Create Reitit router options from config.
   
   Args:
     config - Router configuration map with keys:
              :middleware - Additional middleware vector (symbols resolved)
              :coercion - Coercion configuration (defaults to Malli)
              :muuntaja - Muuntaja configuration (defaults to json/edn/transit)
              :conflicts - Conflict resolution (defaults to nil = disabled)
              
   Returns:
     Map of Reitit router options"
  [config]
  (let [default-middleware (create-default-middleware)
        custom-middleware (resolve-middleware-fns (:middleware config))
        all-middleware (into default-middleware custom-middleware)]
    {:data {:coercion (or (:coercion config) malli-coercion/coercion)
            :muuntaja (or (:muuntaja config) m/instance)
            :middleware all-middleware}
     ;; Disable conflict checking by default - allows routes like /users/new and /users/:id
     ;; to coexist. Reitit will match specific paths before parameterized ones.
     :conflicts (get config :conflicts nil)}))

(defn- create-default-handler
  "Create default handler for routes not matched by router.
  
  Returns:
    Ring handler function"
  []
  (ring/create-default-handler
    {:not-found (constantly {:status 404
                             :headers {"Content-Type" "application/json"}
                             :body {:error "Not Found"
                                    :message "The requested resource was not found"}})
     :method-not-allowed (constantly {:status 405
                                      :headers {"Content-Type" "application/json"}
                                      :body {:error "Method Not Allowed"
                                             :message "The HTTP method is not allowed for this resource"}})
     :not-acceptable (constantly {:status 406
                                  :headers {"Content-Type" "application/json"}
                                  :body {:error "Not Acceptable"
                                         :message "The requested content type is not supported"}})}))

;; =============================================================================
;; IRouter Implementation
;; =============================================================================

(defrecord ReititRouter []
  ports/IRouter
  (compile-routes [_this route-specs config]
    (let [;; Extract observability services from config (if provided)
          system (:system config)
          
          ;; Convert normalized routes to Reitit format
          reitit-routes (convert-all-routes route-specs system)
          
          ;; Create router options
          router-opts (create-router-options config)
          
          ;; Create Reitit router
          router (ring/router reitit-routes router-opts)
          
          ;; Create default handler for unmatched routes
          default-handler (create-default-handler)]
      
      ;; Return Ring handler
      (ring/ring-handler router default-handler))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn create-reitit-router
  "Create new Reitit router adapter instance.
  
  Returns:
    ReititRouter instance implementing IRouter"
  []
  (->ReititRouter))

(comment
  ;; Example usage:
  
  ;; Define normalized routes
  (def example-routes
    [{:path "/api/users"
      :methods {:get {:handler 'my.app.handlers/list-users
                      :summary "List users"
                      :tags ["users"]
                      :coercion {:query [:map
                                         [:limit {:optional true} :int]
                                         [:offset {:optional true} :int]]
                                 :response {200 [:vector [:map [:id :uuid] [:name :string]]]
                                            400 [:map [:error :string]]}}}
                :post {:handler 'my.app.handlers/create-user
                       :summary "Create user"
                       :tags ["users"]
                       :coercion {:body [:map
                                         [:name :string]
                                         [:email :string]]
                                  :response {201 [:map [:id :uuid]]
                                             400 [:map [:error :string]]}}}}
      :children [{:path "/:id"
                  :methods {:get {:handler 'my.app.handlers/get-user
                                  :coercion {:path [:map [:id :uuid]]
                                             :response {200 [:map [:id :uuid] [:name :string]]
                                                        404 [:map [:error :string]]}}}
                            :delete {:handler 'my.app.handlers/delete-user
                                     :coercion {:path [:map [:id :uuid]]
                                                :response {204 nil
                                                           404 [:map [:error :string]]}}}}}]}])
  
  ;; Create router and compile routes
  (let [router (create-reitit-router)
        config {:middleware ['my.app.middleware/wrap-auth]}
        handler (ports/compile-routes router example-routes config)]
    
    ;; Use handler as Ring handler
    (handler {:request-method :get
              :uri "/api/users"
              :query-params {"limit" "10" "offset" "0"}})))
