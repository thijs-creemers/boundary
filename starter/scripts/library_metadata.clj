(ns library-metadata
  "Metadata for all Boundary libraries - dependencies, conflicts, descriptions"
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(def libraries
  {:core
   {:id :core
    :name "boundary-core"
    :description "Foundation: validation, utilities, interceptor pipeline, feature flags"
    :category :foundation
    :required true
    :depends-on []
    :git-path "libs/core"
    :config-sections []
    :config-template {}}

   :observability
   {:id :observability
    :name "boundary-observability"
    :description "Logging, metrics, error reporting (Datadog, Sentry)"
    :category :foundation
    :required true
    :depends-on [:core]
    :git-path "libs/observability"
    :config-sections [:observability]
    :config-template {:observability {:enabled true :level :info}}}

   :platform
   {:id :platform
    :name "boundary-platform"
    :description "HTTP, database, CLI infrastructure"
    :category :foundation
    :required true
    :depends-on [:core :observability]
    :git-path "libs/platform"
    :config-sections [:http :db :cli]
    :config-template {:http {:enabled true :port 3000 :host "0.0.0.0"}
                      :db {:enabled true :type :sqlite :path "dev-database.db"}
                      :cli {:enabled true}}}

   :user
   {:id :user
    :name "boundary-user"
    :description "Authentication, authorization, MFA, session management"
    :category :auth
    :required false
    :depends-on [:core :observability :platform]
    :git-path "libs/user"
    :config-sections [:auth]
    :config-template {:auth {:enabled true :jwt-secret :env/JWT_SECRET}}}

   :admin
   {:id :admin
    :name "boundary-admin"
    :description "Auto-generated CRUD admin UI (Hiccup + HTMX)"
    :category :ui
    :required false
    :depends-on [:core :observability :platform :user]
    :git-path "libs/admin"
    :config-sections [:admin]
    :config-template {:admin {:enabled true :path "/admin"}}}

   :storage
   {:id :storage
    :name "boundary-storage"
    :description "File storage: local filesystem and S3"
    :category :feature
    :required false
    :depends-on [:core :observability :platform]
    :git-path "libs/storage"
    :config-sections [:storage]
    :config-template {:storage {:enabled true :provider :local :local-path "uploads"}}}

   :cache
   {:id :cache
    :name "boundary-cache"
    :description "Distributed caching: Redis and in-memory"
    :category :feature
    :required false
    :depends-on [:core :observability :platform]
    :git-path "libs/cache"
    :config-sections [:cache]
    :config-template {:cache {:enabled true :provider :in-memory :ttl-seconds 3600}}}

   :jobs
   {:id :jobs
    :name "boundary-jobs"
    :description "Background job processing with retry logic"
    :category :feature
    :required false
    :depends-on [:core :observability :platform]
    :git-path "libs/jobs"
    :config-sections [:jobs]
    :config-template {:jobs {:enabled true :worker-count 4 :queue-name "boundary-jobs"}}}

   :email
   {:id :email
    :name "boundary-email"
    :description "Production-ready email: SMTP, async, jobs integration"
    :category :feature
    :required false
    :depends-on [:core :observability :platform]
    :git-path "libs/email"
    :config-sections [:email]
    :config-template {:email {:enabled true :provider :smtp
                              :smtp-host :env/SMTP_HOST
                              :smtp-port :env/SMTP_PORT
                              :from-address :env/EMAIL_FROM}}}

   :tenant
   {:id :tenant
    :name "boundary-tenant"
    :description "Multi-tenancy with PostgreSQL schema-per-tenant isolation"
    :category :feature
    :required false
    :depends-on [:core :observability :platform]
    :conflicts [:cache]  ;; tenant-scoped cache requires special setup
    :git-path "libs/tenant"
    :config-sections [:tenant]
    :config-template {:tenant {:enabled true :strategy :schema :auto-provision true}}}

   :realtime
   {:id :realtime
    :name "boundary-realtime"
    :description "WebSocket / SSE for real-time features"
    :category :feature
    :required false
    :depends-on [:core :observability :platform]
    :git-path "libs/realtime"
    :config-sections [:realtime]
    :config-template {:realtime {:enabled true}}}

   :workflow
   {:id :workflow
    :name "boundary-workflow"
    :description "Declarative state machine workflows with audit trail"
    :category :feature
    :required false
    :depends-on [:core :observability :platform]
    :git-path "libs/workflow"
    :config-sections [:workflow]
    :config-template {:workflow {:enabled true}}}

   :search
   {:id :search
    :name "boundary-search"
    :description "Full-text search: PostgreSQL FTS with LIKE fallback"
    :category :feature
    :required false
    :depends-on [:core :observability :platform]
    :git-path "libs/search"
    :config-sections [:search]
    :config-template {:search {:enabled true}}}

   :external
   {:id :external
    :name "boundary-external"
    :description "External service adapters: Stripe, Twilio, IMAP"
    :category :feature
    :required false
    :depends-on [:core :observability :platform]
    :git-path "libs/external"
    :config-sections [:external]
    :config-template {:external {:enabled true}}}

   :reports
   {:id :reports
    :name "boundary-reports"
    :description "PDF, Excel, and Word (DOCX) generation via defreport"
    :category :feature
    :required false
    :depends-on [:core :observability :platform]
    :git-path "libs/reports"
    :config-sections [:reports]
    :config-template {:reports {:enabled true}}}

   :calendar
   {:id :calendar
    :name "boundary-calendar"
    :description "Recurring events, iCal export/import, conflict detection"
    :category :feature
    :required false
    :depends-on [:core :observability :platform]
    :git-path "libs/calendar"
    :config-sections [:calendar]
    :config-template {:calendar {:enabled true}}}

   :geo
   {:id :geo
    :name "boundary-geo"
    :description "Geocoding (OSM/Google/Mapbox), DB cache, Haversine distance"
    :category :feature
    :required false
    :depends-on [:core :observability :platform]
    :git-path "libs/geo"
    :config-sections [:geo]
    :config-template {:geo {:enabled true}}}})

(defn get-all-libraries
  "Return all library definitions"
  []
  libraries)

(defn get-library
  "Get library metadata by ID"
  [lib-id]
  (get libraries lib-id))

(defn get-required-libraries
  "Return list of required library IDs"
  []
  (->> libraries
       (filter (fn [[_ lib]] (:required lib)))
       (map first)
       set))

(defn get-optional-libraries
  "Return list of optional library IDs"
  []
  (->> libraries
       (filter (fn [[_ lib]] (not (:required lib))))
       (map first)
       set))

(defn get-libraries-by-category
  "Get all libraries in a category"
  [category]
  (->> libraries
       (filter (fn [[_ lib]] (= category (:category lib))))
       (map first)))

(defn resolve-dependencies
  "Given a set of selected library IDs, return full set including dependencies"
  [selected-libs]
  (loop [to-process selected-libs
         resolved #{}]
    (if (empty? to-process)
      resolved
      (let [lib-id (first to-process)
            lib (get-library lib-id)
            deps (:depends-on lib)
            new-deps (remove resolved deps)
            new-resolved (conj resolved lib-id)]
        (recur (concat (rest to-process) new-deps)
               new-resolved)))))

(defn check-conflicts
  "Check if selected libraries have conflicts. Returns vector of conflict messages."
  [selected-libs]
  (let [libs-with-conflicts (filter #(contains? (get-library %) :conflicts) selected-libs)]
    (mapcat (fn [lib-id]
              (let [lib (get-library lib-id)
                    conflicts (:conflicts lib)
                    conflicting (filter #(contains? selected-libs %) conflicts)]
                (map (fn [conflict-id]
                       {:lib lib-id
                        :conflicts-with conflict-id
                        :message (str (:name lib) " conflicts with " (:name (get-library conflict-id)))})
                     conflicting)))
            libs-with-conflicts)))

(defn visualize-dependency-tree
  "Generate ASCII tree visualization of library dependencies.
   Shows which libraries were auto-added and highlights them."
  [selected-libs resolved-libs]
  (let [required (get-required-libraries)
        user-selected (set/difference selected-libs required)
        auto-added (set/difference resolved-libs selected-libs required)

        ;; Build dependency tree structure
        build-tree (fn build-tree [lib-id indent visited]
                     (if (contains? visited lib-id)
                       []
                       (let [lib (get-library lib-id)
                             deps (:depends-on lib)
                             is-auto (contains? auto-added lib-id)
                             is-user (contains? user-selected lib-id)
                             is-required (contains? required lib-id)
                             label (str (:name lib)
                                        (cond
                                          is-user " (selected)"
                                          is-auto " (auto-added)"
                                          is-required " (required)"
                                          :else ""))]
                         (cons
                          {:indent indent :label label :lib-id lib-id}
                          (mapcat #(build-tree % (str indent "│  ") (conj visited lib-id))
                                  deps)))))

        ;; Start from user-selected libraries
        trees (mapcat #(build-tree % "" #{}) (sort user-selected))]

    (when (seq trees)
      (doseq [{:keys [indent label]} trees]
        (println (str indent label))))))

(defn print-dependency-summary
  "Print summary of selected libraries with dependency resolution.
   Shows foundation, user-selected, and auto-added libraries."
  [selected-libs resolved-libs]
  (let [required (get-required-libraries)
        user-selected (set/difference selected-libs required)
        auto-added (set/difference resolved-libs selected-libs required)]

    (println)
    (println "Library Summary:")
    (println)

    ;; Foundation (always included)
    (println "  Foundation (always included):")
    (doseq [lib-id (sort required)]
      (println (str "    - " (:name (get-library lib-id)))))
    (println)

    ;; User selected
    (when (seq user-selected)
      (println "  Selected by you:")
      (doseq [lib-id (sort user-selected)]
        (println (str "    - " (:name (get-library lib-id)))))
      (println))

    ;; Auto-added
    (when (seq auto-added)
      (println "  Auto-added (dependencies):")
      (doseq [lib-id (sort auto-added)]
        (println (str "    - " (:name (get-library lib-id)))))
      (println))

    (println (str "  Total: " (count resolved-libs) " libraries"))
    (println)))

(defn generate-dependency-summary
  "Generate dependency summary as string (for testing).
   Returns formatted string showing foundation, selected, and auto-added libraries."
  [selected-libs]
  (let [resolved-libs (resolve-dependencies selected-libs)
        required (get-required-libraries)
        user-selected (set/difference selected-libs required)
        auto-added (set/difference resolved-libs selected-libs required)]

    (str
     "Library Summary:\n\n"
     "  Foundation (always included):\n"
     (str/join "\n"
                          (map #(str "    - " (:name (get-library %))) (sort required)))
     "\n\n"
     (when (seq user-selected)
       (str "  Selected by you:\n"
            (str/join "\n"
                                 (map #(str "    - " (:name (get-library %))) (sort user-selected)))
            "\n\n"))
     (when (seq auto-added)
       (str "  Auto-added (dependencies):\n"
            (str/join "\n"
                                 (map #(str "    - " (:name (get-library %))) (sort auto-added)))
            "\n\n"))
     "  Total: " (count resolved-libs) " libraries\n")))

(defn generate-dependency-tree
  "Generate ASCII dependency tree as string (for testing).
   Shows library dependencies with visual tree structure."
  [selected-libs]
  (let [resolved-libs (resolve-dependencies selected-libs)
        required (get-required-libraries)
        user-selected (set/difference selected-libs required)
        auto-added (set/difference resolved-libs selected-libs required)

        ;; Build dependency tree structure
        build-tree (fn build-tree [lib-id indent visited]
                     (if (contains? visited lib-id)
                       []
                       (let [lib (get-library lib-id)
                             deps (:depends-on lib)
                             is-auto (contains? auto-added lib-id)
                             is-user (contains? user-selected lib-id)
                             is-required (contains? required lib-id)
                             label (str (:name lib)
                                        (cond
                                          is-user " (selected)"
                                          is-auto " (auto-added)"
                                          is-required " (required)"
                                          :else ""))]
                         (cons
                          {:indent indent :label label :lib-id lib-id}
                          (mapcat #(build-tree % (str indent "│  ") (conj visited lib-id))
                                  deps)))))

        ;; Start from user-selected libraries
        trees (mapcat #(build-tree % "" #{}) (sort user-selected))]

    (str/join "\n" (map #(str (:indent %) (:label %)) trees))))
