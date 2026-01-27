(ns boundary.email.shell.adapters.smtp
  "SMTP email adapter using javax.mail.

   This adapter provides synchronous email sending via SMTP using the standard
   javax.mail library. It supports:
   - Basic SMTP authentication
   - TLS/STARTTLS encryption
   - SSL encryption
   - Plain text email bodies
   - Multiple recipients

   NOT supported (deferred to v2):
   - HTML email templates
   - Attachment handling
   - Connection pooling
   - Batch sending

   Usage:
     (def sender (create-smtp-sender
                   {:host \"smtp.gmail.com\"
                    :port 587
                    :username \"user@gmail.com\"
                    :password \"app-password\"
                    :tls? true}))

     (send-email! sender prepared-email)"
  (:require [boundary.email.ports :as ports]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [javax.mail Session Transport MessagingException Authenticator PasswordAuthentication]
           [javax.mail Message$RecipientType]
           [javax.mail.internet InternetAddress MimeMessage]
           [java.util Properties]))

;; =============================================================================
;; SMTP Session Configuration
;; =============================================================================

(defn- create-authenticator
  "Create javax.mail Authenticator for SMTP authentication.

   Args:
     username - SMTP username
     password - SMTP password

   Returns:
     Authenticator instance"
  [username password]
  (proxy [Authenticator] []
    (getPasswordAuthentication []
      (PasswordAuthentication. username password))))

(defn- create-smtp-session
  "Create javax.mail Session with SMTP configuration.

   Args:
     config - SmtpEmailSender instance with:
              :host - SMTP server host
              :port - SMTP server port
              :username - SMTP auth username (optional)
              :password - SMTP auth password (optional)
              :tls? - Enable STARTTLS
              :ssl? - Enable SSL

   Returns:
     javax.mail Session instance"
  [{:keys [host port username password tls? ssl?]}]
  (let [props (Properties.)]
    ;; Basic SMTP configuration
    (.put props "mail.smtp.host" host)
    (.put props "mail.smtp.port" (str port))

    ;; TLS/SSL configuration
    (when tls?
      (.put props "mail.smtp.starttls.enable" "true")
      (.put props "mail.smtp.starttls.required" "true"))

    (when ssl?
      (.put props "mail.smtp.ssl.enable" "true")
      (.put props "mail.smtp.socketFactory.port" (str port))
      (.put props "mail.smtp.socketFactory.class" "javax.net.ssl.SSLSocketFactory"))

    ;; Authentication configuration
    (if (and username password)
      (do
        (.put props "mail.smtp.auth" "true")
        (Session/getInstance props (create-authenticator username password)))
      (Session/getInstance props))))

;; =============================================================================
;; Email Conversion
;; =============================================================================

