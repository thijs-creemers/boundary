(ns boundary.i18n.shell.catalogue
  "Translation catalogue loading from classpath EDN resources.

   SHELL FUNCTION: performs I/O (classpath resource loading).

   The catalogue is a nested map:
     {locale-keyword {translation-key string-or-plural-map}}

   Example:
     {:en {:user/sign-in \"Sign in\"
           :user/greeting {:one \"Hello {name}\" :many \"Hello everyone\"}}
      :nl {:user/sign-in \"Aanmelden\"
           :user/greeting {:one \"Hallo {name}\" :many \"Hallo iedereen\"}}}"
  (:require [boundary.i18n.ports :as ports]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; EDN loading
;; =============================================================================

(defn- load-locale-edn
  "Load a single locale EDN file from the classpath.

   Args:
     base-path - classpath base directory, e.g. \"boundary/i18n/translations\"
     locale    - keyword, e.g. :en

   Returns:
     Map of {key string-or-plural-map}, or nil if not found"
  [base-path locale]
  (let [resource-path (str base-path "/" (name locale) ".edn")
        resource      (io/resource resource-path)]
    (when resource
      (log/debug "Loading i18n catalogue" {:locale locale :path resource-path})
      (edn/read-string (slurp resource)))))

(defn load-catalogue
  "Load translation catalogue from classpath EDN files.

   Discovers locales by attempting to load known locale files.
   Each locale file must be at: {base-path}/{locale}.edn

   Args:
     base-path - classpath directory, e.g. \"boundary/i18n/translations\"
     locales   - (optional) seq of locale keywords to load; defaults to [:en :nl]

   Returns:
     Nested map {locale-keyword {key string-or-plural-map}}"
  ([base-path]
   (load-catalogue base-path [:en :nl]))
  ([base-path locales]
   (reduce (fn [acc locale]
             (if-let [entries (load-locale-edn base-path locale)]
               (assoc acc locale entries)
               acc))
           {}
           locales)))

;; =============================================================================
;; MapCatalogue — ICatalogue implementation backed by an in-memory map
;; =============================================================================

(defrecord MapCatalogue [data]
  ports/ICatalogue
  (lookup [_ locale key]
    (get-in data [locale key]))
  (available-locales [_]
    (set (keys data))))

(defn create-map-catalogue
  "Create an ICatalogue backed by the given nested map.

   Args:
     data - map of {locale-keyword {key string-or-plural-map}}

   Returns:
     MapCatalogue record implementing ICatalogue"
  [data]
  (->MapCatalogue data))
