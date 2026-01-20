(ns boundary.app
  (:require
    [integrant.core :as ig]
    [reitit.ring :as ring]
    [hiccup.core :as hiccup]
    [ring.adapter.jetty :as jetty]
    [ring.util.response :as resp]))

;; Simple SSR page using Hiccup
(defn home-page []
  (hiccup/html
    [:html
     [:head
      [:meta {:charset "utf-8"}]
      [:title "Boundary Starter"]
      [:link {:rel "stylesheet" :href "/css/app.css"}]]
     [:body
      [:main
       [:h1 "Boundary Starter"]
       [:p "Batteries included, boundaries enforced."]
       [:p "This is a minimal server-rendered page using Hiccup."]
       [:a {:href "/admin"} "Go to Admin"]]]]))

(defn admin-page [entities]
  (hiccup/html
    [:html
     [:head
      [:meta {:charset "utf-8"}]
      [:title "Admin"]
      [:link {:rel "stylesheet" :href "/css/app.css"}]]
     [:body
      [:main
       [:h1 "Admin"]
       [:p "Configured entities:"]
       [:ul
        (for [[k v] entities]
          [:li (str (name k) " — " (:label v))])]
       [:a {:href "/"} "Back to Home"]]]]))

(defn routes [entities]
  (ring/ring-handler
    (ring/router
      [["/" {:get (fn [_] (-> (home-page) resp/response (resp/content-type "text/html")))}]
       ["/admin" {:get (fn [_] (-> (admin-page entities) resp/response (resp/content-type "text/html")))}]])
    (ring/create-default-handler)))

(defmethod ig/init-key :boundary/server [_ {:keys [port admin]}]
  (let [entities (:entities admin)
        handler (routes entities)
        server (jetty/run-jetty handler {:port (or port 3000) :join? false})]
    (println (str "Boundary starter running on http://localhost:" (or port 3000)))
    server))

(defmethod ig/halt-key! :boundary/server [_ server]
  (.stop server))