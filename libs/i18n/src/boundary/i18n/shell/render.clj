(ns boundary.i18n.shell.render
  "Hiccup postwalk renderer that resolves [:t ...] markers to strings.

   SHELL FUNCTION: calls hiccup2.core/html (I/O boundary for HTML output).

   Marker syntax:
     [:t :key]                  → (t-fn :key)
     [:t :key {:param val}]     → (t-fn :key {:param val})
     [:t :key {:n 3} 3]         → (t-fn :key {:n 3} 3)
     [:t :key nil 3]            → (t-fn :key {} 3)

   Dev mode wraps each resolved marker in:
     [:span {:data-i18n (str key)} translated-string]"
  (:require [clojure.walk :as walk]
            [hiccup2.core :as h]))

;; =============================================================================
;; Marker detection
;; =============================================================================

(defn- i18n-marker?
  "Returns true if node is an i18n translation marker [:t :key ...]."
  [node]
  (and (vector? node)
       (= :t (first node))
       (keyword? (second node))))

;; =============================================================================
;; Marker resolution
;; =============================================================================

(defn- resolve-marker
  "Resolve a single [:t ...] marker to a string using t-fn.

   Args:
     node - vector of the form [:t key] or [:t key params] or [:t key params n]
     t-fn - translation function (key params? n?) → string
     dev? - if true, wrap in [:span {:data-i18n ...}]

   Returns:
     String (or dev-mode span vector)"
  [node t-fn dev?]
  (let [[_ key params n] node
        params (or params {})
        result (if n
                 (t-fn key params n)
                 (if (seq params)
                   (t-fn key params)
                   (t-fn key)))]
    (if dev?
      [:span {:data-i18n (str key)} result]
      result)))

;; =============================================================================
;; Postwalk
;; =============================================================================

(defn resolve-markers
  "Walk a Hiccup tree and resolve all [:t ...] markers.

   Pure-ish function — does not call html, safe to use in core if t-fn is pure.

   Args:
     hiccup - Hiccup data structure (nested vectors/maps)
     t-fn   - translation function (key params? n?) → string
     opts   - (optional) map with :dev? boolean

   Returns:
     Hiccup structure with markers replaced by strings (or dev-mode spans)"
  ([hiccup t-fn]
   (resolve-markers hiccup t-fn {}))
  ([hiccup t-fn {:keys [dev?]}]
   (walk/postwalk
    (fn [node]
      (if (i18n-marker? node)
        (resolve-marker node t-fn dev?)
        node))
    hiccup)))

;; =============================================================================
;; Render
;; =============================================================================

(defn render
  "Resolve markers in a Hiccup tree and return an HTML string.

   Calls resolve-markers then hiccup2.core/html.

   Args:
     hiccup - Hiccup data structure
     t-fn   - translation function (key params? n?) → string
     opts   - (optional) map with :dev? boolean

   Returns:
     HTML string"
  ([hiccup t-fn]
   (render hiccup t-fn {}))
  ([hiccup t-fn opts]
   (if (string? hiccup)
     hiccup
     (str (h/html (resolve-markers hiccup t-fn opts))))))
