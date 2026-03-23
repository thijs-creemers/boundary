(ns boundary.i18n.shell.middleware
  "Ring middleware for i18n — injects locale and translation function into requests.

   SHELL FUNCTION: wraps Ring handler (performs I/O boundary).

   The middleware reads the user's preferred language from the session,
   falls back through tenant locale, then the configured default locale.

   Injects into request:
     :i18n/locale-chain - ordered vector of locale keywords, e.g. [:nl :en]
     :i18n/t            - translation function (key params? n?) → string"
  (:require [boundary.i18n.core.translate :as translate]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Locale resolution
;; =============================================================================

(defn- get-user-locale
  "Extract the user's preferred locale from the request session.

   Reads :language from the session user map, returns keyword if valid string.

   Args:
     request - Ring request map

   Returns:
     locale keyword (e.g. :nl) or nil"
  [request]
  (when-let [lang (get-in request [:session :user :language])]
    (when (and (string? lang) (seq lang))
      (keyword lang))))

(defn- get-tenant-locale
  "Extract tenant-configured locale from request (if present).

   Args:
     request - Ring request map

   Returns:
     locale keyword or nil"
  [request]
  (when-let [lang (get-in request [:tenant :default-language])]
    (when (and (string? lang) (seq lang))
      (keyword lang))))

(defn- build-locale-chain
  "Build ordered locale chain: user → tenant → default.

   Nil values are filtered out. :en is always appended as ultimate fallback.

   Args:
     user-locale   - keyword or nil
     tenant-locale - keyword or nil
     default       - keyword (config default, e.g. :en)

   Returns:
     Vector of distinct locale keywords, e.g. [:nl :en]"
  [user-locale tenant-locale default]
  (into [] (distinct (filter identity [user-locale tenant-locale default :en]))))

;; =============================================================================
;; Middleware
;; =============================================================================

(defn wrap-i18n
  "Ring middleware that injects i18n context into each request.

   Adds to request:
     :i18n/locale-chain - vector of locale keywords to try in order
     :i18n/t            - fn (key) or (key params) or (key params n) → string

   Args:
     handler - Ring handler
     opts    - map with:
       :catalogue     - loaded catalogue (map or ICatalogue)
       :default-locale - keyword, e.g. :en
       :dev?           - boolean, enables debug markers (optional)

   Returns:
     Ring middleware function"
  [handler {:keys [catalogue default-locale dev?]
            :or   {default-locale :en}}]
  (fn [request]
    (let [user-locale   (get-user-locale request)
          tenant-locale (get-tenant-locale request)
          locale-chain  (build-locale-chain user-locale tenant-locale default-locale)
          t-fn          (fn
                          ([key]
                           (translate/t catalogue locale-chain key))
                          ([key params]
                           (translate/t catalogue locale-chain key params))
                          ([key params n]
                           (translate/t catalogue locale-chain key params n)))
          enriched      (-> request
                            (assoc :i18n/locale-chain locale-chain)
                            (assoc :i18n/t t-fn))]
      (when dev?
        (log/debug "i18n middleware" {:locale-chain locale-chain}))
      (handler enriched))))
