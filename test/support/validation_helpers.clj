(ns support.validation-helpers
  "Test helpers for validation configuration and testing.")

(def test-validation-config
  "Default validation configuration for tests - very permissive to avoid breaking existing tests."
  {:email-domain-allowlist #{} ; Empty means all domains allowed
   :password-policy {:min-length 1 ; Very lenient for tests
                     :max-length 1000
                     :require-uppercase? false
                     :require-lowercase? false
                     :require-numbers? false
                     :require-special-chars? false
                     :forbidden-patterns #{}}
   :name-restrictions {:min-length 1
                       :max-length 1000
                       :allowed-chars-regex ".*" ; Allow any characters in tests
                       :forbidden-names #{}}
   :role-restrictions {:allowed-roles #{:user :admin :manager :viewer :test-role}
                       :admin-email-domains #{} ; Any domain can be admin in tests
                       :default-role :user}
   :tenant-limits {:max-users-per-tenant 10000 ; High limit for tests
                   :check-enabled? false} ; Disabled in tests
   :cross-field-validation {:admin-requires-company-email? false
                            :manager-requires-department? false}})

(defn strict-test-validation-config
  "Validation configuration for testing strict validation scenarios."
  []
  {:email-domain-allowlist #{"example.com" "test.com"}
   :password-policy {:min-length 8
                     :max-length 128
                     :require-uppercase? true
                     :require-lowercase? true
                     :require-numbers? true
                     :require-special-chars? true
                     :forbidden-patterns #{"password" "123456"}}
   :name-restrictions {:min-length 2
                       :max-length 100
                       :allowed-chars-regex "[a-zA-Z\\s\\-'.]+"
                       :forbidden-names #{"admin" "root"}}
   :role-restrictions {:allowed-roles #{:user :admin :manager}
                       :admin-email-domains #{"example.com"}
                       :default-role :user}
   :tenant-limits {:max-users-per-tenant 100
                   :check-enabled? true}
   :cross-field-validation {:admin-requires-company-email? true
                            :manager-requires-department? true}})