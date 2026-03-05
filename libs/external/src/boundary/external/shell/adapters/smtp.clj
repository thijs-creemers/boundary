(ns boundary.external.shell.adapters.smtp
  "SMTP transport adapter implementing ISmtpProvider.

   Uses javax.mail for SMTP communication. This adapter is positioned as a
   transport-level provider — independent of the email lib's higher-level
   abstraction (templating, job queuing, etc.).

   Usage:
     (def provider (create-smtp-provider
                     {:host \"smtp.gmail.com\"
                      :port 587
                      :username \"user@gmail.com\"
                      :password \"app-password\"
                      :tls? true
                      :from \"no-reply@example.com\"}))
     (send-email! provider {:to \"recipient@example.com\"
                            :subject \"Hello\"
                            :body \"World\"})"
  (:require [boundary.external.core.smtp :as smtp-core]
            [boundary.external.ports :as ports]
            [clojure.tools.logging :as log])
  (:import [javax.mail Session Transport MessagingException Authenticator PasswordAuthentication]
           [javax.mail Message$RecipientType]
           [javax.mail.internet InternetAddress MimeMessage MimeBodyPart MimeMultipart]))

;; =============================================================================
;; Session Building
;; =============================================================================

(defn- create-authenticator [username password]
  (proxy [Authenticator] []
    (getPasswordAuthentication []
      (PasswordAuthentication. username password))))

(defn- create-session
  [{:keys [username password] :as config}]
  (let [props (smtp-core/build-mime-properties config)]
    (if (and username password)
      (Session/getInstance props (create-authenticator username password))
      (Session/getInstance props))))

;; =============================================================================
;; MimeMessage Builder
;; =============================================================================

(defn- build-mime-message
  [session email from-addr]
  (let [recipients (smtp-core/normalize-recipients (:to email))
        msg        (MimeMessage. session)]
    (.setFrom msg (InternetAddress. (str (or (:from email) from-addr))))
    (doseq [r recipients]
      (.addRecipient msg Message$RecipientType/TO (InternetAddress. r)))
    (.setSubject msg (str (:subject email)))
    (if (:html-body email)
      (let [multipart  (MimeMultipart. "alternative")
            text-part  (doto (MimeBodyPart.)
                         (.setContent (str (or (:body email) "")) "text/plain; charset=UTF-8"))
            html-part  (doto (MimeBodyPart.)
                         (.setContent (str (:html-body email)) "text/html; charset=UTF-8"))]
        (.addBodyPart multipart text-part)
        (.addBodyPart multipart html-part)
        (.setContent msg multipart))
      (.setText msg (str (:body email)) "UTF-8"))
    (when-let [reply-to (:reply-to email)]
      (.setReplyTo msg (into-array InternetAddress [(InternetAddress. reply-to)])))
    msg))

;; =============================================================================
;; Adapter Record
;; =============================================================================

(defrecord SmtpProviderAdapter [host port username password tls? ssl? from])

(extend-protocol ports/ISmtpProvider
  SmtpProviderAdapter

  (send-email! [this email]
    (log/info "Sending email via SMTP" {:to (:to email) :host (:host this)})
    (try
      (let [session (create-session this)
            msg     (build-mime-message session email (:from this))]
        (Transport/send msg)
        (let [message-id (try (.getMessageID msg) (catch Exception _ nil))]
          (log/info "Email sent successfully" {:message-id message-id})
          {:success? true :message-id message-id}))
      (catch MessagingException e
        (log/error e "SMTP send failed" {:host (:host this) :error (.getMessage e)})
        {:success? false
         :error    {:message (.getMessage e)
                    :type    "SmtpError"}})
      (catch Exception e
        (log/error e "Unexpected error during SMTP send")
        {:success? false
         :error    {:message (.getMessage e)
                    :type    "UnexpectedError"}})))

  (send-email-async! [this email]
    (future (ports/send-email! this email)))

  (test-connection! [this]
    (log/info "Testing SMTP connection" {:host (:host this) :port (:port this)})
    (try
      (let [session   (create-session this)
            transport (.getTransport session "smtp")]
        (if (and (:username this) (:password this))
          (.connect transport (:host this) (:username this) (:password this))
          (.connect transport))
        (.close transport)
        (log/info "SMTP connection test successful" {:host (:host this)})
        {:success? true})
      (catch MessagingException e
        (log/warn e "SMTP connection test failed" {:host (:host this)})
        {:success? false
         :error    {:message (.getMessage e)
                    :type    "SmtpConnectionError"}})
      (catch Exception e
        (log/warn e "SMTP connection test unexpected error")
        {:success? false
         :error    {:message (.getMessage e)
                    :type    "UnexpectedError"}}))))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-smtp-provider
  "Create an SMTP provider adapter.

  Config keys:
    :host     - SMTP server hostname (required)
    :port     - SMTP server port (required)
    :username - SMTP auth username (optional)
    :password - SMTP auth password (optional)
    :tls?     - Enable STARTTLS (default false)
    :ssl?     - Enable SSL (default false)
    :from     - Default From address (required)

  Returns:
    SmtpProviderAdapter implementing ISmtpProvider"
  [{:keys [host port username password tls? ssl? from]
    :or   {tls? false ssl? false}}]
  {:pre [(string? host) (some? port) (string? from)]}
  (log/info "Creating SMTP provider adapter" {:host host :port port :tls? tls? :ssl? ssl?})
  (->SmtpProviderAdapter host port username password tls? ssl? from))
