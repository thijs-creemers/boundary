(ns boundary.external.shell.module-wiring
  "Integrant lifecycle wiring for the external service adapters.

   Registers init-key and halt-key! multimethods for:
     :boundary.external/smtp    — SMTP transport provider
     :boundary.external/imap    — IMAP mailbox reader
     :boundary.external/stripe  — Stripe payments + webhooks
     :boundary.external/twilio  — Twilio SMS / WhatsApp

   All four keys are opt-in: add them to :active in config.edn to enable.
   They are shipped in :inactive by default."
  (:require [boundary.external.ports :as ports]
            [boundary.external.shell.adapters.smtp :as smtp-adapter]
            [boundary.external.shell.adapters.imap :as imap-adapter]
            [boundary.external.shell.adapters.stripe :as stripe-adapter]
            [boundary.external.shell.adapters.twilio :as twilio-adapter]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

;; =============================================================================
;; SMTP
;; =============================================================================

(defmethod ig/init-key :boundary.external/smtp
  [_ config]
  (log/info "Initializing external SMTP provider" {:host (:host config)})
  (let [adapter (smtp-adapter/create-smtp-provider config)]
    (log/info "External SMTP provider initialized" {:host (:host config)})
    adapter))

(defmethod ig/halt-key! :boundary.external/smtp
  [_ _adapter]
  (log/info "External SMTP provider halted (no cleanup required)"))

;; =============================================================================
;; IMAP
;; =============================================================================

(defmethod ig/init-key :boundary.external/imap
  [_ config]
  (log/info "Initializing external IMAP mailbox" {:host (:host config)})
  (let [adapter (imap-adapter/create-imap-mailbox config)]
    (log/info "External IMAP mailbox initialized" {:host (:host config)})
    adapter))

(defmethod ig/halt-key! :boundary.external/imap
  [_ adapter]
  (log/info "Halting external IMAP mailbox")
  (try
    (ports/close! adapter)
    (catch Exception e
      (log/warn e "Error while closing IMAP mailbox"))))

;; =============================================================================
;; Stripe
;; =============================================================================

(defmethod ig/init-key :boundary.external/stripe
  [_ config]
  (log/info "Initializing Stripe adapter")
  (let [adapter (stripe-adapter/create-stripe-adapter config)]
    (log/info "Stripe adapter initialized")
    adapter))

(defmethod ig/halt-key! :boundary.external/stripe
  [_ _adapter]
  (log/info "Stripe adapter halted (no cleanup required)"))

;; =============================================================================
;; Twilio
;; =============================================================================

(defmethod ig/init-key :boundary.external/twilio
  [_ config]
  (log/info "Initializing Twilio adapter" {:account-sid (:account-sid config)})
  (let [adapter (twilio-adapter/create-twilio-adapter config)]
    (log/info "Twilio adapter initialized")
    adapter))

(defmethod ig/halt-key! :boundary.external/twilio
  [_ _adapter]
  (log/info "Twilio adapter halted (no cleanup required)"))
