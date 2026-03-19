(ns boundary.ui-style
  "Shared app-wide style bundles.

   Keep CSS asset ordering centralized here so modules can reuse the same
   rendering contract without hardcoding per-page lists.")

(def base-css
  "Legacy-compatible base stack."
  ["/css/pico.min.css"
   "/css/boundary-tokens.css"
   "/css/tokens-openprops.css"
   "/css/app.css"])

(def pilot-css
  "General daisy pilot stack."
  ["/css/boundary-tokens.css"
   "/css/app.css"
   "/css/daisy-admin.css"])

(def admin-pilot-css
  "Admin pilot stack with legacy admin rules plus daisy overrides."
  ["/css/boundary-tokens.css"
   "/css/admin.css"
   "/css/app.css"
   "/css/daisy-admin.css"])

(def css-bundles
  "Registry of known stylesheet bundles."
  {:base        base-css
   :pilot       pilot-css
   :admin-pilot admin-pilot-css})

(defn bundle
  "Return stylesheet list for `k` bundle key.
   Falls back to `:base`."
  [k]
  (get css-bundles k base-css))
