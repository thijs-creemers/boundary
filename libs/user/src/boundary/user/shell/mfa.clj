(ns boundary.user.shell.mfa
  "Shell Layer - MFA operations with side effects.
   
   This is the SHELL layer for MFA operations:
   - TOTP secret generation
   - TOTP code verification
   - Backup code generation (secure random)
   - QR code generation for authenticator apps
   
   All I/O and side effects happen here. Pure business logic
   is delegated to boundary.user.core.mfa."
  (:require [boundary.user.core.mfa :as mfa-core]
            [one-time.core :as otp]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import (java.security SecureRandom)
           (java.net URLEncoder)
           (java.time Instant)
           (java.util Base64)
           (java.io ByteArrayOutputStream)
           (org.apache.commons.codec.binary Base32)
           (com.google.zxing BarcodeFormat)
           (com.google.zxing.qrcode QRCodeWriter)
           (com.google.zxing.client.j2se MatrixToImageWriter)))

;; =============================================================================
;; TOTP Operations (Side Effects)
;; =============================================================================

(defn generate-totp-secret
  "Shell function: Generate a new TOTP secret.
   
   Returns:
     Base32-encoded secret string for TOTP
     
   Side effects: Secure random generation"
  []
  (let [random (SecureRandom.)
        bytes (byte-array 20) ; 160 bits = 20 bytes
        base32 (Base32.)]
    (.nextBytes random bytes)
    (.encodeAsString base32 bytes)))

(defn verify-totp-code
  "Shell function: Verify TOTP code against secret.
   
   Args:
     code: 6-digit TOTP code (string)
     secret: Base32-encoded TOTP secret
     
   Returns:
     Boolean - true if code is valid
     
   Side effects: Time-dependent verification"
  [code secret]
  (try
    (if (and code secret)
      (let [code-int (Integer/parseInt (str/trim code))]
        (boolean (otp/is-valid-totp-token? code-int secret)))
      false) ; Explicit false instead of nil when inputs invalid
    (catch Exception _e
      false)))

(defn generate-totp-uri
  "Shell function: Generate TOTP URI for QR code.
   
   Args:
     secret: Base32-encoded TOTP secret
     account-name: User identifier (usually email)
     issuer: Application name
     
   Returns:
     TOTP URI string for QR code generation
     
   Side effects: None (pure string generation)"
  [secret account-name issuer]
  (str "otpauth://totp/"
       (URLEncoder/encode issuer "UTF-8")
       ":"
       (URLEncoder/encode account-name "UTF-8")
       "?secret=" secret
       "&issuer=" (URLEncoder/encode issuer "UTF-8")
       "&algorithm=SHA1"
       "&digits=6"
       "&period=30"))

(defn generate-qr-code-data-url
  "Shell function: Generate QR code as a base64-encoded PNG data URL.

   Args:
     totp-uri: TOTP URI string

   Returns:
     Data URL string (data:image/png;base64,...) for use in <img> src attribute.
     Generated locally using ZXing — no external service required."
  [totp-uri]
  (let [writer (QRCodeWriter.)
        bit-matrix (.encode writer totp-uri BarcodeFormat/QR_CODE 200 200)
        baos (ByteArrayOutputStream.)]
    (MatrixToImageWriter/writeToStream bit-matrix "PNG" baos)
    (str "data:image/png;base64,"
         (.encodeToString (Base64/getEncoder) (.toByteArray baos)))))

;; =============================================================================
;; Backup Code Operations (Side Effects)
;; =============================================================================

