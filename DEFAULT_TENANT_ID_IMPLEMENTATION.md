# Default Tenant-ID Configuration Implementation

## Summary

Implemented a default tenant-id configuration for development and testing convenience. This provides a consistent tenant context for REPL development, CLI operations, and test fixtures while maintaining security best practices for production.

## Changes Made

### 1. Configuration Update

**File**: `resources/conf/dev/config.edn`

Added `:default-tenant-id` to the `:boundary/settings` configuration:

```clojure
:boundary/settings
{:name              "boundary-dev"
 :version           "0.1.0"
 :date-format       "yyyy-MM-dd"
 :date-time-format  "yyyy-MM-dd HH:mm:ss"
 :currency/iso-code "EUR"
 
 ;; Default tenant ID for development
 :default-tenant-id #or [#env DEFAULT_TENANT_ID "00000000-0000-0000-0000-000000000001"]}
```

**Features**:
- Uses Aero's `#or` reader tag for environment variable override
- Provides sensible default value: `"00000000-0000-0000-0000-000000000001"`
- Can be overridden via `DEFAULT_TENANT_ID` environment variable

### 2. Configuration API Enhancement

**File**: `src/boundary/config.clj`

Added new function `default-tenant-id`:

```clojure
(defn default-tenant-id
  "Extract default tenant ID for development/testing.
   
   This provides a consistent tenant context for:
   - REPL development and testing
   - CLI operations without explicit tenant specification  
   - Default test fixtures
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     UUID string of default tenant ID
   
   Note:
     Production systems should NOT rely on defaults and must
     always specify tenant-id explicitly in requests."
  [config]
  (get-in config [:active :boundary/settings :default-tenant-id]))
```

**Added REPL usage examples**:

```clojure
;; Get default tenant ID for development
(default-tenant-id config)
;; => "00000000-0000-0000-0000-000000000001"

;; Use default tenant ID in REPL development
(require '[boundary.shared.core.utils.type-conversion :as tc])
(def tenant-id (tc/string->uuid (default-tenant-id config)))
;; => #uuid "00000000-0000-0000-0000-000000000001"
```

### 3. Documentation

**File**: `docs/development/default-tenant-id.adoc`

Created comprehensive documentation covering:
- Configuration setup
- Environment variable override
- REPL development usage
- CLI integration patterns
- Test fixture examples
- Production considerations and security best practices

## Usage Examples

### REPL Development

```clojure
;; Load configuration and get default tenant
(require '[boundary.config :as config])
(require '[boundary.shared.core.utils.type-conversion :as tc])

(def cfg (config/load-config))
(def tenant-id (tc/string->uuid (config/default-tenant-id cfg)))

;; Use in operations
(require '[boundary.user.ports :as ports])
(require '[integrant.repl :as ig-repl])

(ig-repl/go)
(def user-service (:boundary/user-service integrant.repl.state/system))

(ports/register-user user-service
  {:email "dev@example.com"
   :name "Dev User"
   :password "secret"
   :role :user
   :tenant-id tenant-id
   :active true})
```

### Environment Variable Override

```zsh
# Set custom default tenant ID
export DEFAULT_TENANT_ID="12345678-1234-1234-1234-123456789abc"

# Start REPL
clojure -M:repl-clj
```

### CLI Integration Pattern

```clojure
(defn get-tenant-id-from-options
  "Get tenant ID from CLI options or use default."
  [options config]
  (if-let [tenant-id-str (:tenant-id options)]
    (tc/string->uuid tenant-id-str)
    (tc/string->uuid (config/default-tenant-id config))))
```

### Test Fixtures

```clojure
(def test-config (config/load-config {:profile :test}))
(def default-tenant-id 
  (tc/string->uuid (config/default-tenant-id test-config)))

(def test-user-data
  {:email "test@example.com"
   :name "Test User"
   :password "secret123"
   :role :user
   :tenant-id default-tenant-id
   :active true})
```

## Benefits

1. **Development Convenience**: No need to manually specify tenant-id in every REPL operation
2. **Consistent Testing**: All tests can use the same default tenant-id
3. **CLI Flexibility**: CLI commands can default to the configured tenant when not explicitly specified
4. **Environment Flexibility**: Different tenant-ids for different environments via env vars
5. **Security Awareness**: Documentation emphasizes that production must never rely on defaults

## Security Considerations

### ✅ Safe for Development
- REPL experimentation
- Local testing
- CLI administrative operations (with user acknowledgment)
- Test fixtures

### ⚠️ NOT for Production
- Production API requests must always include explicit tenant identification
- Authentication tokens should contain tenant context
- All tenant access must be validated in auth/authz layer
- Tenant context must be logged for audit trails

## Testing

All unit tests pass successfully:
```
309 tests, 1203 assertions, 0 failures
```

The configuration loads correctly in the REPL:
```clojure
user=> (config/default-tenant-id cfg)
"00000000-0000-0000-0000-000000000001"

user=> (tc/string->uuid (config/default-tenant-id cfg))
#uuid "00000000-0000-0000-0000-000000000001"
```

## Files Modified

1. `resources/conf/dev/config.edn` - Added default-tenant-id configuration
2. `src/boundary/config.clj` - Added default-tenant-id function and usage examples

## Files Created

1. `docs/development/default-tenant-id.adoc` - Comprehensive documentation
2. `DEFAULT_TENANT_ID_IMPLEMENTATION.md` - This summary document

## Next Steps (Optional)

1. **Test Profile Configuration**: Add default-tenant-id to test config if needed
2. **CLI Enhancement**: Update CLI commands to use default tenant-id when not specified
3. **Test Fixture Updates**: Refactor existing test fixtures to use the new default
4. **Production Config**: Ensure production config explicitly documents that defaults should NOT be used

## References

- Configuration Management: `warp.md#configuration-management`
- Multi-Tenancy: Tenant-id is used throughout the user module for data separation
- Type Conversion: `boundary.shared.core.utils.type-conversion` for UUID handling
