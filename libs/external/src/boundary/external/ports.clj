(ns boundary.external.ports
  "Protocol definitions for external service adapters.

   Defines four integration protocols:
   - ISmtpProvider      — outbound email via SMTP
   - IImapMailbox       — inbound email via IMAP
   - IStripePayments    — Stripe payment intent operations
   - IStripeWebhooks    — Stripe webhook signature verification
   - ITwilioMessaging   — Twilio SMS and WhatsApp messaging")

;; =============================================================================
;; SMTP Protocol
;; =============================================================================

(defprotocol ISmtpProvider
  "Protocol for outbound SMTP email sending."

  (send-email! [this email]
    "Send an email synchronously.

    Args:
      email - OutboundEmail map with :to :from :subject :body etc.

    Returns:
      Map with:
        :success?    - boolean
        :message-id  - string (if successful)
        :error       - map {:message :type} (if failed)")

  (send-email-async! [this email]
    "Send an email asynchronously. Returns a future of the send-email! result.")

  (test-connection! [this]
    "Verify SMTP server is reachable and credentials are valid.

    Returns:
      Map with:
        :success? - boolean
        :error    - map {:message :type} (if failed)"))

;; =============================================================================
;; IMAP Protocol
;; =============================================================================

(defprotocol IImapMailbox
  "Protocol for reading messages from an IMAP mailbox."

  (fetch-messages! [this] [this opts]
    "Fetch messages from the mailbox.

    Args:
      opts - ImapFetchOptions map (optional):
             :folder      - folder name (default INBOX)
             :limit       - max messages to fetch
             :unread-only? - fetch only unread messages
             :since       - only messages after this inst

    Returns:
      Map with:
        :success?  - boolean
        :messages  - vector of InboundMessage maps
        :count     - integer
        :error     - map {:message :type} (if failed)")

  (fetch-unread! [this] [this limit]
    "Fetch unread messages. Returns same shape as fetch-messages!.")

  (mark-read! [this uid]
    "Mark a message as read by UID.

    Returns:
      Map with :success? and optional :error")

  (delete-message! [this uid]
    "Delete a message by UID.

    Returns:
      Map with :success? and optional :error")

  (close! [this]
    "Close IMAP connection. Returns true."))

;; =============================================================================
;; Stripe Protocols
;; =============================================================================

(defprotocol IStripePayments
  "Protocol for Stripe payment intent operations."

  (create-payment-intent! [this input]
    "Create a Stripe payment intent.

    Args:
      input - CreatePaymentIntentInput map with :amount :currency etc.

    Returns:
      Map with:
        :success?      - boolean
        :data          - PaymentIntent map (if successful)
        :error         - map {:message :type :code} (if failed)")

  (retrieve-payment-intent! [this id]
    "Retrieve a payment intent by ID.

    Returns same shape as create-payment-intent!")

  (confirm-payment-intent! [this id]
    "Confirm a payment intent by ID.

    Returns same shape as create-payment-intent!")

  (cancel-payment-intent! [this id]
    "Cancel a payment intent by ID.

    Returns same shape as create-payment-intent!")

  (list-payment-intents! [this params]
    "List payment intents with optional filters.

    Args:
      params - map with optional :limit :starting-after :customer

    Returns:
      Map with:
        :success?   - boolean
        :data       - vector of PaymentIntent maps
        :has-more?  - boolean
        :error      - map {:message :type} (if failed)"))

(defprotocol IStripeWebhooks
  "Protocol for Stripe webhook event verification."

  (verify-webhook! [this payload signature]
    "Verify a Stripe webhook signature and parse the event.

    Args:
      payload   - raw request body string
      signature - value of Stripe-Signature header

    Returns:
      Map with:
        :valid?  - boolean
        :event   - StripeWebhookEvent map (if valid)
        :error   - string describing failure (if invalid)"))

;; =============================================================================
;; Twilio Protocol
;; =============================================================================

(defprotocol ITwilioMessaging
  "Protocol for sending SMS and WhatsApp messages via Twilio."

  (send-sms! [this input]
    "Send an SMS message.

    Args:
      input - SendMessageInput map with :to :body and optional :from

    Returns:
      MessageResult map with :success? :message-sid :status :error")

  (send-whatsapp! [this input]
    "Send a WhatsApp message. Automatically prefixes whatsapp: to To/From.

    Args:
      input - SendMessageInput map with :to :body and optional :from

    Returns:
      MessageResult map with :success? :message-sid :status :error")

  (get-message-status! [this sid]
    "Get the delivery status of a message by SID.

    Returns:
      Map with:
        :success? - boolean
        :status   - string (e.g. \"delivered\", \"failed\")
        :error    - map {:message :type} (if failed)"))
