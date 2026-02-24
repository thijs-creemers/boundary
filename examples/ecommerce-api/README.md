# E-commerce API Example

**A complete REST API for e-commerce with payment integration built with Boundary Framework**

This example demonstrates:
- ✅ RESTful JSON API design
- ✅ Multiple interconnected modules (product, cart, order, payment)
- ✅ Functional Core / Imperative Shell (FC/IS) architecture
- ✅ Order state machine (pending → paid → shipped → delivered)
- ✅ Mock Stripe payment integration with webhooks
- ✅ SQLite database with migrations
- ✅ Comprehensive test coverage

**Complexity:** ⭐⭐⭐ Advanced  
**Time to complete:** 2-3 hours  
**Lines of code:** ~2,500 (production) + ~600 (tests)

---

## What You'll Build

A fully functional e-commerce API with:

### Features

- **Product Catalog** - List and view products with pricing
- **Shopping Cart** - Session-based cart with add/update/remove
- **Checkout Flow** - Create orders from cart
- **Payment Processing** - Stripe-like payment intents
- **Webhook Handling** - Process payment events
- **Order Management** - Track order status

### API Endpoints

```
Products:
GET  /api/products           - List active products
GET  /api/products/:slug     - Get product details

Cart:
GET  /api/cart               - Get current cart
POST /api/cart/items         - Add item to cart
PATCH /api/cart/items/:id    - Update quantity
DELETE /api/cart/items/:id   - Remove item
DELETE /api/cart             - Clear cart

Orders:
POST /api/checkout           - Create order from cart
GET  /api/orders             - List customer orders
GET  /api/orders/:id         - Get order details
POST /api/orders/:id/cancel  - Cancel order

Payments:
POST /api/payments/intents        - Create payment intent
GET  /api/payments/orders/:id     - Get payment status
POST /api/webhooks/payment        - Handle webhooks
POST /api/payments/simulate       - Simulate payment (dev)
```

### Data Model

**Product:**
```clojure
{:id          uuid
 :name        "Boundary T-Shirt"
 :slug        "boundary-tshirt"
 :description "Comfortable cotton t-shirt"
 :price-cents 2999            ;; €29.99
 :currency    "EUR"
 :stock       100
 :active      true}
```

**Cart:**
```clojure
{:cart-id       uuid
 :session-id    "session-123"
 :items         [{:product-id uuid
                  :quantity   2
                  :product    {...}
                  :line-total-cents 5998}]
 :item-count    2
 :subtotal-cents 5998
 :currency      "EUR"}
```

**Order:**
```clojure
{:id             uuid
 :order-number   "ORD-20260117-12345"
 :status         :pending  ;; :paid :shipped :delivered :cancelled :refunded
 :customer-email "user@example.com"
 :customer-name  "John Doe"
 :items          [{:product-name "T-Shirt" :quantity 2 :total-cents 5998}]
 :total-cents    5998
 :payment-intent-id "pi_abc123"}
```

---

## Quick Start

### 1. Prerequisites

- Java 11+
- Clojure CLI

### 2. Navigate to Example

```bash
cd examples/ecommerce-api
```

### 3. Start the Application

```bash
# Start with default dev profile
clojure -M -m ecommerce.main

# Or start a REPL for interactive development
clojure -M:repl-clj
```

In the REPL:
```clojure
(require '[ecommerce.system :as sys])
(def system (sys/start!))
;; Server running at http://localhost:3002
```

### 4. Test the API

```bash
# List products
curl http://localhost:3002/api/products

# Add to cart
curl -X POST http://localhost:3002/api/cart/items \
  -H "Content-Type: application/json" \
  -H "X-Session-ID: my-session" \
  -d '{"product-id": "11111111-1111-1111-1111-111111111111", "quantity": 2}'

# Checkout
curl -X POST http://localhost:3002/api/checkout \
  -H "Content-Type: application/json" \
  -H "X-Session-ID: my-session" \
  -d '{
    "email": "test@example.com",
    "name": "Test User",
    "shipping-address": {
      "line1": "123 Main St",
      "city": "Amsterdam",
      "postal-code": "1234AB",
      "country": "NL"
    }
  }'

# Simulate payment (dev only)
curl -X POST http://localhost:3002/api/payments/simulate \
  -H "Content-Type: application/json" \
  -d '{"order-id": "<order-id-from-checkout>", "success": true}'
```

---

## Project Structure

