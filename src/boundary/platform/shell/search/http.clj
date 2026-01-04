(ns boundary.platform.shell.search.http
  "HTTP routes for full-text search functionality.
   
   Provides REST API endpoints for searching users, items, autocomplete
   suggestions, reindexing, and search statistics.
   
   API Endpoints (mounted under /api):
   - GET    /api/search/users           - Search users
   - GET    /api/search/items           - Search items
   - GET    /api/search/suggest         - Autocomplete suggestions
   - POST   /api/search/reindex/:index  - Reindex all documents
   - GET    /api/search/stats           - Search statistics
   
   Architecture: Shell Layer (HTTP Routes)"
  (:require [boundary.platform.search.ports :as ports]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ============================================================================
;; Request Parameter Parsing
;; ============================================================================

(defn- parse-int
  "Parse string to integer with default value.
   
   Args:
     s - String to parse
     default - Default value if parsing fails
   
   Returns:
     Parsed integer or default"
  [s default]
  (try
    (if (string? s)
      (Integer/parseInt s)
      default)
    (catch Exception _
      default)))

(defn- parse-bool
  "Parse string to boolean with default value.
   
   Args:
     s - String to parse (\"true\", \"false\", \"1\", \"0\")
     default - Default value if parsing fails
   
   Returns:
     Parsed boolean or default"
  [s default]
  (cond
    (boolean? s) s
    (string? s) (case (str/lower-case s)
                  ("true" "1" "yes") true
                  ("false" "0" "no") false
                  default)
    :else default))

(defn- parse-fields
  "Parse comma-separated field list.
   
   Args:
     s - Comma-separated string (e.g., \"name,email,bio\")
   
   Returns:
     Vector of keywords or nil"
  [s]
  (when (and s (not (str/blank? s)))
    (mapv keyword (str/split s #","))))

(defn- extract-search-options
  "Extract search options from request query parameters.
   
   Args:
     params - Query parameters map
   
   Returns:
     Options map for search service"
  [params]
  {:from (parse-int (get params "from") 0)
   :size (parse-int (get params "size") 20)
   :highlight? (parse-bool (get params "highlight") true)
   :highlight-fields (or (parse-fields (get params "highlight_fields"))
                         (parse-fields (get params "highlightFields")))
   :boost-recent? (parse-bool (get params "boost_recent") true)
   :filters (when-let [filters-str (get params "filters")]
             (try
               (json/parse-string filters-str true)
               (catch Exception e
                 (log/warn "Failed to parse filters JSON" {:error (ex-message e)})
                 [])))})

;; ============================================================================
;; Response Helpers
;; ============================================================================

(defn- json-response
  "Create JSON response with appropriate headers.
   
   Args:
     status - HTTP status code
     body - Response body (will be JSON encoded)
   
   Returns:
     Ring response map"
  [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

(defn- success-response
  "Create successful JSON response (200 OK).
   
   Args:
     body - Response body
   
   Returns:
     Ring response map"
  [body]
  (json-response 200 body))

(defn- created-response
  "Create successful creation response (201 Created).
   
   Args:
     body - Response body
   
   Returns:
     Ring response map"
  [body]
  (json-response 201 body))

(defn- error-response
  "Create error JSON response with RFC 7807 problem details.
   
   Args:
     status - HTTP status code
     title - Error title
     detail - Error detail message
     additional-fields - Additional fields to include
   
   Returns:
     Ring response map"
  [status title detail & [additional-fields]]
  (json-response status
                (merge {:type "about:blank"
                        :title title
                        :status status
                        :detail detail}
                      additional-fields)))

(defn- bad-request-response
  "Create bad request error response (400).
   
   Args:
     detail - Error detail message
     additional-fields - Additional fields
   
   Returns:
     Ring response map"
  [detail & [additional-fields]]
  (error-response 400 "Bad Request" detail additional-fields))

(defn- internal-error-response
  "Create internal server error response (500).
   
   Args:
     detail - Error detail message
   
   Returns:
     Ring response map"
  [detail]
  (error-response 500 "Internal Server Error" detail))

;; ============================================================================
;; Handler Functions
;; ============================================================================

(defn search-users-handler
  "Search users endpoint handler.
   
   Query Parameters:
     q (required) - Search query string
     from (optional) - Pagination offset (default: 0)
     size (optional) - Page size (default: 20, max: 100)
     highlight (optional) - Enable highlighting (default: true)
     highlight_fields (optional) - Fields to highlight (comma-separated)
     boost_recent (optional) - Boost recent documents (default: true)
   
   Response:
     200 OK with search results
     400 Bad Request if query parameter missing
     500 Internal Server Error on failure"
  [search-service]
  (fn [request]
    (let [params (:query-params request)
          query (get params "q")]
      
      (if (str/blank? query)
        (bad-request-response "Query parameter 'q' is required")
        
        (try
          (let [options (extract-search-options params)
                ;; Limit size to max 100
                options (update options :size #(min % 100))
                result (ports/search-users search-service query options)]
            
            (log/info "User search completed"
                     {:query query
                      :total (:total result)
                      :took-ms (:took-ms result)})
            
            (success-response result))
          
          (catch Exception e
            (log/error e "User search failed" {:query query})
            (internal-error-response (ex-message e))))))))

(defn search-items-handler
  "Search items endpoint handler.
   
   Query Parameters:
     q (required) - Search query string
     from (optional) - Pagination offset (default: 0)
     size (optional) - Page size (default: 20, max: 100)
     highlight (optional) - Enable highlighting (default: true)
     highlight_fields (optional) - Fields to highlight (comma-separated)
     boost_recent (optional) - Boost recent documents (default: true)
   
   Response:
     200 OK with search results
     400 Bad Request if query parameter missing
     500 Internal Server Error on failure"
  [search-service]
  (fn [request]
    (let [params (:query-params request)
          query (get params "q")]
      
      (if (str/blank? query)
        (bad-request-response "Query parameter 'q' is required")
        
        (try
          (let [options (extract-search-options params)
                ;; Limit size to max 100
                options (update options :size #(min % 100))
                result (ports/search-items search-service query options)]
            
            (log/info "Item search completed"
                     {:query query
                      :total (:total result)
                      :took-ms (:took-ms result)})
            
            (success-response result))
          
          (catch Exception e
            (log/error e "Item search failed" {:query query})
            (internal-error-response (ex-message e))))))))

(defn suggest-handler
  "Autocomplete suggestions endpoint handler.
   
   Query Parameters:
     prefix (required) - Prefix to complete
     field (required) - Field to search (e.g., \"name\", \"email\")
     index (optional) - Index to search (default: \"users\")
     limit (optional) - Maximum suggestions (default: 10, max: 50)
   
   Response:
     200 OK with suggestions
     400 Bad Request if required parameters missing
     500 Internal Server Error on failure"
  [search-service]
  (fn [request]
    (let [params (:query-params request)
          prefix (get params "prefix")
          field-str (get params "field")
          index-str (get params "index" "users")
          limit (parse-int (get params "limit") 10)]
      
      (cond
        (str/blank? prefix)
        (bad-request-response "Query parameter 'prefix' is required")
        
        (str/blank? field-str)
        (bad-request-response "Query parameter 'field' is required")
        
        :else
        (try
          (let [field (keyword field-str)
                index (keyword index-str)
                ;; Limit to max 50
                limit (min limit 50)
                options {:index index :limit limit}
                result (ports/suggest search-service prefix field options)]
            
            (log/info "Suggestions completed"
                     {:prefix prefix
                      :field field
                      :count (:count result)
                      :took-ms (:took-ms result)})
            
            (success-response result))
          
          (catch Exception e
            (log/error e "Suggestion failed" {:prefix prefix :field field-str})
            (internal-error-response (ex-message e))))))))

(defn reindex-handler
  "Reindex endpoint handler.
   
   Path Parameters:
     index (required) - Index name (\"users\" or \"items\")
   
   Response:
     201 Created with reindex results
     400 Bad Request if index invalid
     500 Internal Server Error on failure"
  [search-service]
  (fn [request]
    (let [index-str (get-in request [:path-params :index])]
      
      (if (str/blank? index-str)
        (bad-request-response "Path parameter 'index' is required")
        
        (try
          (let [index (keyword index-str)]
            ;; Validate index name
            (when-not (#{:users :items} index)
              (throw (ex-info "Invalid index name"
                            {:type :validation-error
                             :index index-str
                             :valid-indexes [:users :items]})))
            
            (log/info "Starting reindex" {:index index})
            
            (let [result (ports/reindex-all search-service index)]
              
              (log/info "Reindex completed"
                       {:index index
                        :reindexed-count (:reindexed-count result)
                        :failed-count (:failed-count result)
                        :duration-ms (:duration-ms result)})
              
              (created-response result)))
          
          (catch clojure.lang.ExceptionInfo e
            (let [data (ex-data e)]
              (if (= :validation-error (:type data))
                (bad-request-response (ex-message e) data)
                (do
                  (log/error e "Reindex failed" {:index index-str})
                  (internal-error-response (ex-message e))))))
          
          (catch Exception e
            (log/error e "Reindex failed" {:index index-str})
            (internal-error-response (ex-message e))))))))

(defn stats-handler
  "Search statistics endpoint handler.
   
   Response:
     200 OK with statistics
     500 Internal Server Error on failure"
  [search-service]
  (fn [_request]
    (try
      (let [result (ports/get-search-stats search-service)]
        
        (log/info "Search stats retrieved"
                 {:total-documents (:total-documents result)
                  :indices (count (:indices result))})
        
        (success-response result))
      
      (catch Exception e
        (log/error e "Failed to retrieve search stats")
        (internal-error-response (ex-message e))))))

;; ============================================================================
;; Route Definitions (Normalized Format)
;; ============================================================================

(defn normalized-api-routes
  "Define search API routes in normalized format.
   
   Routes are returned WITHOUT /api prefix - the top-level router
   will add the prefix during composition.
   
   Args:
     search-service - Search service instance
   
   Returns:
     Vector of normalized route maps"
  [search-service]
  [{:path "/search/users"
    :methods {:get {:handler (search-users-handler search-service)
                    :summary "Search users with full-text search"
                    :description "Search users by name, email, or bio with highlighting and ranking"
                    :parameters {:query {:q {:type :string :required true :description "Search query"}
                                        :from {:type :integer :default 0 :description "Pagination offset"}
                                        :size {:type :integer :default 20 :max 100 :description "Page size"}
                                        :highlight {:type :boolean :default true :description "Enable highlighting"}
                                        :highlight_fields {:type :string :description "Fields to highlight (comma-separated)"}
                                        :boost_recent {:type :boolean :default true :description "Boost recent documents"}}}}}}
   
   {:path "/search/items"
    :methods {:get {:handler (search-items-handler search-service)
                    :summary "Search items with full-text search"
                    :description "Search items by name, SKU, or location with highlighting and ranking"
                    :parameters {:query {:q {:type :string :required true :description "Search query"}
                                        :from {:type :integer :default 0 :description "Pagination offset"}
                                        :size {:type :integer :default 20 :max 100 :description "Page size"}
                                        :highlight {:type :boolean :default true :description "Enable highlighting"}
                                        :highlight_fields {:type :string :description "Fields to highlight (comma-separated)"}
                                        :boost_recent {:type :boolean :default true :description "Boost recent documents"}}}}}}
   
   {:path "/search/suggest"
    :methods {:get {:handler (suggest-handler search-service)
                    :summary "Get autocomplete suggestions"
                    :description "Get field value suggestions based on prefix matching"
                    :parameters {:query {:prefix {:type :string :required true :description "Prefix to complete"}
                                        :field {:type :string :required true :description "Field to search"}
                                        :index {:type :string :default "users" :description "Index to search"}
                                        :limit {:type :integer :default 10 :max 50 :description "Maximum suggestions"}}}}}}
   
   {:path "/search/reindex/:index"
    :methods {:post {:handler (reindex-handler search-service)
                     :summary "Reindex all documents"
                     :description "Rebuild search index from database (users or items)"
                     :parameters {:path {:index {:type :string :enum ["users" "items"] :description "Index name"}}}}}}
   
   {:path "/search/stats"
    :methods {:get {:handler (stats-handler search-service)
                    :summary "Get search statistics"
                    :description "Retrieve statistics about search indexes and query counts"}}}])

(defn search-routes-normalized
  "Define search module routes in normalized format for top-level composition.
   
   Returns a map with route categories:
   - :api - REST API routes (will be mounted under /api)
   - :web - Web UI routes (empty - no web UI for search)
   - :static - Static asset routes (empty)
   
   Args:
     search-service - Search service instance
     config - Application configuration map (unused currently)
   
   Returns:
     Map with keys :api, :web, :static containing normalized route vectors"
  [search-service _config]
  {:api (normalized-api-routes search-service)
   :web []  ; No web UI for search module
   :static []})
