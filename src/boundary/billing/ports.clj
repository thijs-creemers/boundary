(ns boundary.billing.ports)

(defprotocol IBillingRepository
  "Billing data persistence with transaction support."

  ;; Invoice operations
  (create-invoice [this invoice-entity]
    "Create new invoice. Returns invoice with generated ID and number.")

  (find-invoice-by-id [this invoice-id tenant-id]
    "Retrieve invoice by ID within tenant scope.")

  (find-invoices-by-customer [this customer-id tenant-id options]
    "Retrieve paginated invoices for customer with filtering.")

  (update-invoice-status [this invoice-id new-status updated-by]
    "Update invoice status with audit trail. Returns updated invoice.")

  (find-overdue-invoices [this tenant-id as-of-date]
    "Find invoices past due date for collections processing.")

  ;; Payment operations
  (create-payment [this payment-entity]
    "Create new payment record. Returns payment with generated ID.")

  (find-payments-by-invoice [this invoice-id]
    "Retrieve all payments for specific invoice.")

  (update-payment-status [this payment-id new-status transaction-details]
    "Update payment status with external transaction details.")

  ;; Financial reporting queries
  (calculate-revenue-by-period [this tenant-id start-date end-date]
    "Calculate revenue for date range. Returns aggregated financial data.")

  (find-unpaid-invoices-summary [this tenant-id]
    "Summary of unpaid invoices by age brackets."))

(defprotocol IBillingTransactionRepository
  "Financial transaction audit trail."

  (record-transaction [this transaction-entity]
    "Record financial transaction for audit purposes.")

  (find-transactions-by-invoice [this invoice-id]
    "Retrieve all financial transactions related to invoice.")

  (find-transactions-by-date-range [this tenant-id start-date end-date]
    "Retrieve transactions within date range for reporting."))
