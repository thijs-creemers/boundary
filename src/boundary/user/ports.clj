(ns boundary.user.ports
  "User module port definitions (abstract interfaces).
   
   This namespace defines all ports (abstract interfaces) that the user module
   needs to interact with external systems and services. These ports follow
   Boundary's hexagonal architecture pattern, allowing core business logic to
   remain pure and testable while enabling flexible adapter implementations.
   
   Port Categories:
   - Data Persistence Ports (IUserRepository, IUserSessionRepository)
   - Communication Ports (IEmailService, INotificationService)
   - System Service Ports (IEventBus, IAuditService, ITimeService)
   
   Each port is implemented by adapters in the shell layer, enabling dependency
   inversion and supporting multiple implementations (PostgreSQL, H2, in-memory, etc.)."
  (:require [boundary.user.schema :as schema]
            [malli.core :as m])
  (:import (java.util UUID)))

;; =============================================================================
;; Data Persistence Ports
;; =============================================================================

(defprotocol IUserRepository
  "User data persistence interface with comprehensive CRUD and business operations.
   
   This port abstracts all user data access patterns, supporting:
   - Full CRUD operations operations
   - Business-specific queries for roles, status, and date ranges
   - Batch operations for bulk data processing
   - Soft/hard delete operations for data lifecycle management
   
   All methods return
   domain entities as defined in boundary.user.schema."

  ;; Basic CRUD Operations
  (find-user-by-id [this user-id]
    "Retrieve user by unique identifier.
     
     Args:
       user-id: UUID of the user to find
     
     Returns:
       User entity map or nil if not found
       User must be active (not soft-deleted) to be returned
     
     Example:
       (find-user-by-id repo #uuid \"123e4567-e89b-12d3-a456-426614174000\")")

  (find-user-by-email [this email]
    "Retrieve user by email address.
     
     Args:
       email: User's email address (string)
     
     Returns:
       User entity map or nil if not found
       Only returns active users
     
     Example:
       (find-user-by-email repo \"john@example.com\")")

  (find-users [this options]
    "Retrieve paginated users with filtering and sorting.
     
     Args:
       options: Map with pagination and filtering options:
                {:limit 20
                 :offset 0
                 :sort-by :created-at
                 :sort-direction :desc
                 :filter-role :user
                 :filter-active true
                 :filter-email-contains \"@company.com\"}
     
     Returns:
       Map with :users (vector of user entities) and :total-count
       Only returns active users unless :include-deleted? true in options
     
     Example:
       (find-users repo {:limit 10 :filter-role :admin})")

  (create-user [this user-entity]
    "Create new user with automatic ID and timestamp generation.
     
     Args:
       user-entity: User entity map conforming to schema/User
                   (ID, created-at, updated-at will be generated)
     
     Returns:
       Created user entity with generated ID and timestamps
       Throws exception if email already exists
     
     Example:
       (create-user repo {:email \"new@example.com\"
                         :name \"New User\"
                         :role :user
                         :active true})")

  (update-user [this user-entity]
    "Update existing user with automatic updated-at timestamp.
     
     Args:
       user-entity: Complete user entity map with ID
                   (updated-at will be set automatically)
     
     Returns:
       Updated user entity with new updated-at timestamp
       Throws exception if user not found
     
     Example:
       (update-user repo (assoc existing-user :name \"Updated Name\"))")

  (soft-delete-user [this user-id]
    "Mark user as deleted without physical removal.
     
     Args:
       user-id: UUID of user to soft-delete
     
     Returns:
       Boolean indicating success
       Sets deleted-at timestamp, user will not appear in normal queries
       Related data (sessions, preferences) may be cleaned up separately
     
     Example:
       (soft-delete-user repo user-id)")

  (hard-delete-user [this user-id]
    "Permanently delete user and all related data.
     
     Args:
       user-id: UUID of user to permanently delete
     
     Returns:
       Boolean indicating success
       ⚠️  IRREVERSIBLE: Physically removes user and cascades to related data
       Should only be used for GDPR compliance or similar requirements
     
     Example:
       (hard-delete-user repo user-id)")

  ;; Business-Specific Queries
  (find-active-users-by-role [this role]
    "Find all active users with specific role.
     
     Args:
       role: Keyword role (:admin, :user, :viewer)
     
     Returns:
       Vector of user entities with specified role
       Only returns active (non-deleted) users
     
     Example:
       (find-active-users-by-role repo :admin)")

  (count-users [this]
    "Count total active users.
     
     Returns:
       Integer count of active users (excludes soft-deleted)
     
     Example:
       (count-users repo)")

  (find-users-created-since [this since-date]
    "Find users created after specified date.
     
     Args:
       since-date: Instant representing cutoff date
     
     Returns:
       Vector of user entities created after since-date
       Useful for analytics, recent user tracking, etc.
     
     Example:
       (find-users-created-since repo (time/minus (time/instant) (time/days 7)))")

  (find-users-by-email-domain [this email-domain]
    "Find users with email addresses from specific domain.
     
     Args:
       email-domain: String domain to match (e.g., \"company.com\")
     
     Returns:
       Vector of user entities with matching email domain
       Useful for organization-based user management
     
     Example:
       (find-users-by-email-domain repo \"company.com\")")

  ;; Batch Operations
  (create-users-batch [this user-entities]
    "Create multiple users in single transaction.
     
     Args:
       user-entities: Vector of user entity maps
     
     Returns:
       Vector of created user entities with generated IDs and timestamps
       All operations succeed or all fail (transaction boundary)
       Useful for bulk user imports, organization setup, etc.
     
     Example:
       (create-users-batch repo [{:email \"user1@example.com\" ...}
                                {:email \"user2@example.com\" ...}])")

  (update-users-batch [this user-entities]
    "Update multiple users in single transaction.
     
     Args:
       user-entities: Vector of complete user entity maps with IDs
     
     Returns:
       Vector of updated user entities
       All operations succeed or all fail (transaction boundary)
       Useful for bulk status changes, role assignments, etc.
     
     Example:
       (update-users-batch repo [updated-user1 updated-user2])"))

(defprotocol IUserSessionRepository
  "User session persistence for authentication and session management.
   
   This port manages user authentication sessions, supporting:
   - Session creation and validation
   - Token-based session lookup
   - Session lifecycle management (creation, validation, invalidation)
   - Expired session cleanup for maintenance
   
   Sessions provide secure, stateful authentication tracking across
   user interactions with the system."

  (create-session [this session-entity]
    "Create new user session with secure token generation.
     
     Args:
       session-entity: Session entity map conforming to schema/UserSession
                      (ID and session-token will be generated if not provided)
     
     Returns:
       Created session entity with generated ID, token, and timestamps
       Session token should be cryptographically secure random string
     
     Example:
       (create-session repo {:user-id user-id
                            :expires-at (time/plus (time/instant) (time/hours 24))
                            :user-agent \"Mozilla/5.0...\"
                            :ip-address \"192.168.1.1\"})")

  (find-session-by-token [this session-token]
    "Retrieve active session by token.
     
     Args:
       session-token: String session token to look up
     
     Returns:
       Session entity map or nil if not found or expired
       Should automatically check expiration and return nil for expired sessions
       Updates last-accessed-at timestamp when session is found
     
     Example:
       (find-session-by-token repo \"abc123def456...\")")

  (find-sessions-by-user [this user-id]
    "Find all active sessions for a user.
     
     Args:
       user-id: UUID of user to find sessions for
     
     Returns:
       Vector of active session entities for the user
       Excludes expired and revoked sessions
       Useful for session management, security monitoring
     
     Example:
       (find-sessions-by-user repo user-id)")

  (invalidate-session [this session-token]
    "Invalidate session by token (logout).
     
     Args:
       session-token: String session token to invalidate
     
     Returns:
       Boolean indicating success
       Sets revoked-at timestamp, making session permanently invalid
       Should be idempotent (safe to call multiple times)
     
     Example:
       (invalidate-session repo session-token)")

  (invalidate-all-user-sessions [this user-id]
    "Invalidate all sessions for a user (force logout everywhere).
     
     Args:
       user-id: UUID of user whose sessions to invalidate
     
     Returns:
       Integer count of invalidated sessions
       Useful for security incidents, password changes, etc.
     
     Example:
       (invalidate-all-user-sessions repo user-id)")

  (cleanup-expired-sessions [this before-timestamp]
    "Remove expired sessions for maintenance.
     
     Args:
       before-timestamp: Instant cutoff for cleanup
     
     Returns:
       Integer count of deleted sessions
       Should be called periodically to prevent session table growth
       Only removes sessions that expired before the cutoff
     
     Example:
       (cleanup-expired-sessions repo (time/minus (time/instant) (time/days 30)))")

  (update-session [this session-entity]
    "Update existing session (for access time updates, extensions, etc.).
     
     Args:
       session-entity: Complete session entity map with ID
     
     Returns:
       Updated session entity
       Used for updating access times, extending expiry, etc.
     
     Example:
       (update-session repo (assoc session :last-accessed-at (time/instant)))")

  (find-all-sessions [this]
    "Find all sessions (used for cleanup operations).
     
     Returns:
       Vector of all session entities
       Should be used carefully - may need pagination for large datasets
     
     Example:
       (find-all-sessions repo)")

  (delete-session [this session-id]
    "Permanently delete session by ID.
     
     Args:
       session-id: UUID of session to delete
     
     Returns:
       Boolean indicating success
       Used for cleanup operations
     
     Example:
       (delete-session repo session-id)"))

;; =============================================================================
;; Service Layer Ports
;; =============================================================================

(defprotocol IUserService
  "User domain service interface for business operations.
   
   This port defines the service layer interface for user domain operations.
   The service layer coordinates between pure business logic (core) and
   I/O operations (repositories), managing external dependencies like time,
   UUIDs, and logging.
   
   Service layer responsibilities:
   - Orchestrate complex business operations
   - Coordinate between multiple repositories
   - Handle external dependencies (time, IDs, logging)
   - Enforce business rules and validation
   - Manage transaction boundaries"

  ;; User Management Operations
  (register-user [this user-data]
    "Register a new user with full validation and business rule enforcement.
     
     Business operation that coordinates user registration including validation,
     uniqueness checks, and initial setup.
     
     Args:
       user-data: Map with user creation data
                 {:email string :name string :role keyword ...}
     
     Returns:
       Created user entity with generated ID and timestamps
       
     Throws:
       - ExceptionInfo with :type :validation-error for invalid data
       - ExceptionInfo with :type :user-exists for duplicate email
       - ExceptionInfo with :type :business-rule-violation for rule violations
     
     Example:
       (register-user service {:email \"new@example.com\"
                              :name \"New User\"
                              :role :user})")

  (get-user-by-id [this user-id]
    "Retrieve user by ID with service-level validation.
     
     Args:
       user-id: UUID of user to find
     
     Returns:
       User entity or nil if not found
       
     Example:
       (get-user-by-id service user-id)")

  (get-user-by-email [this email]
    "Retrieve user by email.
     
     Args:
       email: String email address
     
     Returns:
       User entity or nil if not found
       
     Example:
       (get-user-by-email service \"user@example.com\")")

  (list-users [this options]
    "List users with pagination and filtering.
     
     Args:
       options: Map with pagination/filtering options
     
     Returns:
       Map with :users vector and :total-count
       
     Example:
       (list-users service {:limit 10 :offset 0})")

  (update-user-profile [this user-entity]
    "Update user profile with validation and business rule enforcement.
     
     Business operation that validates profile changes and applies business rules.
     
     Args:
       user-entity: Complete user entity map with ID
     
     Returns:
       Updated user entity
       
     Throws:
       - ExceptionInfo with :type :user-not-found if user doesn't exist
       - ExceptionInfo with :type :validation-error for invalid data
       - ExceptionInfo with :type :business-rule-violation for rule violations
       
     Example:
       (update-user-profile service updated-user)")

  (deactivate-user [this user-id]
    "Deactivate user account with business rule validation.
     
     Business operation that soft-deletes a user after checking deletion policies.
     
     Args:
       user-id: UUID of user to deactivate
     
     Returns:
       Boolean indicating success
       
     Throws:
       - ExceptionInfo with :type :user-not-found if user doesn't exist
       - ExceptionInfo with :type :deletion-not-allowed if deletion not permitted
       
     Example:
       (deactivate-user service user-id)")

  (permanently-delete-user [this user-id]
    "Permanently delete user (irreversible) with strict validation.
     
     Business operation for GDPR compliance and permanent user removal.
     
     Args:
       user-id: UUID of user to permanently delete
     
     Returns:
       Boolean indicating success
       
     Throws:
       - ExceptionInfo with :type :user-not-found if user doesn't exist
       - ExceptionInfo with :type :hard-deletion-not-allowed if not permitted
       
     Example:
       (permanently-delete-user service user-id)")

  ;; Session Management Operations
  (authenticate-user [this session-data]
    "Authenticate user and create session with token generation.
     
     Business operation that creates authenticated session after validation.
     
     Args:
       session-data: Map with session creation data
                    {:user-id uuid :user-agent string ...}
     
     Returns:
       Created session entity with generated token and expiry
       
     Throws:
       - ExceptionInfo with :type :validation-error for invalid data
       
     Example:
       (authenticate-user service {:user-id user-id
                                  :user-agent \"Mozilla/5.0...\"})")

  (validate-session [this session-token]
    "Validate and retrieve session by token.
     
     Business operation that checks session validity and updates access time.
     
     Args:
       session-token: String session token
     
     Returns:
       Valid session entity or nil if not found/expired
       
     Side effects:
       - Updates last-accessed-at if session is valid
       
     Example:
       (validate-session service \"session-token-123\")")

  (logout-user [this session-token]
    "Log out user by invalidating session.
     
     Business operation that terminates a user session.
     
     Args:
       session-token: String session token to invalidate
     
     Returns:
       Boolean indicating success
       
     Example:
       (logout-user service \"session-token-123\")")

  (logout-user-everywhere [this user-id]
    "Log out user from all sessions (force logout everywhere).
     
     Business operation for security incidents, password changes, etc.
     
     Args:
       user-id: UUID of user whose sessions to invalidate
     
     Returns:
       Integer count of invalidated sessions
       
     Example:
       (logout-user-everywhere service user-id)"))

;; =============================================================================
;; Communication Ports
;; =============================================================================

(defprotocol IEmailService
  "Email communication service for user notifications and system messages.
   
   This port abstracts email sending capabilities, supporting:
   - Transactional emails (welcome, password reset, notifications)
   - Template-based email generation
   - Multi-format email support (HTML, plain text)
   - Delivery tracking and error handling
   
   Implementations may use SMTP, cloud services (SendGrid, SES), or
   console logging for development environments."

  (send-email [this recipient subject body options]
    "Send email to recipient with specified content.
     
     Args:
       recipient: String email address or map with :email and :name
       subject: String email subject line
       body: String email body (may contain HTML)
       options: Optional map with:
                {:format :html|:text|:both
                 :from-email \"noreply@example.com\"
                 :from-name \"Boundary System\"
                 :reply-to \"support@example.com\"
                 :attachments [{:filename \"doc.pdf\" :content byte-array}]
                 :template-id \"welcome-email\"
                 :template-data {...}}
     
     Returns:
       Map with :success boolean and optional :message-id or :error
     
     Example:
       (send-email service \"user@example.com\" \"Welcome!\" 
                  \"<h1>Welcome to our service!</h1>\" 
                  {:format :html})")

  (send-welcome-email [this user]
    "Send welcome email to newly registered user.
     
     Args:
       user: User entity map with :email, :name, etc.
     
     Returns:
       Map with :success boolean and delivery information
       Uses predefined welcome email template
       May include getting started links, account activation, etc.
     
     Example:
       (send-welcome-email service new-user)")

  (send-password-reset-email [this user reset-token]
    "Send password reset email with secure token.
     
     Args:
       user: User entity map
       reset-token: Secure reset token string
     
     Returns:
       Map with :success boolean and delivery information
       Uses password reset email template with secure reset link
       Token should expire after reasonable time (e.g., 1 hour)
     
     Example:
       (send-password-reset-email service user \"secure-token-123\")")

  (send-account-notification [this user notification-type data]
    "Send account-related notification to user.
     
     Args:
       user: User entity map
       notification-type: Keyword notification type
                         (:profile-updated, :security-alert, :billing-update)
       data: Map with notification-specific data
     
     Returns:
       Map with :success boolean and delivery information
       Uses appropriate template based on notification type
     
     Example:
       (send-account-notification service user :security-alert 
                                 {:ip-address \"192.168.1.1\"
                                  :timestamp (time/instant)})"))

(defprotocol INotificationService
  "Multi-channel notification service for user communications.
   
   This port extends beyond email to support multiple notification channels:
   - Push notifications for mobile/web apps
   - SMS notifications for critical alerts
   - In-app notifications for user dashboard
   - Webhook notifications for integrations
   
   Supports user preference management and notification batching."

  (send-notification [this user-id notification]
    "Send notification through user's preferred channels.
     
     Args:
       user-id: UUID of target user
       notification: Notification map with:
                    {:type :welcome|:alert|:reminder|:update
                     :title \"Notification Title\"
                     :message \"Notification content...\"
                     :priority :low|:normal|:high|:urgent
                     :channels [:email :push :sms]  ; preferred channels
                     :data {...}  ; additional context data}
     
     Returns:
       Map with delivery results per channel:
       {:email {:success true :message-id \"123\"}
        :push {:success false :error \"Device not registered\"}
        :sms {:success true :message-id \"456\"}}
     
     Example:
       (send-notification service user-id 
                         {:type :alert
                          :title \"Security Alert\"
                          :message \"New login detected\"
                          :priority :high
                          :channels [:email :push]})")

  (send-bulk-notification [this user-ids notification]
    "Send notification to multiple users efficiently.
     
     Args:
       user-ids: Vector of user UUIDs
       notification: Notification map (same format as send-notification)
     
     Returns:
       Map with per-user delivery results:
       {user-id-1 {:email {:success true} :push {:success true}}
        user-id-2 {:email {:success false :error \"Invalid email\"}}}
     
     Example:
       (send-bulk-notification service [user-id-1 user-id-2] 
                              {:type :update
                               :title \"System Maintenance\"
                               :message \"Scheduled downtime tonight\"})")

  (get-notification-preferences [this user-id]
    "Retrieve user's notification preferences.
     
     Args:
       user-id: UUID of user
     
     Returns:
       Map with user's notification preferences:
       {:email {:enabled true :types [:welcome :alert :update]}
        :push {:enabled false}
        :sms {:enabled true :types [:alert] :phone-number \"+1234567890\"}}
     
     Example:
       (get-notification-preferences service user-id)")

  (update-notification-preferences [this user-id preferences]
    "Update user's notification preferences.
     
     Args:
       user-id: UUID of user
       preferences: Preferences map (same format as get-notification-preferences)
     
     Returns:
       Updated preferences map
       Should validate phone numbers, email addresses, etc.
     
     Example:
       (update-notification-preferences service user-id 
                                       {:email {:enabled false}
                                        :push {:enabled true}})"))

;; =============================================================================
;; System Service Ports
;; =============================================================================

(defprotocol IEventBus
  "Event publishing and subscription service for inter-module communication.
   
   This port enables loosely-coupled communication between modules through
   domain events. Supports both synchronous and asynchronous event processing,
   event persistence for replay, and module-level event subscriptions.
   
   Events follow a structured format with type, source, and payload data."

  (publish-event [this event]
    "Publish domain event to all interested subscribers.
     
     Args:
       event: Event map with:
              {:type :user-created|:user-updated|:user-deleted|:session-created
               :source :user-module
               :user-id uuid  ; if applicable
               :timestamp instant
               :correlation-id string  ; for request tracing
               :payload {...}  ; event-specific data}
     
     Returns:
       Map with :success boolean and :event-id for tracking
       Event will be delivered to all registered subscribers
       May be processed synchronously or asynchronously
     
     Example:
       (publish-event service {:type :user-created
                              :source :user-module
                              :user-id user-id
                              :timestamp (time/instant)
                              :payload {:email \"user@example.com\"
                                       :role :user}})")

  (subscribe-to-events [this subscriber-id event-types handler-fn]
    "Subscribe to specific event types with handler function.
     
     Args:
       subscriber-id: Unique identifier for subscriber (e.g., :billing-module)
       event-types: Vector of event type keywords to subscribe to
       handler-fn: Function that receives event map and returns result
     
     Returns:
       Subscription identifier for later unsubscription
       Handler function should be idempotent and handle errors gracefully
     
     Example:
       (subscribe-to-events service :billing-module [:user-created :user-updated]
                           (fn [event] 
                             (billing/sync-customer-data event)))")

  (unsubscribe-from-events [this subscription-id]
    "Remove event subscription.
     
     Args:
       subscription-id: ID returned from subscribe-to-events
     
     Returns:
       Boolean indicating success
       Handler will no longer receive events after unsubscription
     
     Example:
       (unsubscribe-from-events service subscription-id)")

  (get-event-history [this filters]
    "Retrieve historical events for replay or analysis.
     
     Args:
       filters: Map with optional filters:
                {:event-types [:user-created :user-updated]
                 :user-id uuid
                 :since-timestamp instant
                 :until-timestamp instant
                 :limit 100
                 :offset 0}
     
     Returns:
       Vector of event maps matching filters
       Useful for debugging, auditing, and event replay scenarios
     
     Example:
       (get-event-history service {:event-types [:user-created]
                                  :since-timestamp yesterday})"))

(defprotocol IAuditService
  "Audit logging service for security and compliance tracking.
   
   This port provides structured audit logging for security-relevant events,
   user actions, and system changes. Supports compliance requirements (GDPR,
   HIPAA, SOX) and security incident investigation.
   
   Audit logs are immutable and include comprehensive context information."

  (log-user-action [this action-data]
    "Log user-initiated action for audit trail.
     
     Args:
       action-data: Map with action details:
                   {:action :create-user|:update-user|:delete-user|:login|:logout
                    :user-id uuid  ; user performing action
                    :target-user-id uuid  ; user being acted upon (if different)
                    :timestamp instant
                    :ip-address string
                    :user-agent string
                    :correlation-id string
                    :changes {...}  ; what was changed (before/after)
                    :result :success|:failure
                    :error-message string  ; if failure}
     
     Returns:
       Map with :success boolean and :audit-id
       Creates immutable audit log entry
     
     Example:
       (log-user-action service {:action :update-user
                                :user-id current-user-id
                                :target-user-id target-user-id
                                :changes {:role {:from :user :to :admin}}
                                :result :success})")

  (log-system-event [this event-data]
    "Log system-level event for operational audit.
     
     Args:
       event-data: Map with system event details:
                  {:event :startup|:shutdown|:configuration-change|:migration
                   :component :user-module|:billing-module|:system
                   :timestamp instant
                   :details {...}  ; event-specific information
                   :correlation-id string}
     
     Returns:
       Map with :success boolean and :audit-id
     
     Example:
       (log-system-event service {:event :configuration-change
                                 :component :user-module
                                 :details {:feature-flags {:old {...} :new {...}}}})")

  (get-audit-trail [this filters]
    "Retrieve audit trail for investigation or compliance.
     
     Args:
       filters: Map with optional filters:
                {:user-id uuid
                 :target-user-id uuid
                 :actions [:create-user :update-user]
                 :since-timestamp instant
                 :until-timestamp instant
                 :result :success|:failure
                 :limit 100
                 :offset 0}
     
     Returns:
       Vector of audit log entries matching filters
       Entries are immutable and chronologically ordered
     
     Example:
       (get-audit-trail service {:user-id user-id
                                :actions [:update-user :delete-user]
                                :since-timestamp (time/minus (time/instant) (time/days 30))})"))

(defprotocol ITimeService
  "Time and scheduling service for consistent temporal operations.
   
   This port provides time-related operations for business logic that needs
   to be deterministic and testable. All temporal logic should use this
   service rather than direct system time calls.
   
   Enables time mocking for tests and consistent timezone handling."

  (current-instant [this]
    "Get current timestamp as Instant.
     
     Returns:
       org.joda.time.DateTime representing current moment
       Should be used instead of (time/now) for business operations
       Enables time mocking in tests
     
     Example:
       (current-instant service)")

  (current-date [this timezone]
    "Get current date in specified timezone.
     
     Args:
       timezone: String timezone ID (e.g., \"America/New_York\", \"UTC\")
     
     Returns:
       org.joda.time.LocalDate for the current date in timezone
       Useful for date-based business rules
     
     Example:
       (current-date service \"UTC\")")

  (format-timestamp [this instant format-string timezone]
    "Format timestamp for display in user's timezone.
     
     Args:
       instant: org.joda.time.DateTime to format
       format-string: String format pattern (e.g., \"yyyy-MM-dd HH:mm:ss\")
       timezone: String timezone ID for display
     
     Returns:
       Formatted timestamp string in specified timezone
       Supports internationalization and user preferences
     
     Example:
       (format-timestamp service (current-instant service) 
                        \"MMM dd, yyyy 'at' HH:mm\" \"America/New_York\")")

  (parse-timestamp [this timestamp-string format-string timezone]
    "Parse timestamp string to Instant.
     
     Args:
       timestamp-string: String timestamp to parse
       format-string: String format pattern used in timestamp-string
       timezone: String timezone ID for parsing context
     
     Returns:
       org.joda.time.DateTime parsed from string
       Throws exception if parsing fails
     
     Example:
       (parse-timestamp service \"2023-12-25 14:30:00\" 
                       \"yyyy-MM-dd HH:mm:ss\" \"UTC\")")

  (add-duration [this instant duration]
    "Add duration to instant.
     
     Args:
       instant: org.joda.time.DateTime base time
       duration: org.joda.time.Duration to add (can be negative for subtraction)
     
     Returns:
       org.joda.time.DateTime result of addition
       Useful for session expiration, scheduling, etc.
     
     Example:
       (add-duration service (current-instant service) (time/hours 24))")

  (is-business-hours? [this instant timezone]
    "Check if instant falls within business hours.
     
     Args:
       instant: org.joda.time.DateTime to check
       timezone: String timezone ID for business hours evaluation
     
     Returns:
       Boolean indicating if time is during business hours.
       Business hours typically 9 AM - 5 PM, Monday-Friday.
       Should be configurable per organization.
     
     Example:
       (is-business-hours? service (current-instant service) \"America/New_York\")"))

;; =============================================================================
;; Utility Functions for Port Usage
;; =============================================================================

(defn validate-user-input
  "Validate user input using schema before passing to ports.

       Args:
         schema: Malli schema to validate against
         data: User input data to validate

       Returns:
         {:valid? true :data transformed-data} or
         {:valid? false :errors validation-errors}

       Example:
         (validate-user-input schema/CreateUserRequest request-data)"
  [sch data]
  (let [transformed-data (m/decode sch data schema/user-request-transformer)]
    (if (m/validate sch transformed-data)
      {:valid? true :data transformed-data}
      {:valid? false :errors (m/explain sch transformed-data)})))

(defn create-correlation-id
  "Generate correlation ID for request tracing.

       Returns:
         String correlation ID for linking related operations

       Example:
         (create-correlation-id)"
  []
  (.toString (UUID/randomUUID)))

(defn enrich-user-context
  "Add system context to user data for port operations.

       Args:
         user-data: User entity or input data
         context: Map with :correlation-id, :timestamp, etc.

       Returns:
         User data enriched with context information

       Example:
         (enrich-user-context user-input
                             {:correlation-id (create-correlation-id)})"
  [user-data context]
  (merge user-data
         (select-keys context [:correlation-id :timestamp])
         {:updated-at (:timestamp context)}))