(defn generate-backup-code
  "Shell function: Generate a single secure backup code.
   
  Returns:
     12-character alphanumeric backup code
     
   Side effects: Secure random generation"
  []
  (let [random (SecureRandom.)
        alphabet "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        alphabet-length (count alphabet)]
    (apply str
           (repeatedly 12
                       (fn []
                         (.charAt alphabet (.nextInt random alphabet-length)))))))

(defn generate-backup-codes
  "Shell function: Generate multiple backup codes.
   
   Args:
     count: Number of backup codes to generate (default 10)
     
   Returns:
     Vector of formatted backup codes
     
   Side effects: Secure random generation"
  ([] (generate-backup-codes 10))
  ([count]
   (vec (repeatedly count #(mfa-core/format-backup-code (generate-backup-code))))))

;; =============================================================================
;; MFA Service Coordination
;; =============================================================================

(defrecord MFAService [user-repository config])

(defn create-mfa-service
  "Factory function: Create MFA service instance.
   
   Args:
     user-repository: IUserRepository implementation
     config: MFA configuration map
     
   Returns:
     MFAService instance"
  [user-repository config]
  (->MFAService user-repository config))

(defn setup-mfa
  "Initiate MFA setup for a user.
   
   Args:
     mfa-service: MFAService instance
     user-id: UUID of user setting up MFA
     
   Returns:
     {:success? boolean
      :secret string (if successful)
      :qr-code-url string (if successful)
      :backup-codes vector (if successful)
      :error string (if failed)}"
  [mfa-service user-id]
  (let [{:keys [user-repository config]} mfa-service]
    (try
      (let [user (.find-user-by-id user-repository user-id)
            can-enable (mfa-core/can-enable-mfa? user)]
        (if (:can-enable? can-enable)
          ;; Generate MFA setup data
          (let [secret (generate-totp-secret)
                backup-codes (generate-backup-codes 10)
                issuer (get config :issuer "Boundary Framework")
                account-name (:email user)
                totp-uri (generate-totp-uri secret account-name issuer)
                qr-code-url (generate-qr-code-data-url totp-uri)]
            {:success? true
             :secret secret
             :qr-code-url qr-code-url
             :backup-codes backup-codes
             :issuer issuer
             :account-name account-name})
          ;; Cannot enable MFA
          {:success? false
           :error (:reason can-enable)}))
      (catch Exception e
        {:success? false
         :error (str "Failed to set up MFA: " (.getMessage e))}))))

(defn enable-mfa
  "Enable MFA for a user after verifying setup.
   
   Args:
     mfa-service: MFAService instance
     user-id: UUID of user enabling MFA
     secret: TOTP secret from setup
     backup-codes: Backup codes from setup
     verification-code: 6-digit code from authenticator app
     
   Returns:
     {:success? boolean
      :error string (if failed)}"
  [mfa-service user-id secret backup-codes verification-code]
  (let [{:keys [user-repository]} mfa-service]
    (try
      (let [user (.find-user-by-id user-repository user-id)
            can-enable (mfa-core/can-enable-mfa? user)]
        (if (:can-enable? can-enable)
          ;; Verify the code before enabling
          (if (verify-totp-code verification-code secret)
            ;; Enable MFA
            (let [current-time (Instant/now)
                  updates (mfa-core/prepare-mfa-enablement
                           user secret backup-codes current-time)
                  updated-user (merge user updates)]
              (log/info "Updating user with MFA"
                        {:user-id (:id user)
                         :mfa-enabled (:mfa-enabled updated-user)
                         :has-secret (boolean (:mfa-secret updated-user))
                         :backup-codes-count (count (:mfa-backup-codes updated-user))})
              (.update-user user-repository updated-user)
              {:success? true})
            ;; Invalid verification code
            {:success? false
             :error "Invalid verification code"})
          ;; Cannot enable MFA
          {:success? false
           :error (:reason can-enable)}))
      (catch Exception e
        {:success? false
         :error (str "Failed to enable MFA: " (.getMessage e))}))))

(defn disable-mfa
  "Disable MFA for a user.
   
   Args:
     mfa-service: MFAService instance
     user-id: UUID of user disabling MFA
     
   Returns:
     {:success? boolean
      :error string (if failed)}"
  [mfa-service user-id]
  (let [{:keys [user-repository]} mfa-service]
    (try
      (let [user (.find-user-by-id user-repository user-id)
            can-disable (mfa-core/can-disable-mfa? user)]
        (if (:can-disable? can-disable)
          ;; Disable MFA
          (let [updates (mfa-core/prepare-mfa-disablement user)
                updated-user (merge user updates)]
            (.update-user user-repository updated-user)
            {:success? true})
          ;; Cannot disable MFA
          {:success? false
           :error (:reason can-disable)}))
      (catch Exception e
        {:success? false
         :error (str "Failed to disable MFA: " (.getMessage e))}))))

(defn verify-mfa-code
  "Verify MFA code for authentication.
   
   Args:
     mfa-service: MFAService instance
     user: User entity
     code: 6-digit TOTP code or backup code
     
   Returns:
     {:valid? boolean
      :used-backup-code? boolean
      :updates map (if backup code used)}"
  [_mfa-service user code]
  (cond
      ;; Try TOTP verification first
    (verify-totp-code code (:mfa-secret user))
    {:valid? true
     :used-backup-code? false}

      ;; Try backup code
    (mfa-core/is-valid-backup-code? code user)
    (let [updates (mfa-core/mark-backup-code-used user code)]
      {:valid? true
       :used-backup-code? true
       :updates updates})

      ;; Invalid code
    :else
    {:valid? false
     :used-backup-code? false}))

(defn get-mfa-status
  "Get MFA status for a user.
   
   Args:
     mfa-service: MFAService instance
     user-id: UUID of user
     
   Returns:
     MFA status map"
  [mfa-service user-id]
  (let [{:keys [user-repository]} mfa-service]
    (try
      (let [user (.find-user-by-id user-repository user-id)]
        (if user
          (mfa-core/get-mfa-status user)
          {:enabled false
           :backup-codes-remaining 0}))
      (catch Exception e
        {:enabled false
         :backup-codes-remaining 0
         :error (.getMessage e)}))))