```
ecommerce-api/
├── README.md                    # This file
├── deps.edn                     # Dependencies
├── resources/
│   └── config/
│       ├── dev.edn             # Development config
│       └── test.edn            # Test config
├── migrations/
│   ├── 001-create-products.sql # Products + seed data
│   ├── 002-create-carts.sql    # Carts + items
│   └── 003-create-orders.sql   # Orders + items
├── src/ecommerce/
│   ├── main.clj                # Entry point
│   ├── system.clj              # Integrant system
│   ├── product/                # Product module
│   │   ├── schema.clj          # Malli schemas
│   │   ├── ports.clj           # Protocols
│   │   ├── core/product.clj    # Pure business logic ⭐
│   │   └── shell/
│   │       ├── persistence.clj # SQLite adapter
│   │       ├── service.clj     # Orchestration
│   │       └── http.clj        # HTTP handlers
│   ├── cart/                   # Cart module (same structure)
│   ├── order/                  # Order module (state machine!)
│   ├── payment/                # Payment module (Stripe mock)
│   │   └── shell/
│   │       └── provider.clj    # Mock Stripe provider
│   └── shared/
│       └── http/
│           ├── middleware.clj  # JSON, CORS, logging
│           └── responses.clj   # Standard JSON responses
└── test/ecommerce/
    ├── product/core/product_test.clj
    ├── cart/core/cart_test.clj
    ├── order/core/order_test.clj
    └── payment/core/payment_test.clj
```

---

## Architecture: FC/IS in Practice

### The State Machine Pattern

Orders follow a strict state machine:

```
┌─────────┐    ┌──────┐    ┌─────────┐    ┌───────────┐
│ PENDING │───▶│ PAID │───▶│ SHIPPED │───▶│ DELIVERED │
└─────────┘    └──────┘    └─────────┘    └───────────┘
     │              │            │              │
     │              │            │              │
     ▼              ▼            ▼              ▼
┌───────────┐  ┌───────────┐ (refund)     ┌──────────┐
│ CANCELLED │  │ REFUNDED  │◀─────────────│ REFUNDED │
└───────────┘  └───────────┘              └──────────┘
```

Implemented as pure functions:

```clojure
;; In order/core/order.clj

(def status-transitions
  {:pending   #{:paid :cancelled}
   :paid      #{:shipped :refunded :cancelled}
   :shipped   #{:delivered :refunded}
   :delivered #{:refunded}
   :cancelled #{}
   :refunded  #{}})

(defn valid-transition? [from to]
  (contains? (get status-transitions from #{}) to))

(defn transition-status [order new-status now]
  (if (valid-transition? (:status order) new-status)
    {:ok (-> order
             (assoc :status new-status)
             (assoc :updated-at now))}
    {:error :invalid-transition
     :from (:status order)
     :to new-status}))
```

### Payment Provider Abstraction

The payment module uses ports to abstract the provider:

```clojure
;; Easily swap between Mock and real Stripe

(defprotocol IPaymentProvider
  (create-intent [this amount currency metadata])
  (confirm-intent [this intent-id])
  (verify-webhook-signature [this payload signature]))

;; Mock implementation (for demo/testing)
(defrecord MockPaymentProvider [config]
  IPaymentProvider
  (create-intent [_ amount currency metadata]
    {:id (str "pi_" (random-uuid))
     :amount amount
     :status :requires-payment-method
     :client-secret (str "secret_" (random-uuid))}))

;; In production, create StripePaymentProvider
```

### Multi-Module Coordination

The checkout flow coordinates multiple modules:

```clojure
;; In order/shell/service.clj

(defn create-order [session-id customer-info]
  ;; 1. Get cart
  (if-let [cart (cart-ports/find-by-session cart-repo session-id)]
    ;; 2. Validate stock
    (let [products (product-ports/find-by-ids prod-repo product-ids)
          stock-errors (check-stock cart products)]
      (if (empty? stock-errors)
        ;; 3. Create order (pure core function)
        (let [order (order-core/create-order items products customer-info (now))]
          ;; 4. Save order
          (order-ports/save! order-repo order)
          ;; 5. Deduct stock
          (deduct-stock! products cart)
          ;; 6. Clear cart
          (cart-ports/clear-cart! cart-repo (:id cart))
          {:ok order})
        {:error :insufficient-stock}))))
```

---

## Key Patterns Demonstrated

### 1. API-First Design

Standard JSON responses:
```clojure
;; Success
{:data {:id "...", :name "Product"}}
{:data [...], :meta {:total 100, :limit 20, :offset 0}}

;; Error
{:error {:code "not_found", :message "Product not found"}}
```