(defn- email->mime-message
  "Convert email map to javax.mail MimeMessage.

   Args:
     session - javax.mail Session
     email - Email map with:
             :from - Sender email address
             :to - Vector of recipient email addresses
             :subject - Email subject
             :body - Email body (plain text)
             :headers - Optional headers map (e.g., {:reply-to \"...\"})

   Returns:
     MimeMessage instance"
  [session email]
  (let [message (MimeMessage. session)]
    ;; Set from address
    (.setFrom message (InternetAddress. (:from email)))

    ;; Set to addresses
    (doseq [to-addr (:to email)]
      (.addRecipient message Message$RecipientType/TO (InternetAddress. to-addr)))

    ;; Set subject
    (.setSubject message (:subject email))

    ;; Set body (plain text)
    (.setText message (:body email))

    ;; Set optional headers
    (when-let [headers (:headers email)]
      (when-let [reply-to (:reply-to headers)]
        (.setReplyTo message (into-array InternetAddress [(InternetAddress. reply-to)])))

      (when-let [cc (:cc headers)]
        (doseq [cc-addr (if (string? cc)
                          (str/split cc #",\s*")
                          cc)]
          (.addRecipient message Message$RecipientType/CC (InternetAddress. cc-addr))))

      (when-let [bcc (:bcc headers)]
        (doseq [bcc-addr (if (string? bcc)
                           (str/split bcc #",\s*")
                           bcc)]
          (.addRecipient message Message$RecipientType/BCC (InternetAddress. bcc-addr)))))

    message))

;; =============================================================================
;; SMTP Email Sender
;; =============================================================================

(defrecord SmtpEmailSender [host port username password tls? ssl?])

(extend-protocol ports/EmailSenderProtocol
  SmtpEmailSender

  (send-email! [this email]
    (log/info "Sending email via SMTP"
              {:email-id (:id email)
               :to (:to email)
               :subject (:subject email)
               :host (:host this)
               :port (:port this)})

    (try
      (let [session (create-smtp-session this)
            message (email->mime-message session email)]

        ;; Send message via SMTP
        (Transport/send message)

        ;; Get message ID (if available)
        (let [message-id (try
                           (.getMessageID message)
                           (catch Exception _
                             (str (:id email))))]  ; Fallback to email ID

          (log/info "Email sent successfully"
                    {:email-id (:id email)
                     :message-id message-id})

          {:success? true
           :message-id message-id}))

      (catch MessagingException e
        (log/error e "Failed to send email via SMTP"
                   {:email-id (:id email)
                    :to (:to email)
                    :host (:host this)
                    :error-message (.getMessage e)})

        {:success? false
         :error {:message (.getMessage e)
                 :type "SmtpError"
                 :provider-error {:class (.getName (.getClass e))
                                  :cause (when-let [c (.getCause e)]
                                           (.getMessage c))}}})

      (catch Exception e
        (log/error e "Unexpected error sending email"
                   {:email-id (:id email)
                    :to (:to email)})

        {:success? false
         :error {:message (.getMessage e)
                 :type "UnexpectedError"
                 :provider-error {:class (.getName (.getClass e))
                                  :cause (when-let [c (.getCause e)]
                                           (.getMessage c))}}})))

  (send-email-async! [this email]
    (log/debug "Enqueueing email for async send"
               {:email-id (:id email)
                :to (:to email)})

    (future
      (ports/send-email! this email))))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-smtp-sender
  "Create SMTP email sender.

   Config map:
     :host - SMTP server host (required, e.g., \"smtp.gmail.com\")
     :port - SMTP server port (required, e.g., 587 for TLS, 465 for SSL, 25 for plain)
     :username - SMTP auth username (optional, required for most providers)
     :password - SMTP auth password (optional, required for most providers)
     :tls? - Enable STARTTLS (default: true, recommended for port 587)
     :ssl? - Enable SSL (default: false, use for port 465)

   Common SMTP Configurations:

   Gmail:
     {:host \"smtp.gmail.com\"
      :port 587
      :username \"user@gmail.com\"
      :password \"app-specific-password\"  ; NOT your Gmail password!
      :tls? true}

   Amazon SES:
     {:host \"email-smtp.us-east-1.amazonaws.com\"
      :port 587
      :username \"SMTP-USERNAME\"
      :password \"SMTP-PASSWORD\"
      :tls? true}

   Mailgun:
     {:host \"smtp.mailgun.org\"
      :port 587
      :username \"postmaster@yourdomain.com\"
      :password \"mailgun-smtp-password\"
      :tls? true}

   SendGrid:
     {:host \"smtp.sendgrid.net\"
      :port 587
      :username \"apikey\"
      :password \"sendgrid-api-key\"
      :tls? true}

   Local Development (Mailhog/MailCatcher):
     {:host \"localhost\"
      :port 1025
      :tls? false
      :ssl? false}

   Returns:
     SmtpEmailSender instance implementing EmailSenderProtocol

   Throws:
     AssertionError if required fields are missing"
  [{:keys [host port username password tls? ssl?]
    :or {tls? true ssl? false}}]
  {:pre [(string? host)
         (some? port)]}

  (log/info "Creating SMTP email sender"
            {:host host
             :port port
             :tls? tls?
             :ssl? ssl?
             :auth? (boolean (and username password))})

  (->SmtpEmailSender host port username password tls? ssl?))
