(ns boundary.ui-style
  "Shared app-wide UI asset bundles.

   Keep CSS/JS asset ordering centralized here so modules can reuse the same
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
  ["/css/fonts.css"
   "/css/boundary-tokens.css"
   "/css/admin.css"
   "/css/app.css"
   "/css/daisy-admin.css"])

(def css-bundles
  "Registry of known stylesheet bundles."
  {:base        base-css
   :pilot       pilot-css
   :admin-pilot admin-pilot-css})

(def base-js
  "Base JavaScript stack.
   Alpine.js must load before HTMX."
  ["/js/theme.js"
   "/js/alpine.min.js"
   "/js/htmx.min.js"])

(def pilot-js
  "Pilot JavaScript stack."
  base-js)

(def admin-pilot-js
  "Admin pilot JavaScript stack.
   admin-ux.js must load before alpine.min.js because it registers
   the sidebar Alpine store via the alpine:init event."
  ["/js/theme.js"
   "/js/admin-ux.js"
   "/js/alpine.min.js"
   "/js/htmx.min.js"
   "/js/forms.js"
   "/js/keyboard.js"])

(def js-bundles
  "Registry of known JavaScript bundles."
  {:base        base-js
   :pilot       pilot-js
   :admin-pilot admin-pilot-js})

(defn bundle
  "Return stylesheet list for `k` bundle key.
   Falls back to `:base`."
  [k]
  (get css-bundles k base-css))

(defn js-bundle
  "Return JavaScript list for `k` bundle key.
   Falls back to `:base`."
  [k]
  (get js-bundles k base-js))
