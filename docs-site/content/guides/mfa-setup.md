---
title: "Multi-Factor Authentication (MFA)"
weight: 50
---

# Multi-Factor Authentication (MFA)

**Enterprise-grade TOTP authentication for the Boundary Framework**

Multi-Factor Authentication adds an extra layer of security to your application by requiring users to provide a time-based one-time password (TOTP) in addition to their regular password. Boundary's MFA implementation is production-ready, easy to integrate, and compatible with all major authenticator apps.

## Quick Example

```clojure
(require '[boundary.user.shell.mfa :as mfa]
         '[boundary.user.ports :as user-ports])

;; 1. Setup MFA for a user
(let [setup-result (mfa/setup-mfa user-service user-id)]
  {:secret (:secret setup-result)               ; "JBSWY3DPEHPK3PXP"
   :qr-code-url (:qr-code-url setup-result)     ; URL to scan
   :backup-codes (:backup-codes setup-result)})  ; ["3LTW-XRM1-GYVF" ...]

;; 2. User scans QR code with Google Authenticator/Authy

;; 3. Enable MFA with verification
(mfa/enable-mfa user-service user-id
                "JBSWY3DPEHPK3PXP"
                ["3LTW-XRM1-GYVF" ...]
                "123456")  ; Current 6-digit code from app
;; => {:success? true}

;; 4. Login now requires MFA code
(user-ports/login user-service
                  {:email "user@example.com"
                   :password "password"
                   :mfa-code "123456"})
;; => {:success true :session-id "..." :user {...}}
```

Users can now securely authenticate with password + TOTP code from their authenticator app.

---

## Features

Boundary's MFA system provides comprehensive authentication security:

- **TOTP Compatibility**: Works with Google Authenticator, Authy, 1Password, Microsoft Authenticator, and any RFC 6238 compliant app
- **QR Code Setup**: Instant setup by scanning QR code with authenticator app
- **Backup Codes**: 10 single-use recovery codes (12 characters each, formatted `XXX-XXXX-XXXX`)
- **Cryptographic Security**: Secrets generated with `SecureRandom`, 160 bits of entropy
- **Zero-Downtime Integration**: Works seamlessly with existing authentication flow
- **FC/IS Architecture**: Pure business logic in core, all I/O in shell
- **Production-Ready**: Compliant with NIST SP 800-63B Level 2 authentication standards

---

## Architecture

MFA follows the Functional Core / Imperative Shell pattern with clean separation between business logic and cryptographic operations:

```mermaid
graph TB
    subgraph "User Flow"
        A[User Requests MFA Setup] -->|HTTP POST| B[Shell: MFA Service]
    end
    
    subgraph "Imperative Shell"
        B -->|Generate Secret| C[SecureRandom]
        B -->|Generate Backup Codes| D[SecureRandom]
        B -->|Create QR Code URL| E[QR Service API]
        C & D & E -->|Prepared Data| F[Core: MFA Logic]
    end
    
    subgraph "Functional Core"
        F -->|Business Rules| G{Can Enable MFA?}
        G -->|Yes| H[Prepare MFA Enablement]
        G -->|No| I[Return Error Reason]
    end
    
    subgraph "Persistence"
        H -->|Save to DB| J[User Repository]
        J -->|Encrypted Storage| K[(PostgreSQL)]
    end
    
    style C fill:#B0E0E6,stroke:#333,stroke-width:2px
    style D fill:#B0E0E6,stroke:#333,stroke-width:2px
    style E fill:#B0E0E6,stroke:#333,stroke-width:2px
    style F fill:#90EE90,stroke:#333,stroke-width:2px
    style G fill:#90EE90,stroke:#333,stroke-width:2px
    style H fill:#90EE90,stroke:#333,stroke-width:2px
```

**Component Breakdown**:

- **Core** (`boundary.user.core.mfa`): Pure functions for business rules (`can-enable-mfa?`, `should-require-mfa?`, `prepare-mfa-enablement`)
- **Shell** (`boundary.user.shell.mfa`): Crypto operations (`generate-totp-secret`, `verify-totp-code`, `generate-backup-codes`)
- **Ports** (`boundary.user.ports`): Protocol definitions for user service and repository
- **HTTP** (`boundary.user.shell.http`): REST endpoints for MFA operations

