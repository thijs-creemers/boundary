(ns boundary.logging.schema
  "Configuration schemas for logging infrastructure.
   
   This namespace defines Malli schemas for validating logging configuration,
   ensuring proper structure and constraints for different logging providers."
  (:require
   [malli.core :as m]
   [malli.transform :as mt]))

;; =============================================================================
;; Core Logging Configuration Schemas
;; =============================================================================

(def LogLevel
  "Valid logging levels in order of severity."
  [:enum :trace :debug :info :warn :error :fatal])

(def LogProvider
  "Supported logging providers."
  [:enum :stdout :json :structured :file :syslog :datadog :custom])

(def CorrelationConfig
  "Configuration for correlation ID handling."
  [:map {:title "Correlation Configuration"}
   [:header {:optional true} [:string {:min 1 :max 100}]]
   [:generate-if-missing {:optional true} :boolean]
   [:include-in-logs {:optional true} :boolean]])

(def SamplingConfig
  "Configuration for log sampling rates."
  [:map {:title "Sampling Configuration"}
   [:trace {:optional true} [:double {:min 0.0 :max 1.0}]]
   [:debug {:optional true} [:double {:min 0.0 :max 1.0}]]
   [:info {:optional true} [:double {:min 0.0 :max 1.0}]]
   [:warn {:optional true} [:double {:min 0.0 :max 1.0}]]
   [:error {:optional true} [:double {:min 0.0 :max 1.0}]]
   [:fatal {:optional true} [:double {:min 0.0 :max 1.0}]]
   [:audit {:optional true} [:double {:min 0.0 :max 1.0}]]])

(def BaseLoggingConfig
  "Base logging configuration shared by all providers."
  [:map {:title "Base Logging Configuration"}
   [:provider LogProvider]
   [:level {:optional true} LogLevel]
   [:enabled {:optional true} :boolean]
   [:correlation {:optional true} CorrelationConfig]
   [:sampling {:optional true} SamplingConfig]
   [:default-tags {:optional true} [:map-of :keyword :string]]
   [:format {:optional true} [:enum :text :json :structured]]])

;; =============================================================================
;; Provider-Specific Configuration Schemas
;; =============================================================================

(def StdoutLoggingConfig
  "Configuration for stdout logging provider."
  [:and BaseLoggingConfig
   [:map {:title "Stdout Logging Configuration"}
    [:provider [:= :stdout]]
    [:json {:optional true} :boolean]
    [:colors {:optional true} :boolean]
    [:include-timestamp {:optional true} :boolean]
    [:include-level {:optional true} :boolean]
    [:include-thread {:optional true} :boolean]]])

(def JsonLoggingConfig
  "Configuration for JSON logging provider."
  [:and BaseLoggingConfig
   [:map {:title "JSON Logging Configuration"}
    [:provider [:= :json]]
    [:pretty {:optional true} :boolean]
    [:include-stacktrace {:optional true} :boolean]
    [:max-stacktrace-elements {:optional true} [:int {:min 1 :max 1000}]]]])

(def FileLoggingConfig
  "Configuration for file logging provider."
  [:and BaseLoggingConfig
   [:map {:title "File Logging Configuration"}
    [:provider [:= :file]]
    [:path [:string {:min 1 :max 500}]]
    [:max-size {:optional true} [:int {:min 1024}]] ; bytes
    [:max-files {:optional true} [:int {:min 1 :max 100}]]
    [:append {:optional true} :boolean]
    [:buffer-size {:optional true} [:int {:min 1024 :max 65536}]]
    [:flush-interval {:optional true} [:int {:min 100 :max 60000}]]]])

(def StructuredLoggingConfig
  "Configuration for structured logging provider (ELK, etc.)."
  [:and BaseLoggingConfig
   [:map {:title "Structured Logging Configuration"}
    [:provider [:= :structured]]
    [:index-prefix {:optional true} [:string {:min 1 :max 100}]]
    [:include-host {:optional true} :boolean]
    [:include-service {:optional true} :boolean]
    [:service-name {:optional true} [:string {:min 1 :max 100}]]
    [:service-version {:optional true} [:string {:min 1 :max 50}]]]])

(def DatadogLoggingConfig
  "Configuration for Datadog logging provider."
  [:and BaseLoggingConfig
   [:map {:title "Datadog Logging Configuration"}
    [:provider [:= :datadog]]
    [:api-key [:string {:min 32 :max 32}]]
    [:service [:string {:min 1 :max 100}]]
    [:source {:optional true} [:string {:min 1 :max 100}]]
    [:hostname {:optional true} [:string {:min 1 :max 253}]]
    [:tags {:optional true} [:vector :string]]
    [:endpoint {:optional true} [:string {:min 1 :max 500}]]
    [:batch-size {:optional true} [:int {:min 1 :max 1000}]]
    [:flush-interval {:optional true} [:int {:min 1000 :max 60000}]]]])

(def CustomLoggingConfig
  "Configuration for custom logging provider."
  [:and BaseLoggingConfig
   [:map {:title "Custom Logging Configuration"}
    [:provider [:= :custom]]
    [:adapter-ns [:string {:min 1 :max 200}]]
    [:adapter-config {:optional true} :map]]])

;; =============================================================================
;; Unified Configuration Schema
;; =============================================================================

