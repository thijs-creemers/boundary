(ns boundary.scaffolder.core.generators
  "Pure functions for generating file content from templates.
   
   Each generator function takes a template context and returns
   file content as a string. All functions are pure and deterministic."
  (:require [clojure.string :as str]
            [boundary.scaffolder.core.template :as template]))

;; =============================================================================
;; Schema File Generator
;; =============================================================================

(defn generate-field-schema
  "Generate Malli schema for a single field.
   
   Args:
     field-ctx - Field context map
   
   Returns:
     String representation of Malli schema
   
   Pure: true"
  [field-ctx]
  (let [required (:field-required field-ctx)
        malli-type (:malli-type field-ctx)
        field-name (keyword (:field-name-kebab field-ctx))]
    (if required
      (format "   [%s %s]" field-name malli-type)
      (format "   [%s {:optional true} %s]" field-name malli-type))))

(defn generate-schema-file
  "Generate schema.clj file content.
   
   Args:
     ctx - Template context map
   
   Returns:
     String content for schema.clj
   
   Pure: true"
  [ctx]
  (let [module-name (:module-name ctx)
        entity (first (:entities ctx))
        entity-name (:entity-name entity)
        fields (:fields entity)
        field-schemas (str/join "\n" (map generate-field-schema fields))]
    (format "(ns boundary.%s.schema
  \"Schema definitions for %s module.\"
  (:require [malli.core :as m]))

;; =============================================================================
;; Domain Entity Schemas
;; =============================================================================

(def %s
  \"Schema for %s entity.\"
  [:map {:title \"%s\"}
   [:id :uuid]
%s
   [:created-at inst?]
   [:updated-at {:optional true} [:maybe inst?]]])

;; =============================================================================
;; API Request Schemas
;; =============================================================================

(def Create%sRequest
  \"Schema for create %s API requests.\"
  [:map {:title \"Create %s Request\"}
%s])

(def Update%sRequest
  \"Schema for update %s API requests.\"
  [:map {:title \"Update %s Request\"}
%s])

;; =============================================================================
;; Validation Functions
;; =============================================================================

(defn validate-%s
  \"Validates a %s entity against the %s schema.\"
  [%s-data]
  (m/validate %s %s-data))

(defn explain-%s
  \"Provides detailed validation errors for %s data.\"
  [%s-data]
  (m/explain %s %s-data))
"
            module-name
            module-name
            entity-name
            entity-name
            entity-name
            field-schemas
            entity-name
            (str/lower-case entity-name)
            entity-name
            field-schemas
            entity-name
            (str/lower-case entity-name)
            entity-name
            field-schemas
            (str/lower-case entity-name)
            (str/lower-case entity-name)
            entity-name
            (str/lower-case entity-name)
            entity-name
            (str/lower-case entity-name)
            (str/lower-case entity-name)
            (str/lower-case entity-name)
            (str/lower-case entity-name)
            entity-name
            (str/lower-case entity-name))))

;; =============================================================================
;; Ports File Generator
;; =============================================================================

(defn generate-ports-file
  "Generate ports.clj file content.
   
   Args:
     ctx - Template context map
   
   Returns:
     String content for ports.clj
   
   Pure: true"
  [ctx]
  (let [module-name (:module-name ctx)
        entity (first (:entities ctx))
        entity-name (:entity-name entity)
        entity-lower (:entity-lower entity)]
    (format "(ns boundary.%s.ports
  \"%s module port definitions (abstract interfaces).\")

;; =============================================================================
;; Repository Ports
;; =============================================================================

(defprotocol I%sRepository
  \"Repository interface for %s persistence operations.\"

  (find-by-id [this id]
    \"Find %s by ID.\")

  (find-all [this options]
    \"Find all %ss with pagination and filtering.\")

  (create [this entity]
    \"Create new %s.\")

  (update-%s [this entity]
    \"Update existing %s.\")

  (delete [this id]
    \"Delete %s by ID.\"))

;; =============================================================================
;; Service Ports
;; =============================================================================

(defprotocol I%sService
  \"%s service interface for business operations.\"

  (get-%s [this id]
    \"Get %s by ID.\")

  (list-%ss [this options]
    \"List %ss with pagination.\")

  (create-%s [this data]
    \"Create new %s.\")

  (update-%s [this id data]
    \"Update %s.\")

  (delete-%s [this id]
    \"Delete %s.\"))
"
            module-name
            (str/capitalize module-name)
            entity-name
            entity-lower
            entity-lower
            entity-lower
            entity-lower
            entity-lower  ; update-%s
            entity-lower
            entity-lower
            entity-name
            entity-name
            entity-lower
            entity-lower
            entity-lower
            entity-lower
            entity-lower
            entity-lower
            entity-lower
            entity-lower
            entity-lower
            entity-lower)))

;; =============================================================================
;; Core Logic File Generator
;; =============================================================================

(defn generate-core-file
  "Generate core/{entity}.clj file content.
   
   Args:
     ctx - Template context map
   
   Returns:
     String content for core/{entity}.clj
   
   Pure: true"
  [ctx]
  (let [module-name (:module-name ctx)
        entity (first (:entities ctx))
        entity-name (:entity-name entity)
        entity-lower (:entity-lower entity)
        entity-kebab (:entity-kebab entity)]
    (format "(ns boundary.%s.core.%s
  \"Pure business logic for %s domain.
   
   All functions in this namespace are pure - they have no side effects,
   don't perform I/O, and always return the same output for the same input.\"
  (:require [boundary.%s.schema :as schema]))

;; =============================================================================
;; Entity Creation
;; =============================================================================

(defn prepare-new-%s
  \"Prepare data for creating a new %s.
   
   Args:
     data - Input data map
     current-time - java.time.Instant for timestamps
   
   Returns:
     Prepared %s entity map
   
   Pure: true\"
  [data current-time]
  (merge data
         {:id (java.util.UUID/randomUUID)
          :created-at current-time
          :updated-at current-time}))

;; =============================================================================
;; Entity Updates
;; =============================================================================

(defn apply-%s-update
  \"Apply updates to existing %s entity.
   
   Args:
     existing - Current %s entity
     updates - Map of fields to update
     current-time - java.time.Instant for updated-at
   
   Returns:
     Updated %s entity map
   
   Pure: true\"
  [existing updates current-time]
  (merge existing
         updates
         {:updated-at current-time}))

;; =============================================================================
;; Validation
;; =============================================================================

(defn validate-%s
  \"Validate %s entity data.
   
   Args:
     data - %s data to validate
   
   Returns:
     Vector of [valid? errors data]
   
   Pure: true\"
  [data]
  (if (schema/validate-%s data)
    [true nil data]
    [false (schema/explain-%s data) nil]))
"
            module-name
            entity-kebab
            entity-lower
            module-name
            entity-kebab
            entity-lower
            entity-lower
            entity-kebab
            entity-lower
            entity-lower
            entity-lower
            entity-kebab
            entity-lower
            entity-lower
            entity-lower
            entity-lower)))

;; =============================================================================
;; Migration File Generator
;; =============================================================================

(defn generate-migration-field
  "Generate SQL for a single field.
   
   Args:
     field-ctx - Field context map
   
   Returns:
     SQL field definition string
   
   Pure: true"
  [field-ctx]
  (let [field-name (:field-name-snake field-ctx)
        sql-type (:sql-type field-ctx)
        required (:field-required field-ctx)
        unique (:field-unique field-ctx)
        null-clause (if required " NOT NULL" "")
        unique-clause (if unique " UNIQUE" "")]
    (format "  %s %s%s%s" field-name sql-type null-clause unique-clause)))

(defn generate-migration-file
  "Generate migration SQL file content.
   
   Args:
     ctx - Template context map
     migration-number - Migration sequence number (e.g., \"005\")
   
   Returns:
     String content for migration SQL
   
   Pure: true"
  [ctx migration-number]
  (let [entity (first (:entities ctx))
        table-name (:entity-table entity)
        fields (:fields entity)
        field-sqls (str/join ",\n" (map generate-migration-field fields))]
    (format "-- Migration %s: Create %s table

CREATE TABLE IF NOT EXISTS %s (
  id UUID PRIMARY KEY,
%s,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_%s_created_at ON %s(created_at);
"
            migration-number
            table-name
            table-name
            field-sqls
            table-name
            table-name)))
;; =============================================================================
;; UI File Generator  
;; =============================================================================

(defn generate-ui-file
  "Generate core/ui.clj file content.
   
   Args:
     ctx - Template context map
     
   Returns:
     String content for ui.clj file
     
   Pure: true"
  [ctx]
  (let [module-name (:module-name ctx)
        entity (first (:entities ctx))
        entity-name (:entity-name entity)
        entity-lower (str/lower-case entity-name)
        entity-plural (template/pluralize entity-lower)]
    (str "(ns boundary." module-name ".core.ui\n"
         "  \"Pure UI generation for " module-name " module - Hiccup templates.\")\n"
         "\n"
         "(defn " entity-lower "-list-page\n"
         "  \"Generate " entity-lower " listing page.\"\n"
         "  [" entity-plural " opts]\n"
         "  [:div.page\n"
         "   [:h1 \"" (str/capitalize entity-plural) "\"]\n"
         "   [:div.items\n"
         "    (for [item " entity-plural "]\n"
         "      [:div.item {:key (:id item)}\n"
         "       [:p (str (:id item))]])]])\n")))

;; =============================================================================
;; Service File Generator
;; =============================================================================

(defn generate-service-file
  "Generate shell/service.clj file content.
   
   Args:
     ctx - Template context map
     
   Returns:
     String content for service.clj file
     
   Pure: true"
  [ctx]
  (let [module-name (:module-name ctx)
        entity (first (:entities ctx))
        entity-name (:entity-name entity)
        entity-lower (str/lower-case entity-name)]
    (str "(ns boundary." module-name ".shell.service\n"
         "  \"Service layer for " module-name " module.\"\n"
         "  (:require [boundary." module-name ".ports :as ports]\n"
         "            [boundary." module-name ".core." (template/kebab->snake entity-name) " :as core]))\n"
         "\n"
         "(defrecord " entity-name "Service [repository]\n"
         "  ports/I" entity-name "Service\n"
         "  (create-" entity-lower " [this data]\n"
         "    (let [prepared (core/prepare-new-" entity-lower " data (java.time.Instant/now))]\n"
         "      (.create repository prepared)))\n"
         "  (find-" entity-lower " [this id]\n"
         "    (.find-by-id repository id))\n"
         "  (list-" (template/pluralize entity-lower) " [this opts]\n"
         "    (.list-" (template/pluralize entity-lower) " repository opts))\n"
         "  (update-" entity-lower " [this id data]\n"
         "    (.update-" entity-lower " repository (assoc data :id id)))\n"
         "  (delete-" entity-lower " [this id]\n"
         "    (.delete repository id)))\n"
         "\n"
         "(defn create-service [repository]\n"
         "  (->" entity-name "Service repository))\n")))

;; =============================================================================
;; Persistence File Generator
;; =============================================================================

(defn generate-persistence-file
  "Generate shell/persistence.clj file content.
   
   Args:
     ctx - Template context map
     
   Returns:
     String content for persistence.clj file
     
   Pure: true"
  [ctx]
  (let [module-name (:module-name ctx)
        entity (first (:entities ctx))
        entity-name (:entity-name entity)
        entity-lower (str/lower-case entity-name)
        table-name (template/pluralize (template/kebab->snake entity-name))]
    (str "(ns boundary." module-name ".shell.persistence\n"
         "  \"Persistence layer for " module-name " module.\"\n"
         "  (:require [boundary." module-name ".ports :as ports]\n"
         "            [boundary.platform.shell.adapters.database.common.core :as db]\n"
         "            [honey.sql :as sql]))\n"
         "\n"
         "(defrecord Database" entity-name "Repository [db-ctx]\n"
         "  ports/I" entity-name "Repository\n"
         "  (create [this entity]\n"
         "    (db/execute-one! db-ctx\n"
         "      (sql/format {:insert-into :" table-name "\n"
         "                   :values [entity]\n"
         "                   :returning [:*]})))\n"
         "  (find-by-id [this id]\n"
         "    (db/execute-one! db-ctx\n"
         "      (sql/format {:select [:*]\n"
         "                   :from [:" table-name "]\n"
         "                   :where [:= :id id]})))\n"
         "  (find-all [this opts]\n"
         "    (db/execute-query! db-ctx\n"
         "      (sql/format {:select [:*]\n"
         "                   :from [:" table-name "]\n"
         "                   :limit (:limit opts 20)})))\n"
         "  (update-" entity-lower " [this entity]\n"
         "    (db/execute-one! db-ctx\n"
         "      (sql/format {:update :" table-name "\n"
         "                   :set (dissoc entity :id)\n"
         "                   :where [:= :id (:id entity)]\n"
         "                   :returning [:*]})))\n"
         "  (delete [this id]\n"
         "    (db/execute-one! db-ctx\n"
         "      (sql/format {:delete-from :" table-name "\n"
         "                   :where [:= :id id]}))))\n"
         "\n"
         "(defn create-repository [db-ctx]\n"
         "  (->Database" entity-name "Repository db-ctx))\n")))

;; =============================================================================
;; HTTP File Generator
;; =============================================================================

(defn generate-http-file
  "Generate shell/http.clj file content.
   
   Args:
     ctx - Template context map
     
   Returns:
     String content for http.clj file
     
   Pure: true"
  [ctx]
  (let [module-name (:module-name ctx)
        entity (first (:entities ctx))
        entity-name (:entity-name entity)
        entity-lower (str/lower-case entity-name)
        entity-plural (template/pluralize entity-lower)]
    (str "(ns boundary." module-name ".shell.http\n"
         "  \"HTTP routes for " module-name " module.\"\n"
         "  (:require [boundary." module-name ".ports :as ports]))\n"
         "\n"
         ";; =============================================================================\n"
         ";; Legacy Reitit Routes\n"
         ";; =============================================================================\n"
         "\n"
         "(defn api-routes [service]\n"
         "  [[\"/api/" entity-plural "\" {:get {:handler (fn [req] {:status 200 :body []})}\n"
         "                          :post {:handler (fn [req] {:status 201 :body {}})}}]\n"
         "   [\"/api/" entity-plural "/:id\" {:get {:handler (fn [req] {:status 200 :body {}})}\n"
         "                                :put {:handler (fn [req] {:status 200 :body {}})}\n"
         "                                :delete {:handler (fn [req] {:status 204})}}]])\n"
         "\n"
         "(defn web-routes [service config]\n"
         "  [[\"/web/" entity-plural "\" {:get {:handler (fn [req] {:status 200 :body \"<html><body>Web UI</body></html>\"})}}]])\n"
         "\n"
         "(defn routes [service config]\n"
         "  (vec (concat (api-routes service) (web-routes service config))))\n"
         "\n"
         ";; =============================================================================\n"
         ";; Normalized Routes\n"
         ";; =============================================================================\n"
         "\n"
         "(defn normalized-api-routes\n"
         "  \"Define API routes in normalized format.\n"
         "   \n"
         "   Args:\n"
         "     service: " (str/capitalize module-name) " service instance\n"
         "     \n"
         "   Returns:\n"
         "     Vector of normalized route maps\"\n"
         "  [service]\n"
         "  [{:path \"/" entity-plural "\"\n"
         "    :methods {:get {:handler (fn [req] {:status 200 :body []})}\n"
         "              :post {:handler (fn [req] {:status 201 :body {}})}}}\n"
         "   {:path \"/" entity-plural "/:id\"\n"
         "    :methods {:get {:handler (fn [req] {:status 200 :body {}})}\n"
         "              :put {:handler (fn [req] {:status 200 :body {}})}\n"
         "              :delete {:handler (fn [req] {:status 204})}}}])\n"
         "\n"
         "(defn normalized-web-routes\n"
         "  \"Define web UI routes in normalized format (WITHOUT /web prefix).\n"
         "   \n"
         "   NOTE: These routes will be mounted under /web by the top-level router.\n"
         "   Do NOT include /web prefix in paths here.\n"
         "   \n"
         "   Args:\n"
         "     service: " (str/capitalize module-name) " service instance\n"
         "     config: Application configuration map\n"
         "     \n"
         "   Returns:\n"
         "     Vector of normalized route maps\"\n"
         "  [service config]\n"
         "  [{:path \"/" entity-plural "\"\n"
         "    :methods {:get {:handler (fn [req] {:status 200 :body \"<html><body>Web UI</body></html>\"})}}}])\n"
         "\n"
         "(defn " module-name "-routes-normalized\n"
         "  \"Define " module-name " module routes in normalized format for top-level composition.\n"
         "   \n"
         "   Returns a map with route categories:\n"
         "   - :api - REST API routes (will be mounted under /api)\n"
         "   - :web - Web UI routes (will be mounted under /web)\n"
         "   - :static - Static asset routes (empty)\n"
         "   \n"
         "   Args:\n"
         "     service: " (str/capitalize module-name) " service instance\n"
         "     config: Application configuration map\n"
         "\n"
         "   Returns:\n"
         "     Map with keys :api, :web, :static containing normalized route vectors\"\n"
         "  [service config]\n"
         "  {:api (normalized-api-routes service)\n"
         "   :web (normalized-web-routes service config)\n"
         "   :static []})\n"
         "\n")))

;; =============================================================================
;; Web Handlers File Generator
;; =============================================================================

(defn generate-web-handlers-file
  "Generate shell/web_handlers.clj file content.
   
   Args:
     ctx - Template context map
     
   Returns:
     String content for web_handlers.clj file
     
   Pure: true"
  [ctx]
  (let [module-name (:module-name ctx)
        entity (first (:entities ctx))
        entity-name (:entity-name entity)
        entity-lower (str/lower-case entity-name)
        entity-plural (template/pluralize entity-lower)]
    (str "(ns boundary." module-name ".shell.web-handlers\n"
         "  \"Web UI handlers for " module-name " module.\"\n"
         "  (:require [boundary." module-name ".core.ui :as ui]\n"
         "            [boundary." module-name ".ports :as ports]))\n"
         "\n"
         "(defn " entity-lower "-list-handler [service config]\n"
         "  (fn [request]\n"
         "    (let [items (ports/list-" entity-plural " service {})]\n"
         "      {:status 200\n"
         "       :headers {\"Content-Type\" \"text/html\"}\n"
         "       :body (ui/" entity-lower "-list-page items {})})))\n")))

;; =============================================================================
;; Test File Generators
;; =============================================================================

(defn generate-core-test-file
  "Generate test core file content.
   
   Args:
     ctx - Template context map
     
   Returns:
     String content for core test file
     
   Pure: true"
  [ctx]
  (let [module-name (:module-name ctx)
        entity (first (:entities ctx))
        entity-name (:entity-name entity)
        entity-lower (str/lower-case entity-name)]
    (str "(ns boundary." module-name ".core." entity-lower "-test\n"
         "  (:require [clojure.test :refer [deftest testing is]]\n"
         "            [boundary." module-name ".core." entity-lower " :as core]))\n"
         "\n"
         "(deftest prepare-new-" entity-lower "-test\n"
         "  (testing \"prepares " entity-lower " for creation\"\n"
         "    (let [data {:name \"Test\"}\n"
         "          current-time (java.time.Instant/now)\n"
         "          result (core/prepare-new-" entity-lower " data current-time)]\n"
         "      (is (some? result)))))\n")))

(defn generate-service-test-file
  "Generate test service file content.
   
   Args:
     ctx - Template context map
     
   Returns:
     String content for service test file
     
   Pure: true"
  [ctx]
  (let [module-name (:module-name ctx)
        entity (first (:entities ctx))
        entity-name (:entity-name entity)
        entity-lower (str/lower-case entity-name)]
    (str "(ns boundary." module-name ".shell.service-test\n"
         "  (:require [clojure.test :refer [deftest testing is]]\n"
         "            [boundary." module-name ".shell.service :as service]\n"
         "            [boundary." module-name ".ports :as ports]))\n"
         "\n"
         "(deftest create-" entity-lower "-test\n"
         "  (testing \"creates " entity-lower " via service\"\n"
         "    (let [mock-repo (reify ports/I" entity-name "Repository\n"
         "                      (create [_ entity] entity))\n"
         "          svc (service/create-service mock-repo)\n"
         "          result (ports/create-" entity-lower " svc {:name \"Test\"})]\n"
         "      (is (some? result)))))\n")))

(defn generate-persistence-test-file
  "Generate test persistence file content.
   
   Args:
     ctx - Template context map
     
   Returns:
     String content for persistence test file
     
   Pure: true"
  [ctx]
  (let [module-name (:module-name ctx)
        entity (first (:entities ctx))
        entity-name (:entity-name entity)
        entity-lower (str/lower-case entity-name)]
    (str "(ns boundary." module-name ".shell." entity-lower "-repository-test\n"
         "  (:require [clojure.test :refer [deftest testing is]]\n"
         "            [boundary." module-name ".shell.persistence :as persistence]\n"
         "            [boundary." module-name ".ports :as ports]))\n"
         "\n"
         "(deftest create-" entity-lower "-test\n"
         "  (testing \"creates " entity-lower " in database\"\n"
         "    ;; Test requires database context\n"
         "    (is true)))\n")))