### 2. Session-Based Cart

Cart tied to session ID (header):
```bash
curl -H "X-Session-ID: user-123" http://localhost:3002/api/cart
```

### 3. Webhook Signature Verification

```clojure
;; Stripe-compatible signature format
;; Header: Stripe-Signature: t=timestamp,v1=signature

(defn verify-signature [payload signature secret]
  (let [parts (parse-signature signature)
        expected (compute-hmac (str (:t parts) "." payload) secret)]
    (= expected (:v1 parts))))
```

### 4. Idempotent Migrations

```sql
CREATE TABLE IF NOT EXISTS products (...);
CREATE INDEX IF NOT EXISTS idx_products_slug ON products(slug);
```

---

## Running Tests

```bash
# Run all tests
clojure -M:test

# Run with verbose output
clojure -M:test --reporter documentation
```

Expected output:
```
55 tests, 120 assertions, 0 failures.
```

---

## Configuration

### Development (`resources/config/dev.edn`)

```clojure
{:server {:port 3002
          :join? false}
 :database {:dbtype "sqlite"
            :dbname "ecommerce-dev.db"}
 :payment {:provider :mock
           :webhook-secret "whsec_test_secret"
           :api-key "sk_test_mock_key"}}
```

### Production

Replace mock payment provider with real Stripe:

```clojure
{:payment {:provider :stripe
           :webhook-secret #env STRIPE_WEBHOOK_SECRET
           :api-key #env STRIPE_API_KEY}}
```

---

## API Examples

### Complete Purchase Flow

```bash
# 1. Browse products
curl http://localhost:3002/api/products | jq '.data[0]'

# 2. Add to cart
curl -X POST http://localhost:3002/api/cart/items \
  -H "Content-Type: application/json" \
  -H "X-Session-ID: demo" \
  -d '{"product-id": "11111111-1111-1111-1111-111111111111", "quantity": 1}'

# 3. View cart
curl -H "X-Session-ID: demo" http://localhost:3002/api/cart | jq

# 4. Checkout
ORDER=$(curl -s -X POST http://localhost:3002/api/checkout \
  -H "Content-Type: application/json" \
  -H "X-Session-ID: demo" \
  -d '{
    "email": "demo@example.com",
    "name": "Demo User",
    "shipping-address": {
      "line1": "123 Demo St",
      "city": "Amsterdam",
      "postal-code": "1234AB",
      "country": "NL"
    }
  }')
echo $ORDER | jq

# 5. Get order ID
ORDER_ID=$(echo $ORDER | jq -r '.data.id')

# 6. Create payment intent
curl -X POST http://localhost:3002/api/payments/intents \
  -H "Content-Type: application/json" \
  -d "{\"order-id\": \"$ORDER_ID\"}" | jq

# 7. Simulate successful payment
curl -X POST http://localhost:3002/api/payments/simulate \
  -H "Content-Type: application/json" \
  -d "{\"order-id\": \"$ORDER_ID\", \"success\": true}" | jq

# 8. Check order status (now "paid")
curl http://localhost:3002/api/orders/$ORDER_ID | jq '.data.status'
```

---

## Extending the Example

### Add Real Stripe Integration

1. Add Stripe SDK dependency
2. Create `StripePaymentProvider`:
   ```clojure
   (defrecord StripePaymentProvider [api-key]
     IPaymentProvider
     (create-intent [_ amount currency metadata]
       (stripe/payment-intents-create
        {:amount amount
         :currency currency
         :metadata metadata})))
   ```
3. Update system.clj to use based on config

### Add User Authentication

1. Create `user/` module with JWT tokens
2. Add authentication middleware
3. Associate carts and orders with user IDs

### Add Inventory Management

1. Add `inventory/` module for stock tracking
2. Implement reservations during checkout
3. Handle abandoned cart stock restoration

---

## Resources

- [Boundary Documentation](../../docs-site/)
- [Stripe Payment Intents](https://stripe.com/docs/payments/payment-intents)
- [Stripe Webhooks](https://stripe.com/docs/webhooks)
- [FC/IS Architecture](https://www.destroyallsoftware.com/screencasts/catalog/functional-core-imperative-shell)

---

**Congratulations!** You've built a complete e-commerce API. 🎉

**Key Takeaways:**
- State machines as pure data (easy to test!)
- Port abstraction for external services
- Multi-module coordination through services
- Webhook handling with signature verification