(def LoggingConfig
  "Unified logging configuration schema that validates any provider."
  [:multi {:dispatch :provider
           :title "Logging Configuration"}
   [:stdout StdoutLoggingConfig]
   [:json JsonLoggingConfig]
   [:file FileLoggingConfig]
   [:structured StructuredLoggingConfig]
   [:datadog DatadogLoggingConfig]
   [:custom CustomLoggingConfig]])

;; =============================================================================
;; Audit Logging Configuration Schemas
;; =============================================================================

(def AuditEventType
  "Types of audit events to track."
  [:enum :user-action :system-event :security-event :data-change :api-call])

(def AuditLoggingConfig
  "Configuration for audit logging."
  [:map {:title "Audit Logging Configuration"}
   [:enabled {:optional true} :boolean]
   [:provider {:optional true} LogProvider]
   [:level {:optional true} LogLevel]
   [:event-types {:optional true} [:vector AuditEventType]]
   [:include-request-body {:optional true} :boolean]
   [:include-response-body {:optional true} :boolean]
   [:max-body-size {:optional true} [:int {:min 0 :max 1048576}]] ; 1MB max
   [:sensitive-fields {:optional true} [:vector :string]]
   [:retention-days {:optional true} [:int {:min 1 :max 2555}]}]) ; ~7 years max

;; =============================================================================
;; Complete System Configuration Schema
;; =============================================================================

(def SystemLoggingConfig
  "Complete system logging configuration."
  [:map {:title "System Logging Configuration"}
   [:application {:optional true} LoggingConfig]
   [:audit {:optional true} AuditLoggingConfig]
   [:performance {:optional true} LoggingConfig]
   [:security {:optional true} AuditLoggingConfig]])

;; =============================================================================
;; Default Configurations
;; =============================================================================

(def default-stdout-config
  "Default stdout logging configuration."
  {:provider :stdout
   :level :info
   :enabled true
   :json false
   :colors true
   :include-timestamp true
   :include-level true
   :include-thread false
   :correlation {:header \"x-correlation-id\"
                 :generate-if-missing true
                 :include-in-logs true}
   :default-tags {:service \"boundary\"}})

(def default-json-config
  "Default JSON logging configuration."
  {:provider :json
   :level :info
   :enabled true
   :pretty false
   :include-stacktrace true
   :max-stacktrace-elements 50
   :correlation {:header \"x-correlation-id\"
                 :generate-if-missing true
                 :include-in-logs true}
   :default-tags {:service \"boundary\"}})

(def default-audit-config
  "Default audit logging configuration."
  {:enabled true
   :provider :json
   :level :info
   :event-types [:user-action :security-event :data-change]
   :include-request-body false
   :include-response-body false
   :max-body-size 4096
   :sensitive-fields [\"password\" \"token\" \"secret\" \"key\"]
   :retention-days 90})

;; =============================================================================
;; Validation Functions
;; =============================================================================

(defn validate-logging-config
  "Validates a logging configuration map."
  [config]
  (m/validate LoggingConfig config))

(defn validate-system-logging-config
  "Validates a complete system logging configuration map."
  [config]
  (m/validate SystemLoggingConfig config))

(defn explain-logging-config
  "Provides detailed validation errors for logging configuration."
  [config]
  (m/explain LoggingConfig config))

(defn explain-system-logging-config
  "Provides detailed validation errors for system logging configuration."
  [config]
  (m/explain SystemLoggingConfig config))

;; =============================================================================
;; Configuration Transformation
;; =============================================================================

(def logging-config-transformer
  "Transforms external configuration to internal format."
  (mt/transformer
   mt/strip-extra-keys-transformer
   mt/string-transformer
   {:name :logging-config}))

(defn normalize-logging-config
  "Normalizes and validates a logging configuration map."
  [config]
  (let [normalized (m/decode LoggingConfig config logging-config-transformer)]
    (if (validate-logging-config normalized)
      normalized
      (throw (ex-info \"Invalid logging configuration\"
                      {:errors (explain-logging-config normalized)
                       :config config})))))

(defn normalize-system-logging-config
  "Normalizes and validates a system logging configuration map."
  [config]
  (let [normalized (m/decode SystemLoggingConfig config logging-config-transformer)]
    (if (validate-system-logging-config normalized)
      normalized
      (throw (ex-info \"Invalid system logging configuration\"
                      {:errors (explain-system-logging-config normalized)
                       :config config})))))

;; =============================================================================
;; Schema Registry
;; =============================================================================

(def schema-registry
  "Registry of all logging schemas for easy access."
  {:core
   {:logging-config LoggingConfig
    :system-logging-config SystemLoggingConfig
    :audit-config AuditLoggingConfig}
   :providers
   {:stdout StdoutLoggingConfig
    :json JsonLoggingConfig
    :file FileLoggingConfig
    :structured StructuredLoggingConfig
    :datadog DatadogLoggingConfig
    :custom CustomLoggingConfig}
   :common
   {:log-level LogLevel
    :log-provider LogProvider
    :correlation-config CorrelationConfig
    :sampling-config SamplingConfig}})

(defn get-schema
  "Retrieves a schema from the registry by category and name."
  [category schema-name]
  (get-in schema-registry [category schema-name]))