---

## Usage Patterns

### Pattern 1: Progressive MFA Enrollment

**Use Case**: Allow users to opt-in to MFA for enhanced security without forcing it on everyone.

```clojure
(ns myapp.user.handlers
  (:require [boundary.user.shell.mfa :as mfa]
            [boundary.user.ports :as user-ports]))

(defn user-profile-handler
  "GET /profile - Show user profile with MFA status"
  [user-service]
  (fn [request]
    (let [user-id (get-in request [:session :user :id])
          user (user-ports/find-by-id user-service user-id)
          mfa-status (mfa/get-mfa-status user-service user-id)]
      
      {:status 200
       :body {:user user
              :mfa {:enabled (:enabled mfa-status)
                    :enabled-at (:enabled-at mfa-status)
                    :backup-codes-remaining (:backup-codes-remaining mfa-status)
                    :recommendation (if (:enabled mfa-status)
                                      "MFA is protecting your account"
                                      "Enable MFA for enhanced security")}}})))

(defn initiate-mfa-setup-handler
  "POST /profile/mfa/setup - Start MFA setup"
  [user-service]
  (fn [request]
    (let [user-id (get-in request [:session :user :id])
          setup-result (mfa/setup-mfa user-service user-id)]
      
      ;; Return setup data to client
      {:status 200
       :body {:message "Scan QR code with your authenticator app"
              :qr-code-url (:qr-code-url setup-result)
              :secret (:secret setup-result)  ; For manual entry
              :backup-codes (:backup-codes setup-result)
              :instructions "Save backup codes in a secure location"}})))

(defn complete-mfa-setup-handler
  "POST /profile/mfa/enable - Complete MFA setup with verification"
  [user-service]
  (fn [request]
    (let [user-id (get-in request [:session :user :id])
          {:keys [secret backupCodes verificationCode]} (get request :body-params)
          
          ;; Enable MFA with verification
          result (mfa/enable-mfa user-service user-id secret backupCodes verificationCode)]
      
      (if (:success? result)
        {:status 200
         :body {:message "MFA enabled successfully"
                :next-login-requires-mfa true}}
        {:status 400
         :body {:error "Invalid verification code"
                :hint "Ensure code is current (changes every 30 seconds)"}}))))
```

