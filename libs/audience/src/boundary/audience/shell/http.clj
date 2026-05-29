(ns boundary.audience.shell.http
  "HTTP handlers and route definitions for audience management.

   Web Pages (mounted under /web/audiences):
     GET    /audiences               — list all segments
     GET    /audiences/builder       — new segment form
     GET    /audiences/builder/:id   — edit existing segment

   API Endpoints (mounted under /api/audiences):
     POST   /audiences               — create audience
     PUT    /audiences/:id           — update audience
     DELETE /audiences/:id           — delete audience
     POST   /audiences/preview       — returns count + sample
     POST   /audiences/:id/evaluate  — trigger evaluation
     GET    /audiences/:id/members   — list members"
  (:require [boundary.audience.core.ui :as ui]
            [boundary.audience.ports :as ports]
            [clojure.tools.logging :as log]
            [hiccup2.core :as h])
  (:import [java.util UUID]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- parse-uuid-param
  [s param-name]
  (try
    (UUID/fromString s)
    (catch IllegalArgumentException _
      (throw (ex-info (str "Invalid UUID for " param-name)
                      {:type    :validation-error
                       :field   param-name
                       :value   s
                       :message (str param-name " must be a valid UUID")})))))

(defn- html-response
  ([hiccup]
   (html-response hiccup 200))
  ([hiccup status]
   {:status  status
    :headers {"Content-Type" "text/html; charset=utf-8"}
    :body    (str (h/html hiccup))}))

(defn- json-response
  ([body]
   (json-response body 200))
  ([body status]
   {:status  status
    :headers {"Content-Type" "application/json"}
    :body    body}))

;; =============================================================================
;; Web handlers
;; =============================================================================

(defn list-audiences-handler
  "GET /web/audiences — lists all segments."
  [_resolver store request]
  (log/debug "Listing audiences")
  (let [segments (ports/list-audiences store)]
    (html-response
     [:div.min-h-screen.bg-gray-50.p-6
      [:div.max-w-4xl.mx-auto
       [:div.flex.items-center.justify-between.mb-6
        [:h1.text-2xl.font-bold "Audiences"]
        [:a.rounded-md.bg-blue-600.px-4.py-2.text-white.text-sm
         {:href "/web/audiences/builder"} "New Audience"]]
       (if (seq segments)
         (ui/segment-list segments)
         [:p.text-gray-500 "No audience segments defined yet."])]])))

(defn builder-page-handler
  "GET /web/audiences/builder — new segment form."
  [_resolver _store _request]
  (log/debug "Rendering new audience builder page")
  (html-response (ui/builder-layout {})))

(defn builder-edit-handler
  "GET /web/audiences/builder/:id — edit existing segment."
  [_resolver store request]
  (let [id      (parse-uuid-param (get-in request [:path-params :id]) "id")
        segment (ports/find-audience store id)]
    (if segment
      (html-response (ui/builder-layout {:segment segment}))
      {:status  404
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body    (str (h/html [:div [:p "Audience not found."]]))})))

;; =============================================================================
;; API handlers
;; =============================================================================

(defn create-audience-handler
  "POST /api/audiences — create a new audience definition."
  [_resolver store request]
  (let [body       (get-in request [:parameters :body] {})
        definition (cond-> body
                     (:filtersData body) (assoc :filters (:filtersData body)))]
    (log/info "Creating audience" {:label (:label definition)})
    (let [saved (ports/save-audience store definition)]
      (json-response {:id    (str (:id saved))
                      :label (:label saved)}
                     201))))

(defn update-audience-handler
  "PUT /api/audiences/:id — update an existing audience definition."
  [_resolver store request]
  (let [id      (parse-uuid-param (get-in request [:path-params :id]) "id")
        body    (get-in request [:parameters :body] {})
        updated (ports/save-audience store (assoc body :id id))]
    (log/info "Updating audience" {:id id})
    (json-response {:id    (str (:id updated))
                    :label (:label updated)})))

(defn delete-audience-handler
  "DELETE /api/audiences/:id — delete an audience definition."
  [_resolver store request]
  (let [id (parse-uuid-param (get-in request [:path-params :id]) "id")]
    (log/info "Deleting audience" {:id id})
    (ports/delete-audience store id)
    {:status 204 :body nil}))

(defn preview-audience-handler
  "POST /api/audiences/preview — evaluate filters and return count + sample."
  [resolver _store request]
  (let [body        (get-in request [:parameters :body] {})
        audience-id (keyword (get body :audienceId "preview"))]
    (log/debug "Previewing audience" {:audience-id audience-id})
    (let [result (ports/resolve-audience resolver audience-id {:force-refresh? true})]
      (json-response {:count  (:count result)
                      :sample (take 10 (:user-ids result))}))))

(defn evaluate-audience-handler
  "POST /api/audiences/:id/evaluate — trigger full evaluation and cache."
  [resolver _store request]
  (let [id          (parse-uuid-param (get-in request [:path-params :id]) "id")
        audience-id (keyword (str id))]
    (log/info "Evaluating audience" {:id id})
    (let [result (ports/resolve-audience resolver audience-id {:force-refresh? true})]
      (json-response {:count       (:count result)
                      :cachedAt    (str (:evaluated-at result))
                      :cached      (:cached? result)}))))

(defn list-members-handler
  "GET /api/audiences/:id/members — list member user-ids for an audience."
  [resolver _store request]
  (let [id          (parse-uuid-param (get-in request [:path-params :id]) "id")
        audience-id (keyword (str id))]
    (log/debug "Listing members" {:id id})
    (let [result (ports/resolve-audience resolver audience-id)]
      (json-response {:count   (:count result)
                      :userIds (mapv str (:user-ids result))}))))

;; =============================================================================
;; Route definitions
;; =============================================================================

(defn audience-web-routes
  "Return normalized route definitions for the audience web pages.

   Routes will be mounted under /web by the HTTP handler.

   Args:
     resolver - IAudienceResolver
     store    - IAudienceRepository

   Returns:
     Vector of normalized route maps"
  [resolver store]
  [{:path    "/audiences"
    :methods {:get {:handler (fn [req] (list-audiences-handler resolver store req))
                    :summary "List audience segments"}}}
   {:path    "/audiences/builder"
    :methods {:get {:handler (fn [req] (builder-page-handler resolver store req))
                    :summary "New audience builder page"}}}
   {:path    "/audiences/builder/:id"
    :methods {:get {:handler (fn [req] (builder-edit-handler resolver store req))
                    :summary "Edit audience builder page"}}}])

(defn audience-api-routes
  "Return normalized route definitions for the audience API.

   Routes will be mounted under /api by the HTTP handler.

   Args:
     resolver - IAudienceResolver
     store    - IAudienceRepository

   Returns:
     Vector of normalized route maps"
  [resolver store]
  [{:path    "/audiences"
    :methods {:post {:handler (fn [req] (create-audience-handler resolver store req))
                     :summary "Create audience segment"}}}
   {:path    "/audiences/preview"
    :methods {:post {:handler (fn [req] (preview-audience-handler resolver store req))
                     :summary "Preview audience filter results"}}}
   {:path    "/audiences/:id"
    :methods {:put    {:handler (fn [req] (update-audience-handler resolver store req))
                       :summary "Update audience segment"}
              :delete {:handler (fn [req] (delete-audience-handler resolver store req))
                       :summary "Delete audience segment"}}}
   {:path    "/audiences/:id/evaluate"
    :methods {:post {:handler (fn [req] (evaluate-audience-handler resolver store req))
                     :summary "Evaluate and cache audience"}}}
   {:path    "/audiences/:id/members"
    :methods {:get {:handler (fn [req] (list-members-handler resolver store req))
                    :summary "List audience member user-ids"}}}])
