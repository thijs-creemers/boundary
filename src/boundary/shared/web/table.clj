(ns boundary.shared.web.table
  "DEPRECATED: Use boundary.platform.shell.web.table instead.
   
   This namespace is kept for backwards compatibility only.
   All functions are re-exported from the new location."
  (:require [boundary.platform.shell.web.table :as platform-table]))

;; Re-export all public functions for backwards compatibility
(def parse-search-filters platform-table/parse-search-filters)
(def search-filters->params platform-table/search-filters->params)
(def parse-table-query platform-table/parse-table-query)
(def table-query->params platform-table/table-query->params)
(def encode-query-params platform-table/encode-query-params)
