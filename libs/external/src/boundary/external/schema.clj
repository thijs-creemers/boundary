(ns boundary.external.schema
  "Malli validation schemas for external service adapters."
  (:require [malli.core :as m]))

;; =============================================================================
;; SMTP Schemas
;; =============================================================================

(def SmtpConfig
  "SMTP transport configuration schema."
  [:map
   [:host [:string {:min 1}]]
   [:port [:int {:min 1 :max 65535}]]
   [:username {:optional true} [:string {:min 1}]]
   [:password {:optional true} [:string {:min 1}]]
   [:tls? {:optional true} :boolean]
   [:ssl? {:optional true} :boolean]
   [:from [:string {:min 3}]]])

(def OutboundEmail
  "Outbound email message schema."
  [:map
   [:to [:or [:string {:min 3}] [:vector [:string {:min 3}]]]]
   [:from {:optional true} [:string {:min 3}]]
   [:subject [:string {:min 1}]]
   [:body {:optional true} :string]
   [:html-body {:optional true} :string]
   [:reply-to {:optional true} [:string {:min 3}]]
   [:cc {:optional true} [:vector [:string {:min 3}]]]
   [:bcc {:optional true} [:vector [:string {:min 3}]]]])

(def EmailSendResult
  "Result of an email send operation."
  [:map
   [:success? :boolean]
   [:message-id {:optional true} [:string {:min 1}]]
   [:error {:optional true} [:map
                             [:message :string]
                             [:type :string]]]])

;; =============================================================================
;; IMAP Schemas
;; =============================================================================

(def ImapConfig
  "IMAP mailbox configuration schema."
  [:map
   [:host [:string {:min 1}]]
   [:port [:int {:min 1 :max 65535}]]
   [:username [:string {:min 1}]]
   [:password [:string {:min 1}]]
   [:ssl? {:optional true} :boolean]
   [:folder {:optional true} [:string {:min 1}]]])

(def InboundMessage
  "Inbound email message from IMAP."
  [:map
   [:uid :int]
   [:message-id {:optional true} :string]
   [:from :string]
   [:to [:vector :string]]
   [:subject :string]
   [:body {:optional true} :string]
   [:html-body {:optional true} :string]
   [:received-at inst?]
   [:headers {:optional true} [:map-of :keyword :string]]
   [:attachments {:optional true} [:vector :map]]])

(def ImapFetchOptions
  "Options for fetching messages from IMAP."
  [:map
   [:folder {:optional true} [:string {:min 1}]]
   [:limit {:optional true} [:int {:min 1}]]
   [:unread-only? {:optional true} :boolean]
   [:since {:optional true} inst?]])

;; =============================================================================
;; Stripe Schemas
;; =============================================================================

(def StripeConfig
  "Stripe API configuration schema."
  [:map
   [:api-key [:string {:min 1}]]
   [:webhook-secret {:optional true} [:string {:min 1}]]
   [:api-version {:optional true} [:string {:min 1}]]
   [:base-url {:optional true} [:string {:min 1}]]])

(def CreatePaymentIntentInput
  "Input for creating a Stripe payment intent."
  [:map
   [:amount [:int {:min 1}]]
   [:currency [:string {:min 3 :max 3}]]
   [:description {:optional true} :string]
   [:metadata {:optional true} [:map-of :keyword :any]]
   [:customer-id {:optional true} :string]])

(def PaymentIntent
  "Stripe payment intent schema."
  [:map
   [:id :string]
   [:status :string]
   [:amount :int]
   [:currency :string]
   [:client-secret {:optional true} :string]
   [:created-at inst?]
   [:metadata {:optional true} [:map-of :string :any]]])

(def StripeWebhookEvent
  "Stripe webhook event schema."
  [:map
   [:id :string]
   [:type :string]
   [:api-version {:optional true} :string]
   [:created-at inst?]
   [:data :map]])

;; =============================================================================
;; Twilio Schemas
;; =============================================================================

(def TwilioConfig
  "Twilio API configuration schema."
  [:map
   [:account-sid [:string {:min 1}]]
   [:auth-token [:string {:min 1}]]
   [:from-number [:string {:min 1}]]
   [:base-url {:optional true} [:string {:min 1}]]])

(def SendMessageInput
  "Input for sending a Twilio SMS or WhatsApp message."
  [:map
   [:to [:string {:min 1}]]
   [:body [:string {:min 1}]]
   [:from {:optional true} [:string {:min 1}]]
   [:media-url {:optional true} [:string {:min 1}]]])

(def MessageResult
  "Result of a Twilio message send operation."
  [:map
   [:success? :boolean]
   [:message-sid {:optional true} :string]
   [:status {:optional true} :string]
   [:error {:optional true} [:map
                             [:message :string]
                             [:type :string]]]])

;; =============================================================================
;; Validation Functions
;; =============================================================================

(defn valid-smtp-config?
  "Validate SMTP configuration against schema."
  [config]
  (m/validate SmtpConfig config))

(defn valid-imap-config?
  "Validate IMAP configuration against schema."
  [config]
  (m/validate ImapConfig config))

(defn valid-stripe-config?
  "Validate Stripe configuration against schema."
  [config]
  (m/validate StripeConfig config))

(defn valid-twilio-config?
  "Validate Twilio configuration against schema."
  [config]
  (m/validate TwilioConfig config))