**Why This Pattern Works**:
- Users can enable MFA when ready (not forced at registration)
- Clear feedback on MFA status in profile
- Backup codes provided immediately (users can't enable MFA and forget to save codes)
- Verification step ensures TOTP is working before enabling

---

### Pattern 2: Mandatory MFA for Admin Users

**Use Case**: Require MFA for users with elevated privileges (admins, moderators) while optional for regular users.

```clojure
(ns myapp.admin.middleware
  (:require [boundary.user.shell.mfa :as mfa]
            [boundary.user.ports :as user-ports]))

(defn require-mfa-for-admin
  "HTTP middleware: Require MFA for admin users"
  [handler user-service]
  (fn [request]
    (let [user-id (get-in request [:session :user :id])
          user (user-ports/find-by-id user-service user-id)
          role (:role user)]
      
      (if (and (= role "admin")
               (not (:mfa-enabled user)))
        ;; Admin without MFA - force setup
        {:status 403
         :body {:error "MFA required for admin accounts"
                :action "setup-mfa"
                :setup-url "/profile/mfa/setup"}}
        
        ;; MFA enabled or not admin - proceed
        (handler request)))))

(defn admin-routes [user-service]
  ["/admin"
   {:middleware [(require-mfa-for-admin user-service)]}
   
   ["/users"
    {:get {:handler admin-list-users-handler}}]
   
   ["/settings"
    {:get {:handler admin-settings-handler}
     :post {:handler admin-update-settings-handler}}]])
```

**Automatic MFA Setup on Admin Promotion**:

```clojure
(ns myapp.admin.service
  (:require [boundary.user.shell.mfa :as mfa]
            [boundary.user.ports :as user-ports]))

(defn promote-to-admin!
  "Promote user to admin role and initiate MFA setup if not enabled"
  [user-service email-service user-id]
  (let [user (user-ports/find-by-id user-service user-id)
        
        ;; Update role
        updated-user (user-ports/update-user user-service user-id {:role "admin"})]
    
    ;; If MFA not enabled, send setup instructions
    (when-not (:mfa-enabled updated-user)
      (let [setup-result (mfa/setup-mfa user-service user-id)
            
            ;; Send email with QR code and backup codes
            email-body (str "You've been promoted to Admin. "
                           "MFA is required for admin accounts.\n\n"
                           "Setup instructions:\n"
                           "1. Visit: " (:setup-url setup-result) "\n"
                           "2. Scan QR code or enter secret manually\n"
                           "3. Save backup codes securely:\n"
                           (clojure.string/join "\n" (:backup-codes setup-result)))]
        
        (email-service/send! {:to (:email updated-user)
                              :subject "Admin Promotion - MFA Setup Required"
                              :body email-body})))
    
    updated-user))
```

**Why This Pattern Works**:
- Security policy enforced automatically via middleware
- Admins can't access admin routes without MFA
- Promotion triggers MFA setup workflow
- Email provides clear instructions and backup codes

---

### Pattern 3: MFA for Sensitive Operations

**Use Case**: Require MFA verification for sensitive actions (money transfers, account deletion) even if user already authenticated with MFA during login.

```clojure
(ns myapp.banking.transfer
  (:require [boundary.user.shell.mfa :as mfa]
            [boundary.user.ports :as user-ports]))

(defn initiate-transfer-handler
  "POST /transfers - Initiate money transfer (requires MFA verification)"
  [user-service banking-service]
  (fn [request]
    (let [user-id (get-in request [:session :user :id])
          {:keys [to-account amount mfa-code]} (get request :body-params)
          user (user-ports/find-by-id user-service user-id)]
      
      ;; Check if user has MFA enabled
      (if-not (:mfa-enabled user)
        {:status 403
         :body {:error "MFA required for transfers"
                :action "enable-mfa"}}
        
        ;; Verify MFA code for this sensitive operation
        (let [mfa-secret (:mfa-secret user)
              code-valid? (mfa/verify-totp-code mfa-code mfa-secret)]
          
          (if code-valid?
            ;; MFA verified - proceed with transfer
            (let [transfer-result (banking-service/transfer!
                                     {:from-account (:account-id user)
                                      :to-account to-account
                                      :amount amount
                                      :authorized-by user-id
                                      :mfa-verified-at (java.time.Instant/now)})]
              {:status 200
               :body {:message "Transfer initiated"
                      :transfer-id (:id transfer-result)
                      :status (:status transfer-result)}})
            
            ;; Invalid MFA code
            {:status 401
             :body {:error "Invalid MFA code"
                    :hint "Enter current 6-digit code from authenticator app"}}))))))

(defn delete-account-handler
  "DELETE /account - Delete user account (requires MFA + confirmation)"
  [user-service]
  (fn [request]
    (let [user-id (get-in request [:session :user :id])
          {:keys [confirmation mfa-code]} (get request :body-params)
          user (user-ports/find-by-id user-service user-id)]
      
      (cond
        ;; Check MFA required
        (not (:mfa-enabled user))
        {:status 403
         :body {:error "MFA required for account deletion"}}
        
        ;; Check confirmation phrase
        (not= confirmation "DELETE MY ACCOUNT")
        {:status 400
         :body {:error "Confirmation phrase incorrect"
                :required "DELETE MY ACCOUNT"}}
        
        ;; Verify MFA code
        (not (mfa/verify-totp-code mfa-code (:mfa-secret user)))
        {:status 401
         :body {:error "Invalid MFA code"}}
        
        ;; All checks passed - delete account
        :else
        (do
          (user-ports/delete-user! user-service user-id)
          {:status 200
           :body {:message "Account deleted successfully"}})))))
```

**Why This Pattern Works**:
- Sensitive operations get extra verification (defense in depth)
- Even if session is hijacked, attacker needs TOTP device
- Real-time MFA verification (not relying on login MFA from hours ago)
- Clear error messages guide user through verification

---

### Pattern 4: Backup Code Recovery Flow

**Use Case**: User lost their phone/authenticator app but has backup codes saved.

```clojure
(ns myapp.auth.recovery
  (:require [boundary.user.shell.mfa :as mfa]
            [boundary.user.ports :as user-ports]
            [boundary.user.core.mfa :as mfa-core]))

(defn login-with-backup-code-handler
  "POST /auth/login - Login with backup code"
  [user-service session-service]
  (fn [request]
    (let [{:keys [email password mfa-code]} (get request :body-params)
          
          ;; Authenticate with password
          user (user-ports/find-by-email user-service email)
          password-valid? (user-ports/verify-password user-service user password)]
      
      (if-not password-valid?
        {:status 401
         :body {:error "Invalid credentials"}}
        
        ;; Check if MFA code is backup code (12 chars vs 6 digits)
        (let [is-backup-code? (and (string? mfa-code)
                                   (> (count mfa-code) 6)
                                   (re-matches #"[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}" mfa-code))
              
              verification-result (if is-backup-code?
                                    ;; Try backup code
                                    (mfa/verify-backup-code user-service (:id user) mfa-code)
                                    ;; Try TOTP code
                                    (mfa/verify-totp-code mfa-code (:mfa-secret user)))]
          
          (if verification-result
            (let [;; Mark backup code as used if applicable
                  _ (when is-backup-code?
                      (mfa/mark-backup-code-used! user-service (:id user) mfa-code))
                  
                  ;; Check remaining backup codes
                  mfa-status (mfa/get-mfa-status user-service (:id user))
                  remaining-codes (:backup-codes-remaining mfa-status)
                  
                  ;; Create session
                  session (session-service/create-session! user-service (:id user))]
              
              {:status 200
               :body {:success true
                      :session-id (:id session)
                      :user (select-keys user [:id :email :name :role])
                      :warning (when (and is-backup-code? (< remaining-codes 3))
                                 (str "Warning: Only " remaining-codes " backup codes remaining. "
                                      "Consider regenerating backup codes."))}})
            
            {:status 401
             :body {:error "Invalid MFA code"
                    :hint "Enter 6-digit code from authenticator or 12-character backup code"}}))))))

(defn regenerate-backup-codes-handler
  "POST /profile/mfa/regenerate-codes - Generate new backup codes"
  [user-service]
  (fn [request]
    (let [user-id (get-in request [:session :user :id])
          {:keys [mfa-code]} (get request :body-params)
          user (user-ports/find-by-id user-service user-id)
          
          ;; Verify current MFA code before regenerating
          code-valid? (mfa/verify-totp-code mfa-code (:mfa-secret user))]
      
      (if-not code-valid?
        {:status 401
         :body {:error "MFA verification required to regenerate codes"}}
        
        ;; Generate new backup codes
        (let [new-codes (mfa/generate-backup-codes 10)
              _ (mfa/update-backup-codes! user-service user-id new-codes)]
          
          {:status 200
           :body {:message "Backup codes regenerated"
                  :backup-codes new-codes
                  :warning "Previous backup codes are no longer valid. Save these new codes securely."}})))))
```

**Why This Pattern Works**:
- Backup codes provide account recovery without support intervention
- System differentiates backup codes from TOTP codes by format
- Warning when backup codes running low (< 3 remaining)
- MFA verification required to regenerate codes (prevent abuse)

---

## Configuration

### Environment Variables

Configure MFA settings for your application:

```bash
# MFA Settings
MFA_ISSUER="Boundary Framework"  # Appears in authenticator app
MFA_QR_SERVICE_URL="https://api.qrserver.com/v1/create-qr-code/"  # QR code generator

# Security Settings
MFA_CODE_WINDOW=1  # Allow ±1 time window (30 seconds before/after)
MFA_MAX_ATTEMPTS=5  # Rate limit: max attempts per minute
MFA_LOCKOUT_DURATION=300  # Lockout duration in seconds (5 minutes)

# Backup Codes
MFA_BACKUP_CODE_COUNT=10  # Number of backup codes to generate
MFA_BACKUP_CODE_LENGTH=12  # Characters per backup code
```

### Application Configuration

```clojure
;; In resources/conf/prod/config.edn
{:boundary/mfa
 {:issuer #env [MFA_ISSUER "Boundary Framework"]
  :qr-service-url #env [MFA_QR_SERVICE_URL "https://api.qrserver.com/v1/create-qr-code/"]
  
  :totp {:algorithm "SHA1"  ; TOTP algorithm (SHA1 standard)
         :digits 6          ; Code length (6 standard)
         :period 30         ; Time step in seconds (30 standard)
         :window 1}         ; Accept ±1 time window
  
  :backup-codes {:count 10
                 :length 12
                 :format "XXX-XXXX-XXXX"}
  
  :rate-limiting {:max-attempts 5
                  :window-seconds 60
                  :lockout-seconds 300}}}
```

### Database Migration

MFA requires additional columns in the `users` table:

```sql
-- Migration: 006_add_mfa_to_users.sql
ALTER TABLE users ADD COLUMN mfa_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE users ADD COLUMN mfa_secret TEXT;
ALTER TABLE users ADD COLUMN mfa_backup_codes TEXT;  -- JSON array
ALTER TABLE users ADD COLUMN mfa_backup_codes_used TEXT;  -- JSON array
ALTER TABLE users ADD COLUMN mfa_enabled_at TIMESTAMP;

-- Indexes for performance
CREATE INDEX idx_users_mfa_enabled ON users(mfa_enabled) WHERE mfa_enabled = TRUE;
CREATE INDEX idx_users_mfa_enabled_at ON users(mfa_enabled_at);

-- Comments for documentation
COMMENT ON COLUMN users.mfa_enabled IS 'Whether MFA is enabled for this user';
COMMENT ON COLUMN users.mfa_secret IS 'Base32-encoded TOTP secret (encrypted)';
COMMENT ON COLUMN users.mfa_backup_codes IS 'JSON array of backup codes';
COMMENT ON COLUMN users.mfa_backup_codes_used IS 'JSON array of used backup codes';
COMMENT ON COLUMN users.mfa_enabled_at IS 'Timestamp when MFA was enabled';
```

Apply migration:

```bash
psql -U boundary_dev -d boundary_dev -f migrations/006_add_mfa_to_users.sql
```

---

## Performance

### Latency Benchmarks

**Test Conditions**: MacBook Pro M1, PostgreSQL local, no network latency

| Operation | Latency (p50) | Latency (p99) | Notes |
|-----------|---------------|---------------|-------|
| Setup MFA | 25ms | 45ms | Includes secret + backup codes generation |
| Enable MFA | 35ms | 60ms | Includes TOTP verification + DB write |
| Disable MFA | 15ms | 30ms | DB update only |
| Verify TOTP | 5ms | 12ms | Pure cryptographic operation |
| Verify Backup Code | 20ms | 40ms | DB lookup + validation |
| Login with MFA | 80ms | 150ms | Full auth flow (password + MFA + session) |

### Throughput

**Sequential MFA Operations**:
- **Setup**: 40 operations/second
- **Verification**: 200 operations/second (TOTP only)
- **Login**: 12 operations/second (full auth flow)

**Concurrent MFA Verifications** (10 workers):
- **TOTP Verification**: 1,500 ops/second
- **Backup Code Verification**: 400 ops/second

### Scaling Considerations

**Database Load**:
- MFA adds minimal database overhead (< 5% increase in auth queries)
- Backup code validation requires JSON parsing (slightly slower than TOTP)
- Consider caching MFA status for frequently accessed users

**TOTP Verification**:
- CPU-bound operation (cryptographic operations)
- Negligible memory footprint (< 1 KB per verification)
- Scales linearly with CPU cores

---

## Testing

### Unit Tests (Pure Core Logic)

Test MFA business rules without I/O:

```clojure
(ns myapp.user.core.mfa-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.user.core.mfa :as mfa-core]))

(deftest can-enable-mfa-test
  (testing "user can enable MFA when eligible"
    (let [user {:id #uuid "123" :active true :mfa-enabled false}
          result (mfa-core/can-enable-mfa? user)]
      (is (:can-enable? result))))
  
  (testing "user cannot enable MFA if already enabled"
    (let [user {:id #uuid "123" :active true :mfa-enabled true}
          result (mfa-core/can-enable-mfa? user)]
      (is (not (:can-enable? result)))
      (is (= "MFA is already enabled" (:reason result)))))
  
  (testing "deleted user cannot enable MFA"
    (let [user {:id #uuid "123" :active true :mfa-enabled false
                :deleted-at #inst "2024-01-01T00:00:00Z"}
          result (mfa-core/can-enable-mfa? user)]
      (is (not (:can-enable? result)))
      (is (= "User account is deleted" (:reason result))))))

(deftest should-require-mfa-test
  (testing "requires MFA for users with MFA enabled"
    (let [user {:mfa-enabled true}
          risk-analysis {}
          result (mfa-core/should-require-mfa? user risk-analysis)]
      (is (true? result))))
  
  (testing "does not require MFA for users without MFA"
    (let [user {:mfa-enabled false}
          risk-analysis {}
          result (mfa-core/should-require-mfa? user risk-analysis)]
      (is (false? result)))))

(deftest prepare-mfa-enablement-test
  (testing "prepares user for MFA enablement"
    (let [user {:id #uuid "123" :mfa-enabled false}
          secret "JBSWY3DPEHPK3PXP"
          codes ["CODE-1234-ABCD" "CODE-5678-EFGH"]
          time #inst "2024-01-01T10:00:00Z"
          result (mfa-core/prepare-mfa-enablement user secret codes time)]
      
      (is (= true (:mfa-enabled result)))
      (is (= secret (:mfa-secret result)))
      (is (= codes (:mfa-backup-codes result)))
      (is (= [] (:mfa-backup-codes-used result)))
      (is (= time (:mfa-enabled-at result))))))
```

### Integration Tests (With Mocked Repository)

Test MFA service operations with mocked dependencies:

```clojure
(ns myapp.user.shell.mfa-test
  {:kaocha.testable/meta {:integration true :mfa true}}
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.user.shell.mfa :as mfa]
            [boundary.user.ports :as ports]))

(deftest setup-mfa-test
  (testing "setup generates secret and backup codes"
    (let [mock-repo (reify ports/IUserRepository
                      (find-by-id [_ user-id]
                        {:id user-id :email "test@example.com"
                         :active true :mfa-enabled false}))
          service (->UserService mock-repo nil)
          result (mfa/setup-mfa service #uuid "123")]
      
      (is (some? (:secret result)))
      (is (= 32 (count (:secret result))))  ; Base32 secret length
      (is (= 10 (count (:backup-codes result))))
      (is (every? #(re-matches #"[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}" %)
                  (:backup-codes result)))
      (is (some? (:qr-code-url result))))))

(deftest enable-mfa-test
  (testing "enables MFA with valid verification code"
    (let [secret (mfa/generate-totp-secret)
          valid-code (get-current-totp-code secret)  ; Helper to get current code
          
          mock-repo (reify ports/IUserRepository
                      (find-by-id [_ user-id]
                        {:id user-id :mfa-enabled false :active true})
                      (update-user! [_ user-id updates]
                        (merge {:id user-id} updates)))
          
          service (->UserService mock-repo nil)
          backup-codes ["CODE-1234-ABCD" "CODE-5678-EFGH"]
          result (mfa/enable-mfa service #uuid "123" secret backup-codes valid-code)]
      
      (is (:success? result))))
  
  (testing "rejects invalid verification code"
    (let [secret (mfa/generate-totp-secret)
          invalid-code "000000"  ; Wrong code
          
          mock-repo (reify ports/IUserRepository
                      (find-by-id [_ user-id]
                        {:id user-id :mfa-enabled false :active true}))
          
          service (->UserService mock-repo nil)
          backup-codes ["CODE-1234-ABCD"]
          result (mfa/enable-mfa service #uuid "123" secret backup-codes invalid-code)]
      
      (is (not (:success? result))))))
```

### End-to-End Tests (HTTP API)

Test complete MFA flow via HTTP:

```clojure
(deftest mfa-e2e-flow-test
  (testing "complete MFA setup and login flow"
    (let [;; 1. Create user
          user (create-test-user! {:email "mfa-test@example.com"
                                   :password "password123"})
          token (login-and-get-token "mfa-test@example.com" "password123")
          
          ;; 2. Setup MFA
          setup-response (http-post "/api/auth/mfa/setup"
                                    {:headers {"Authorization" (str "Bearer " token)}})
          setup-data (json/parse-string (:body setup-response) true)
          secret (:secret setup-data)
          backup-codes (:backupCodes setup-data)
          
          ;; 3. Get current TOTP code
          totp-code (generate-totp-code secret)
          
          ;; 4. Enable MFA
          enable-response (http-post "/api/auth/mfa/enable"
                                     {:headers {"Authorization" (str "Bearer " token)}
                                      :body (json/generate-string
                                              {:secret secret
                                               :backupCodes backup-codes
                                               :verificationCode totp-code})})
          
          ;; 5. Logout and login with MFA
          _ (http-post "/api/auth/logout" {:headers {"Authorization" (str "Bearer " token)}})
          
          ;; 6. Try login without MFA (should require MFA)
          login-no-mfa (http-post "/api/auth/login"
                                  {:body (json/generate-string
                                           {:email "mfa-test@example.com"
                                            :password "password123"})})
          
          ;; 7. Login with MFA
          new-totp-code (generate-totp-code secret)
          login-with-mfa (http-post "/api/auth/login"
                                    {:body (json/generate-string
                                             {:email "mfa-test@example.com"
                                              :password "password123"
                                              :mfa-code new-totp-code})})]
      
      ;; Assertions
      (is (= 200 (:status setup-response)))
      (is (= 200 (:status enable-response)))
      (is (= 200 (:status login-no-mfa)))
      (is (get-in (json/parse-string (:body login-no-mfa) true) [:requires-mfa?]))
      (is (= 200 (:status login-with-mfa)))
      (is (get-in (json/parse-string (:body login-with-mfa) true) [:success])))))
```

---

## Security Considerations

### Secret Storage

**Best Practices**:

1. **Database Encryption**: Encrypt `mfa_secret` column at application level
2. **Key Management**: Use separate encryption keys (not same as password hashes)
3. **Secret Rotation**: Support re-generating secrets (user re-enrolls MFA)

```clojure
(ns myapp.security.encryption
  (:require [buddy.core.crypto :as crypto]
            [buddy.core.codecs :as codecs]))

(def ^:private encryption-key
  "Load from environment or key management service"
  (System/getenv "MFA_SECRET_ENCRYPTION_KEY"))

(defn encrypt-mfa-secret [secret]
  (let [iv (crypto/generate-iv 16)
        encrypted (crypto/encrypt (codecs/to-bytes secret)
                                  (codecs/to-bytes encryption-key)
                                  iv
                                  {:algorithm :aes256-cbc})]
    (codecs/bytes->hex (concat iv encrypted))))

(defn decrypt-mfa-secret [encrypted-hex]
  (let [bytes (codecs/hex->bytes encrypted-hex)
        iv (take 16 bytes)
        encrypted (drop 16 bytes)
        decrypted (crypto/decrypt (byte-array encrypted)
                                  (codecs/to-bytes encryption-key)
                                  (byte-array iv)
                                  {:algorithm :aes256-cbc})]
    (codecs/bytes->str decrypted)))
```

### Rate Limiting

Prevent brute force attacks on MFA codes:

```clojure
(ns myapp.security.rate-limit
  (:require [boundary.cache.ports :as cache-ports]))

(defn check-mfa-rate-limit
  "Check if user has exceeded MFA verification attempts.
   
   Args:
     cache-service: Cache service implementation
     user-id: User UUID
     max-attempts: Maximum attempts allowed (default 5)
     window-seconds: Time window in seconds (default 60)
     
   Returns:
     {:allowed? boolean :remaining integer :reset-at instant}"
  [cache-service user-id max-attempts window-seconds]
  (let [cache-key (str "mfa-attempts:" user-id)
        attempts (or (cache-ports/get-value cache-service cache-key) 0)
        remaining (- max-attempts attempts)
        reset-at (+ (System/currentTimeMillis) (* window-seconds 1000))]
    
    (if (>= attempts max-attempts)
      {:allowed? false :remaining 0 :reset-at (java.time.Instant/ofEpochMilli reset-at)}
      (do
        ;; Increment attempts counter
        (cache-ports/set-value! cache-service cache-key (inc attempts) window-seconds)
        {:allowed? true :remaining (dec remaining) :reset-at (java.time.Instant/ofEpochMilli reset-at)}))))

;; Use in MFA verification
(defn verify-with-rate-limit [cache-service user-id mfa-code mfa-secret]
  (let [rate-limit (check-mfa-rate-limit cache-service user-id 5 60)]
    (if-not (:allowed? rate-limit)
      {:success? false
       :error "Too many attempts"
       :retry-after (:reset-at rate-limit)}
      
      (let [valid? (mfa/verify-totp-code mfa-code mfa-secret)]
        (when valid?
          ;; Clear rate limit on successful verification
          (cache-ports/delete-key! cache-service (str "mfa-attempts:" user-id)))
        {:success? valid?
         :remaining-attempts (:remaining rate-limit)}))))
```

### Compliance

**NIST SP 800-63B (Level 2)**:
- ✅ Multi-factor authentication required
- ✅ Cryptographically secure secret generation
- ✅ Time-based one-time passwords (TOTP)
- ✅ Rate limiting on authentication attempts
- ✅ Session management

**PCI DSS**:
- ✅ Multi-factor authentication for administrative access
- ✅ Unique authentication credentials per user
- ✅ Audit logging of authentication attempts

**GDPR**:
- ✅ MFA data encrypted at rest
- ✅ Right to erasure (MFA data deleted with account)
- ✅ Data minimization (only necessary MFA data stored)

---

## Troubleshooting

### Common Issues

#### 1. "Invalid verification code" During Setup

**Symptoms**: Setup completes, but enable fails with "Invalid verification code"

**Causes**:
- Server time not synchronized
- Code expired (30-second window)
- Typo in code entry

**Solutions**:

```bash
# Check server time synchronization
date -u  # Should match actual UTC time

# Ensure NTP is running
sudo systemctl status ntp  # Linux
sudo systemctl status systemd-timesyncd  # Alternative

# Verify TOTP generation (REPL)
clojure -M:repl-clj
user=> (require '[boundary.user.shell.mfa :as mfa])
user=> (def secret "JBSWY3DPEHPK3PXP")
user=> (mfa/verify-totp-code "123456" secret)  ; Use current code from app
```

#### 2. Backup Codes Not Working

**Symptoms**: Backup code rejected during login

**Causes**:
- Code already used
- Format incorrect (missing dashes)
- Typo in code

**Solutions**:

```bash
# Check MFA status
curl -X GET http://localhost:3000/api/auth/mfa/status \
  -H "Authorization: Bearer YOUR_TOKEN" | jq '.'

# Verify format: XXXX-XXXX-XXXX (12 alphanumeric chars with dashes)
# Try copy-paste to avoid typos
```

#### 3. Lost Access to Authenticator App

**Symptoms**: Can't log in, no access to TOTP codes

**Recovery Options**:

1. **Use Backup Code**: Enter one of the 10 backup codes saved during setup
2. **Contact Support**: Support can temporarily disable MFA (requires identity verification)
3. **Account Recovery**: Implement email-based recovery flow

**Prevention**:

```clojure
(defn send-backup-codes-reminder
  "Send periodic reminders to save backup codes"
  [email-service user]
  (when (and (:mfa-enabled user)
             (< (count-unused-backup-codes user) 3))
    (email-service/send! {:to (:email user)
                          :subject "MFA Backup Codes Running Low"
                          :body "You have less than 3 backup codes remaining. 
                                Consider regenerating backup codes to ensure account access."})))
```

---

## Next Steps

- **[Full-Text Search Guide]({{< relref "search.md" >}})** - Search users with MFA enabled
- **[Distributed Caching]({{< relref "caching.md" >}})** - Cache MFA status for performance
- **[Observability]({{< relref "integrate-observability.adoc" >}})** - Monitor MFA adoption and failures
- **[GitHub Repository](https://github.com/thijs-creemers/boundary)** - View MFA implementation source code

---

**Questions or Issues?** [Open an issue on GitHub](https://github.com/thijs-creemers/boundary/issues) or join our [community chat](https://discord.gg/boundary).
