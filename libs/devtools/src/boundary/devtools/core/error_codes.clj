(ns boundary.devtools.core.error-codes
  "Error code catalog for Boundary.
   Pure data — no I/O, no side effects.

   Error code ranges:
     BND-1xx  Configuration errors
     BND-2xx  Validation errors
     BND-3xx  Persistence errors
     BND-4xx  Authentication/authorization errors
     BND-5xx  Interceptor pipeline errors
     BND-6xx  FC/IS boundary violations")

;; =============================================================================
;; Error catalog
;; =============================================================================

(def catalog
  "Map of error code string to error definition."
  {"BND-101" {:code        "BND-101"
              :category    :config
              :title       "Missing Environment Variable"
              :description "A required #env reference in config.edn is not set and has no #or default."
              :fix         "Set the environment variable before starting, or add a default with #or."}

   "BND-102" {:code        "BND-102"
              :category    :config
              :title       "Unknown Provider"
              :description "A :provider value in config.edn is not recognized."
              :fix         "Check the valid providers list in libs/tools/AGENTS.md."}

   "BND-103" {:code        "BND-103"
              :category    :config
              :title       "Missing JWT Secret"
              :description "JWT_SECRET is not set but the user module is active."
              :fix         "export JWT_SECRET=\"your-secret-at-least-32-characters\""}

   "BND-104" {:code        "BND-104"
              :category    :config
              :title       "Placeholder Value in Production"
              :description "A placeholder value (TODO, example.com, CHANGEME) was found in production config."
              :fix         "Replace all placeholder values with real configuration."}

   "BND-105" {:code        "BND-105"
              :category    :config
              :title       "Dangerous Reset Endpoint Enabled"
              :description ":test/reset-endpoint-enabled? is true outside of dev/test profiles."
              :fix         "Remove or set to false in production config."}

   "BND-201" {:code        "BND-201"
              :category    :validation
              :title       "Schema Validation Failed"
              :description "Input data does not match the expected Malli schema."
              :fix         "Check the schema definition and ensure all required fields are present with valid values."}

   "BND-202" {:code        "BND-202"
              :category    :validation
              :title       "Invalid Field Format"
              :description "A field value does not match the expected format (email, URL, etc.)."
              :fix         "Check the schema constraints for the specific field."}

   "BND-203" {:code        "BND-203"
              :category    :validation
              :title       "Case Conversion Mismatch"
              :description "A field name uses the wrong case convention at a boundary crossing."
              :fix         "Use boundary.core.utils.case-conversion for conversions between kebab-case (Clojure), snake_case (DB), and camelCase (API)."}

   "BND-301" {:code        "BND-301"
              :category    :persistence
              :title       "Migration Not Applied"
              :description "A database table or column is missing because a migration has not been run."
              :fix         "Run: bb migrate up"}

   "BND-302" {:code        "BND-302"
              :category    :persistence
              :title       "Connection Pool Exhausted"
              :description "All database connections in the HikariCP pool are in use."
              :fix         "Check for connection leaks or increase :pool-size in config."}

   "BND-303" {:code        "BND-303"
              :category    :persistence
              :title       "Database Connection Failed"
              :description "Could not connect to the configured database."
              :fix         "Verify the database is running and config values (host, port, name) are correct."}

   "BND-401" {:code        "BND-401"
              :category    :auth
              :title       "Authentication Required"
              :description "The request requires authentication but no valid token/session was provided."
              :fix         "Include a valid JWT token or session cookie with the request."}

   "BND-402" {:code        "BND-402"
              :category    :auth
              :title       "Insufficient Permissions"
              :description "The authenticated user does not have the required role for this action."
              :fix         "Check the route's required roles and the user's assigned roles."}

   "BND-501" {:code        "BND-501"
              :category    :interceptor
              :title       "Interceptor Error"
              :description "An interceptor in the pipeline threw an unhandled exception."
              :fix         "Check the interceptor chain for the failing route with (interceptors :handler-name)."}

   "BND-601" {:code        "BND-601"
              :category    :fcis
              :title       "FC/IS Boundary Violation"
              :description "A core namespace imports from a shell namespace, violating the Functional Core / Imperative Shell boundary."
              :fix         "Move the data access behind a port (protocol) in ports.clj, then have shell implement it."}

   "BND-602" {:code        "BND-602"
              :category    :fcis
              :title       "Core Namespace Uses I/O"
              :description "A core namespace uses I/O operations (logging, file access, HTTP) directly."
              :fix         "Core functions must be pure. Move I/O to the shell layer."}})

;; =============================================================================
;; Lookup functions
;; =============================================================================

(defn lookup
  "Look up an error code. Returns the error definition map or nil."
  [code]
  (get catalog code))

(defn by-category
  "Get all error codes for a category (:config, :validation, :persistence, :auth, :interceptor, :fcis)."
  [category]
  (->> (vals catalog)
       (filter #(= category (:category %)))
       (sort-by :code)))

(defn all-codes
  "Get all error codes sorted."
  []
  (sort-by :code (vals catalog)))

(defn category-range
  "Get the human-readable range description for a category."
  [category]
  (case category
    :config      "BND-1xx"
    :validation  "BND-2xx"
    :persistence "BND-3xx"
    :auth        "BND-4xx"
    :interceptor "BND-5xx"
    :fcis        "BND-6xx"
    "BND-???"))
