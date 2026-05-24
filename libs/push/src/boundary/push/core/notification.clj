(ns boundary.push.core.notification
  "Push notification definitions, registry, and template rendering."
  (:require [clojure.string :as str]))

;; ===== Registry =====

(defonce ^:private registry-atom (atom {}))

(defn register-push! [definition]
  (swap! registry-atom assoc (:id definition) definition)
  definition)

(defn get-push [id]
  (get @registry-atom id))

(defn list-pushes []
  (vec (keys @registry-atom)))

(defn clear-registry! []
  (reset! registry-atom {}))

(defmacro defpush
  "Define and register a push notification type."
  [sym definition-map]
  `(do
     (def ~sym ~definition-map)
     (register-push! ~sym)
     ~sym))

;; ===== Template Rendering =====

(defn render-template
  "Interpolate {{var}} placeholders with data map values."
  [template data]
  (reduce-kv
   (fn [s k v]
     (str/replace s (str "{{" (name k) "}}") (str v)))
   template
   data))

(defn resolve-content
  "Resolve localized content. Fallback: requested -> :en -> first available."
  [content locale]
  (cond
    (string? content) content
    (map? content)    (or (get content locale)
                          (get content :en)
                          (first (vals content)))))

(defn build-notification
  "Pure: resolve locale + render templates into ready-to-send map."
  [push-def data locale]
  {:title        (render-template (resolve-content (:title push-def) locale) data)
   :body         (render-template (resolve-content (:body push-def) locale) data)
   :deep-link    (some-> (:deep-link push-def) (render-template data))
   :priority     (:priority push-def :normal)
   :ttl          (:ttl push-def 86400)
   :silent?      (:silent? push-def false)
   :collapse-key (:collapse-key push-def)
   :data         data})
