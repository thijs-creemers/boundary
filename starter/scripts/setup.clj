#!/usr/bin/env bb
;; starter/scripts/setup.clj
;;
;; Interactive project setup wizard for Boundary Framework.
;;
;; Usage (via bb.edn task):
;;   bb setup                    -- interactive wizard
;;   bb setup --help             -- show help
;;
;; Usage (direct):
;;   bb scripts/setup.clj

(ns setup
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.set :as set]))

;; Load helper scripts
(load-file "scripts/helpers.clj")
(load-file "scripts/file_generators.clj")
(load-file "scripts/library_metadata.clj")
(require '[helpers :as helpers]
         '[file-generators :as file-generators]
         '[library-metadata :as library-metadata])

;; =============================================================================
;; ANSI Color Helpers
;; =============================================================================

(defn bold   [s] (str "\033[1m"  s "\033[0m"))
(defn green  [s] (str "\033[32m" s "\033[0m"))
(defn cyan   [s] (str "\033[36m" s "\033[0m"))
(defn red    [s] (str "\033[31m" s "\033[0m"))
(defn yellow [s] (str "\033[33m" s "\033[0m"))
(defn dim    [s] (str "\033[2m"  s "\033[0m"))
(defn blue   [s] (str "\033[34m" s "\033[0m"))

;; =============================================================================
;; Validation Helpers
;; =============================================================================

(defn valid-project-name?
  "Project name must be kebab-case: lowercase letters, numbers, hyphens only.
   Must start with a letter."
  [s]
  (boolean (and (seq s) (re-matches #"^[a-z][a-z0-9-]*$" s))))

(defn valid-directory?
  "Check if directory path is valid and writable."
  [path]
  (let [dir (io/file path)]
    (or (.exists dir)
        ;; Check if parent exists and is writable
        (let [parent (.getParentFile dir)]
          (and parent (.exists parent) (.canWrite parent))))))

;; =============================================================================
;; Input Prompts
;; =============================================================================

(defn prompt
  "Print label with optional default hint, return trimmed input (or default on blank)."
  ([label] (prompt label nil))
  ([label default]
   (if default
     (print (str label " [" (dim default) "]: "))
     (print (str label ": ")))
   (flush)
   (let [input (str/trim (or (read-line) ""))]
     (if (and (empty? input) default)
       default
       input))))

(defn confirm
  "Y/n or y/N prompt. Returns boolean."
  ([label] (confirm label true))
  ([label default-yes?]
   (let [hint (if default-yes? "Y/n" "y/N")]
     (print (str label " [" hint "]: "))
     (flush)
     (let [input (str/trim (str/lower-case (or (read-line) "")))]
       (if (empty? input)
         default-yes?
         (= input "y"))))))

;; =============================================================================
;; Template Definitions
;; =============================================================================

(def templates
  [{:id :minimal
    :name "Minimal"
    :description "Bare minimum - HTTP server, database, health checks"
    :features ["HTTP server" "Database (SQLite/PostgreSQL)" "Health check endpoint" "REPL development"]
    :libs 3
    :use-case "Learning Boundary, quick prototypes, minimal apps"
    :recommended-for [:learning :prototype :minimal]}

   {:id :api-only
    :name "API-Only"
    :description "RESTful JSON API with JWT auth - no web UI"
    :features ["Everything in Minimal" "Stateless JWT authentication" "API key management" "CORS configuration" "Rate limiting" "JSON-only responses"]
    :libs 4
    :use-case "Mobile backends, microservices, third-party integrations"
    :recommended-for [:api :mobile :microservice :backend]}

   {:id :microservice
    :name "Microservice"
    :description "Lightweight containerized service - health checks, metrics, optional database"
    :features ["Health checks (/health, /ready)" "Prometheus metrics (/metrics)" "12-factor app design" "Container-ready (Dockerfile)" "Optional database" "Graceful shutdown" "Distributed tracing ready"]
    :libs 3
    :use-case "Internal services, worker services, sidecars, stateless processing"
    :recommended-for [:container :kubernetes :internal :stateless]}

   {:id :web-app
    :name "Web Application"
    :description "Authentication + admin interface for content-driven apps"
    :features ["Everything in Minimal" "JWT authentication" "Session management" "MFA support" "Auto-generated admin UI"]
    :libs 5
    :use-case "CMS, blogs, internal tools, admin dashboards"
    :recommended-for [:auth :admin :cms]}

   {:id :saas
    :name "SaaS Application"
    :description "Production-ready multi-tenant SaaS with all features"
    :features ["Everything in Web-App" "Multi-tenancy" "Background jobs" "Email (SMTP)" "File storage (S3)" "Distributed caching (Redis)"]
    :libs 10
    :use-case "Production SaaS products, B2B platforms, enterprise apps"
    :recommended-for [:production :saas :enterprise]}

   {:id :custom
    :name "Custom"
    :description "Select individual Boundary libraries (interactive)"
    :features ["Choose exactly what you need" "Auto-resolve dependencies" "Conflict detection"]
    :libs "?"
    :use-case "Custom projects with specific requirements"
    :recommended-for [:custom :advanced]}])

;; =============================================================================
;; Database Options
;; =============================================================================

(def database-options
  [{:id :sqlite
    :name "SQLite"
    :description "Zero-config embedded database - perfect for development"
    :features ["No setup required" "Single file database" "Great for development"]
    :recommended-for [:development :prototype :learning]}

   {:id :postgres
    :name "PostgreSQL"
    :description "Production-grade relational database"
    :features ["Robust and battle-tested" "Full SQL support" "Great for production"]
    :recommended-for [:production :multi-user]}

   {:id :both
    :name "Both (SQLite + PostgreSQL)"
    :description "SQLite for dev, PostgreSQL for production"
    :features ["Best of both worlds" "Easy local dev" "Production-ready deployment"]
    :recommended-for [:full-stack :flexible]}])

;; =============================================================================
;; Library Selection (for Custom Template)
;; =============================================================================

(defn display-library-selection []
  (println)
  (println (bold "Select Boundary Libraries:"))
  (println)

  ;; Build lookup table for numbering
  (let [_optional-libs (concat
                        (library-metadata/get-libraries-by-category :auth)
                        (library-metadata/get-libraries-by-category :ui)
                        (sort (library-metadata/get-libraries-by-category :feature)))]

    ;; Foundation (required, grayed out)
    (println (dim "  Foundation (required):"))
    (doseq [lib-id (library-metadata/get-libraries-by-category :foundation)]
      (let [lib (library-metadata/get-library lib-id)]
        (println (dim (str "    [✓] " (:name lib) " - " (:description lib))))))
    (println)

    ;; Auth
    (let [auth-libs (library-metadata/get-libraries-by-category :auth)
          start-idx 0]
      (when (seq auth-libs)
        (println "  Authentication:")
        (doseq [[idx lib-id] (map-indexed vector auth-libs)]
          (let [lib (library-metadata/get-library lib-id)
                num (+ idx start-idx 1)]
            (println (str "    " (bold (str num ".")) " " (:name lib) " - " (:description lib)))))
        (println)))

    ;; UI
    (let [ui-libs (library-metadata/get-libraries-by-category :ui)
          start-idx (count (library-metadata/get-libraries-by-category :auth))]
      (when (seq ui-libs)
        (println "  User Interface:")
        (doseq [[idx lib-id] (map-indexed vector ui-libs)]
          (let [lib (library-metadata/get-library lib-id)
                num (+ idx start-idx 1)]
            (println (str "    " (bold (str num ".")) " " (:name lib) " - " (:description lib)))))
        (println)))

    ;; Features
    (let [feature-libs (sort (library-metadata/get-libraries-by-category :feature))
          start-idx (+ (count (library-metadata/get-libraries-by-category :auth))
                       (count (library-metadata/get-libraries-by-category :ui)))]
      (when (seq feature-libs)
        (println "  Features:")
        (doseq [[idx lib-id] (map-indexed vector feature-libs)]
          (let [lib (library-metadata/get-library lib-id)
                num (+ idx start-idx 1)]
            (println (str "    " (bold (str num ".")) " " (:name lib) " - " (:description lib)))))
        (println)))))

(defn select-libraries
  "Interactive multi-select library picker. Returns set of selected library IDs."
  []
  (display-library-selection)

  (println (bold "Enter library numbers to toggle (space-separated), or 'done' when finished:"))
  (println (dim "  Example: 1 2 5  (toggles libraries 1, 2, 5)"))
  (println (dim "  Type 'help' to see library list again"))
  (println (dim "  Type 'done' when you're ready to continue"))
  (println)

  (let [;; Build lookup table: index -> lib-id
        optional-libs (concat
                       (library-metadata/get-libraries-by-category :auth)
                       (library-metadata/get-libraries-by-category :ui)
                       (sort (library-metadata/get-libraries-by-category :feature)))
        lib-lookup (into {} (map-indexed (fn [idx lib-id] [(inc idx) lib-id]) optional-libs))]

    (loop [selected #{}]
      (println)
      (println (str "Currently selected: "
                    (if (empty? selected)
                      (dim "none")
                      (cyan (str/join ", " (map #(:name (library-metadata/get-library %)) selected))))))
      (print "> ")
      (flush)

      (let [input (str/trim (or (read-line) ""))]
        (cond
          ;; Done
          (= input "done")
          selected

          ;; Help - redisplay
          (= input "help")
          (do
            (display-library-selection)
            (recur selected))

          ;; Empty input
          (empty? input)
          (recur selected)

          ;; Toggle selections
          :else
          (let [numbers (try
                          (map #(Integer/parseInt %) (str/split input #"\s+"))
                          (catch Exception _
                            (println (red "Invalid input. Enter numbers like: 1 2 5"))
                            nil))]
            (if numbers
              (let [;; Toggle each number
                    new-selected (reduce
                                  (fn [sel num]
                                    (if-let [lib-id (get lib-lookup num)]
                                      (if (contains? sel lib-id)
                                        (do
                                          (println (yellow (str "  Removed: " (:name (library-metadata/get-library lib-id)))))
                                          (disj sel lib-id))
                                        (do
                                          (println (green (str "  Added: " (:name (library-metadata/get-library lib-id)))))
                                          (conj sel lib-id)))
                                      (do
                                        (println (red (str "  Invalid number: " num)))
                                        sel)))
                                  selected
                                  numbers)]
                (recur new-selected))
              (recur selected))))))))

(defn select-libraries-interactive
  "Interactive library selection with dependency resolution and conflict checking.
   Returns map with :selected (user picks) and :resolved (with dependencies)."
  []
  (loop []
    (let [;; User selection
          selected (select-libraries)

          ;; Resolve dependencies
          _ (println)
          _ (println (bold "Resolving dependencies..."))
          resolved (library-metadata/resolve-dependencies selected)

          ;; Calculate auto-added
          auto-added (set/difference resolved selected (library-metadata/get-required-libraries))

          ;; Check conflicts
          conflicts (library-metadata/check-conflicts resolved)]

      ;; Display resolution results
      (println)
      (when (seq auto-added)
        (println (cyan "Auto-added dependencies:"))
        (doseq [lib-id auto-added]
          (println (str "  + " (:name (library-metadata/get-library lib-id)))))
        (println))

      ;; Display conflicts (if any)
      (if (seq conflicts)
        (do
          (println (red (bold "⚠ Conflicts detected:")))
          (doseq [{:keys [message]} conflicts]
            (println (red (str "  - " message))))
          (println)
          (println (yellow "Please remove conflicting libraries and try again."))
          (println)
          ;; Restart selection
          (recur))

        ;; No conflicts - return
        {:selected selected
         :resolved resolved}))))

;; =============================================================================
;; Menu Selection
;; =============================================================================

(defn display-template-menu []
  (println)
  (println (bold "Available Templates:"))
  (println)
  (doseq [[idx {:keys [name description libs use-case]}] (map-indexed vector templates)]
    (println (str "  " (bold (str (inc idx) ".")) " " (cyan name) " " (dim (str "(" libs " libraries)"))))
    (println (str "     " description))
    (println (str "     " (dim "Use case: ") use-case))
    (when (< idx 2) (println))))

(defn display-database-menu []
  (println)
  (println (bold "Database Options:"))
  (println)
  (doseq [[idx {:keys [name description]}] (map-indexed vector database-options)]
    (println (str "  " (bold (str (inc idx) ".")) " " (cyan name)))
    (println (str "     " description))
    (when (< idx 2) (println))))

(defn select-from-menu
  "Print a menu and return the chosen item. Loops on invalid input."
  [items display-fn default-idx]
  (display-fn)
  (print (str "  Choice [" (bold (str (inc default-idx))) "]: "))
  (flush)
  (let [input  (str/trim (or (read-line) ""))
        choice (if (empty? input)
                 (inc default-idx)
                 (try (Integer/parseInt input) (catch Exception _ nil)))]
    (if (and choice (>= choice 1) (<= choice (count items)))
      (nth items (dec choice))
      (do
        (println (red (str "  Invalid choice, enter 1–" (count items))))
        (select-from-menu items display-fn default-idx)))))

;; =============================================================================
;; Summary Display
;; =============================================================================

(defn display-summary [project-name template-name db-name output-dir]
  (println)
  (println (cyan "┌─ Project Configuration ───────────────────────────────┐"))
  (println (str (cyan "│") " Project Name:  " (bold project-name)))
  (println (str (cyan "│") " Template:      " (bold template-name)))
  (println (str (cyan "│") " Database:      " (bold db-name)))
  (println (str (cyan "│") " Output Dir:    " (bold output-dir)))
  (println (cyan "└────────────────────────────────────────────────────────┘"))
  (println))

(defn display-success [output-dir template-id jwt-secret]
  (println)
  (println (green (bold "✓ Project generated successfully!")))
  (println)
  (println (bold "Next steps:"))
  (println)
  (println (str "  1. " (bold "cd ") output-dir))
  (println)
  (println "  2. Set environment variables:")
  (println (str "     " (cyan (str "export JWT_SECRET=\"" jwt-secret "\""))))
  (println (str "     " (cyan "export BND_ENV=development")))

  (when (= template-id :saas)
    (println)
    (println "  3. (Optional) Configure SaaS features:")
    (println (str "     " (dim "export REDIS_URL=redis://localhost:6379")))
    (println (str "     " (dim "export SMTP_HOST=smtp.example.com")))
    (println (str "     " (dim "# See .env.example for all variables"))))

  (println)
  (println (str "  " (if (= template-id :saas) "4" "3") ". Start the REPL:"))
  (println (str "     " (cyan "clojure -M:repl-clj")))
  (println)
  (println (str "  " (if (= template-id :saas) "5" "4") ". In the REPL, start the system:"))
  (println (str "     " (cyan "(require '[integrant.repl :as ig-repl])")))
  (println (str "     " (cyan "(ig-repl/go)")))
  (println)
  (println (str "  " (if (= template-id :saas) "6" "5") ". Visit " (blue "http://localhost:3000")))

  (when (#{:web-app :saas} template-id)
    (println (str "     Admin UI: " (blue "http://localhost:3000/admin"))))

  (println)
  (println (bold "Documentation:"))
  (println (str "  - README.md in " output-dir))
  (println "  - https://github.com/thijs-creemers/boundary")
  (println))

;; =============================================================================
;; Interactive Wizard
;; =============================================================================

(defn wizard-setup []
  (println)
  (println (bold "╭────────────────────────────────────────╮"))
  (println (bold "│  ⚡ Boundary Framework Setup Wizard  │"))
  (println (bold "╰────────────────────────────────────────╯"))
  (println)
  (println "  Create a new Boundary project in seconds!")
  (println)

  ;; 1. Select Template
  (let [template (select-from-menu templates display-template-menu 0)
        template-id (:id template)
        template-name (:name template)

        ;; Custom template path
        custom-libs (when (= template-id :custom)
                      ;; Offer to load saved template or create new
                      (let [saved (helpers/list-saved-templates)]
                        (if (seq saved)
                          (do
                            (println)
                            (println (bold "Template Management:"))
                            (println)
                            (println (bold "  Load Saved Templates:"))
                            (doseq [[idx {:keys [name created-at updated-at]}] (map-indexed vector saved)]
                              (let [date-info (if updated-at
                                                (str "updated " updated-at)
                                                (str "created " created-at))]
                                (println (str "    " (bold (str (inc idx) ".")) " " (cyan name) " " (dim (str "(" date-info ")"))))))
                            (println)
                            (println (bold "  Template Actions:"))
                            (println (str "    " (bold (str (inc (count saved)) ".")) " " (cyan "Create new custom template")))
                            (println (str "    " (bold (str (+ (count saved) 2) ".")) " " (yellow "Edit existing template")))
                            (println (str "    " (bold (str (+ (count saved) 3) ".")) " " (yellow "Duplicate template")))
                            (println (str "    " (bold (str (+ (count saved) 4) ".")) " " (yellow "Rename template")))
                            (println (str "    " (bold (str (+ (count saved) 5) ".")) " " (red "Delete template")))
                            (println)
                            (print (str "  Choice [" (bold (str (inc (count saved)))) "]: "))
                            (flush)
                            (let [input (str/trim (or (read-line) ""))
                                  choice (if (empty? input)
                                           (inc (count saved))
                                           (try (Integer/parseInt input) (catch Exception _ nil)))]
                              (cond
                                 ;; Edit template
                                (= choice (+ (count saved) 2))
                                (do
                                  (println)
                                  (println (bold "Edit template:"))
                                  (doseq [[idx {:keys [name]}] (map-indexed vector saved)]
                                    (println (str "  " (bold (str (inc idx) ".")) " " (cyan name))))
                                  (print "  Template to edit: ")
                                  (flush)
                                  (let [edit-input (str/trim (or (read-line) ""))
                                        edit-choice (try (Integer/parseInt edit-input) (catch Exception _ nil))]
                                    (if (and edit-choice (>= edit-choice 1) (<= edit-choice (count saved)))
                                      (let [tpl-name (:name (nth saved (dec edit-choice)))
                                            result (select-libraries-interactive)]
                                        (helpers/edit-saved-template tpl-name (:resolved result))
                                        (println (green (str "✓ Updated '" tpl-name "'")))
                                        (recur))
                                      (do
                                        (println (red "  Invalid choice"))
                                        (recur)))))

                                 ;; Duplicate template
                                (= choice (+ (count saved) 3))
                                (do
                                  (println)
                                  (println (bold "Duplicate template:"))
                                  (doseq [[idx {:keys [name]}] (map-indexed vector saved)]
                                    (println (str "  " (bold (str (inc idx) ".")) " " (cyan name))))
                                  (print "  Template to duplicate: ")
                                  (flush)
                                  (let [dup-input (str/trim (or (read-line) ""))
                                        dup-choice (try (Integer/parseInt dup-input) (catch Exception _ nil))]
                                    (if (and dup-choice (>= dup-choice 1) (<= dup-choice (count saved)))
                                      (let [source-name (:name (nth saved (dec dup-choice)))]
                                        (loop []
                                          (let [new-name (prompt "New template name (kebab-case)" nil)]
                                            (if (valid-project-name? new-name)
                                              (try
                                                (helpers/duplicate-saved-template source-name new-name)
                                                (println (green (str "✓ Created '" new-name "' from '" source-name "'")))
                                                (recur)
                                                (catch Exception e
                                                  (println (red (str "  Error: " (.getMessage e))))
                                                  (recur)))
                                              (do
                                                (println (red "  Must be kebab-case (e.g., my-template)"))
                                                (recur))))))
                                      (do
                                        (println (red "  Invalid choice"))
                                        (recur)))))

                                 ;; Rename template
                                (= choice (+ (count saved) 4))
                                (do
                                  (println)
                                  (println (bold "Rename template:"))
                                  (doseq [[idx {:keys [name]}] (map-indexed vector saved)]
                                    (println (str "  " (bold (str (inc idx) ".")) " " (cyan name))))
                                  (print "  Template to rename: ")
                                  (flush)
                                  (let [rename-input (str/trim (or (read-line) ""))
                                        rename-choice (try (Integer/parseInt rename-input) (catch Exception _ nil))]
                                    (if (and rename-choice (>= rename-choice 1) (<= rename-choice (count saved)))
                                      (let [old-name (:name (nth saved (dec rename-choice)))]
                                        (loop []
                                          (let [new-name (prompt "New template name (kebab-case)" nil)]
                                            (if (valid-project-name? new-name)
                                              (try
                                                (helpers/rename-saved-template old-name new-name)
                                                (println (green (str "✓ Renamed '" old-name "' to '" new-name "'")))
                                                (recur)
                                                (catch Exception e
                                                  (println (red (str "  Error: " (.getMessage e))))
                                                  (recur)))
                                              (do
                                                (println (red "  Must be kebab-case (e.g., my-template)"))
                                                (recur))))))
                                      (do
                                        (println (red "  Invalid choice"))
                                        (recur)))))

                                 ;; Delete template
                                (= choice (+ (count saved) 5))

                                (do
                                  (println)
                                  (println (bold "Delete saved template:"))
                                  (doseq [[idx {:keys [name]}] (map-indexed vector saved)]
                                    (println (str "  " (bold (str (inc idx) ".")) " " (cyan name))))
                                  (print "  Template to delete: ")
                                  (flush)
                                  (let [del-input (str/trim (or (read-line) ""))
                                        del-choice (try (Integer/parseInt del-input) (catch Exception _ nil))]
                                    (if (and del-choice (>= del-choice 1) (<= del-choice (count saved)))
                                      (let [tpl-name (:name (nth saved (dec del-choice)))]
                                        (if (confirm (str "Delete '" tpl-name "'?") false)
                                          (do
                                            (helpers/delete-saved-template tpl-name)
                                            (println (green (str "✓ Deleted '" tpl-name "'")))
                                            (recur))
                                          (recur)))
                                      (do
                                        (println (red "  Invalid choice"))
                                        (recur)))))

                                ;; Create new template
                                (= choice (inc (count saved)))
                                (let [result (select-libraries-interactive)
                                      _ (println)
                                      save? (confirm "Save this template for future use?" false)]
                                  (when save?
                                    (loop []
                                      (let [tpl-name (prompt "Template name (kebab-case)" nil)]
                                        (if (valid-project-name? tpl-name)
                                          (do
                                            (helpers/save-custom-template tpl-name (:resolved result))
                                            (println (green (str "✓ Saved as '" tpl-name "'"))))
                                          (do
                                            (println (red "  Must be kebab-case (e.g., my-template)"))
                                            (recur))))))
                                  result)

                                ;; Load saved template
                                (and choice (>= choice 1) (<= choice (count saved)))
                                (let [saved-tpl (nth saved (dec choice))
                                      libs (:libraries saved-tpl)]
                                  (println)
                                  (println (green (str "✓ Loaded template: " (:name saved-tpl))))
                                  {:selected (set libs) :resolved (set libs)})

                                ;; Invalid choice
                                :else
                                (do
                                  (println (red "  Invalid choice"))
                                  (recur)))))
                          ;; No saved templates, create new
                          (let [result (select-libraries-interactive)
                                _ (println)
                                save? (confirm "Save this template for future use?" false)]
                            (when save?
                              (loop []
                                (let [tpl-name (prompt "Template name (kebab-case)" nil)]
                                  (if (valid-project-name? tpl-name)
                                    (do
                                      (helpers/save-custom-template tpl-name (:resolved result))
                                      (println (green (str "✓ Saved as '" tpl-name "'"))))
                                    (do
                                      (println (red "  Must be kebab-case (e.g., my-template)"))
                                      (recur))))))
                            result))))

        ;; 2. Enter Project Name
        _ (println)
        project-name (loop []
                       (let [name (prompt "Project name (kebab-case)" "my-app")]
                         (cond
                           (empty? name)
                           (do (println (red "  Project name is required")) (recur))

                           (not (valid-project-name? name))
                           (do (println (red "  Must be kebab-case (e.g., my-app, awesome-project)")) (recur))

                           :else name)))

        ;; 3. Select Database
        _ (println)
        database (select-from-menu database-options display-database-menu 0)
        db-choice (:id database)
        db-name (:name database)

        ;; 4. Select Output Directory
        _ (println)
        output-dir (loop []
                     (let [dir (prompt "Output directory" ".")
                           full-path (if (= dir ".")
                                       (str (System/getProperty "user.dir") "/" project-name)
                                       (str dir "/" project-name))
                           dir-file (io/file full-path)]
                       (cond
                         (.exists dir-file)
                         (do
                           (println (red (str "  Directory already exists: " full-path)))
                           (if (confirm "  Overwrite?" false)
                             full-path
                             (recur)))

                         (not (valid-directory? (.getParent dir-file)))
                         (do
                           (println (red "  Parent directory does not exist or is not writable"))
                           (recur))

                         :else full-path)))

        ;; 5. Display Summary
        _ (display-summary project-name template-name db-name output-dir)

        ;; Display selected libraries for custom template
        _ (when (= template-id :custom)
            (let [selected (:selected custom-libs)
                  resolved (:resolved custom-libs)]
              (library-metadata/print-dependency-summary selected resolved)

              (when (confirm "Show dependency tree?" false)
                (println)
                (println (bold "Dependency Tree:"))
                (println)
                (library-metadata/visualize-dependency-tree selected resolved)
                (println))))

        ;; 6. Confirm
        confirmed? (confirm "Generate project?" true)]

    (if confirmed?
      (do
        ;; Generate project
        (println)
        (println (bold "Generating project..."))
        (println)

        (let [;; Load template (or generate custom)
              template-edn (if (= template-id :custom)
                             ;; Generate custom template from library selection
                             (helpers/generate-custom-template (:resolved custom-libs))
                             ;; Load existing template
                             (helpers/load-template (name template-id)))
              resolved (helpers/resolve-extends template-edn)
              result (file-generators/generate-project!
                      resolved
                      output-dir
                      project-name
                      {:db-choice db-choice})]

          (if (:success result)
            (let [;; Extract JWT_SECRET from generated README
                  readme-path (str output-dir "/README.md")
                  readme-content (slurp readme-path)
                  jwt-secret (second (re-find #"JWT_SECRET=\"([^\"]+)\"" readme-content))]
              (display-success output-dir template-id jwt-secret))
            (do
              (println)
              (println (red (bold "✗ Project generation failed")))
              (println)
              (System/exit 1)))))

      (do
        (println)
        (println (yellow "Setup cancelled."))
        (println)
        (System/exit 0)))))

;; =============================================================================
;; CLI Argument Parsing
;; =============================================================================

(defn parse-args
  "Parse command-line arguments into a map of options."
  [args]
  (loop [args args
         opts {}]
    (if (empty? args)
      opts
      (let [arg (first args)
            rest-args (rest args)]
        (cond
          ;; Flags (no value)
          (or (= arg "--help") (= arg "-h"))
          (recur rest-args (assoc opts :help true))

          (= arg "--yes")
          (recur rest-args (assoc opts :yes true))

          (= arg "--dry-run")
          (recur rest-args (assoc opts :dry-run true))

          ;; Options with values
          (= arg "--template")
          (recur (rest rest-args) (assoc opts :template (first rest-args)))

          (= arg "--name")
          (recur (rest rest-args) (assoc opts :name (first rest-args)))

          (= arg "--db")
          (recur (rest rest-args) (assoc opts :db (first rest-args)))

          (= arg "--output")
          (recur (rest rest-args) (assoc opts :output (first rest-args)))

          (= arg "--template-path")
          (recur (rest rest-args) (assoc opts :template-path (first rest-args)))

          ;; Unknown argument
          :else
          (do
            (println (red (str "Unknown argument: " arg)))
            (println "Use --help for usage information.")
            (System/exit 1)))))))

(defn validate-non-interactive-args
  "Validate required arguments for non-interactive mode."
  [opts]
  (let [errors (atom [])]

    ;; Template validation (not required if --template-path is provided)
    (when-not (or (:template opts) (:template-path opts))
      (swap! errors conj "Missing required argument: --template (or --template-path)"))

    (when (and (:template opts) (not (:template-path opts)))
      (let [template-id (keyword (:template opts))
            valid-templates #{:minimal :api-only :microservice :web-app :saas}]
        (when-not (valid-templates template-id)
          (swap! errors conj (str "Invalid template: " (:template opts) ". Must be one of: minimal, api-only, microservice, web-app, saas")))))

    ;; Name validation
    (when-not (:name opts)
      (swap! errors conj "Missing required argument: --name"))

    (when (:name opts)
      (when-not (valid-project-name? (:name opts))
        (swap! errors conj (str "Invalid project name: " (:name opts) ". Must be kebab-case (lowercase, hyphens only)"))))

    ;; Database validation
    (when (:db opts)
      (let [db (keyword (:db opts))
            valid-dbs #{:sqlite :postgres :both}]
        (when-not (valid-dbs db)
          (swap! errors conj (str "Invalid database: " (:db opts) ". Must be one of: sqlite, postgres, both")))))

    ;; Return errors or nil
    (when (seq @errors)
      @errors)))

(defn non-interactive-setup
  "Run setup in non-interactive mode using CLI arguments."
  [opts]
  (let [errors (validate-non-interactive-args opts)]
    (if errors
      (do
        (println)
        (println (red (bold "Validation errors:")))
        (doseq [error errors]
          (println (red (str "  - " error))))
        (println)
        (println "Use --help for usage information.")
        (System/exit 1))

      ;; Valid arguments - proceed with generation
      (let [;; Template handling
            template-id (when (:template opts) (keyword (:template opts)))
            template (when template-id (first (filter #(= (:id %) template-id) templates)))
            template-name (if (:template-path opts)
                            "Custom Template"
                            (:name template))

            ;; Other options
            project-name (:name opts)
            db-choice (if (:db opts)
                        (keyword (:db opts))
                        :sqlite)  ; Default to SQLite
            output-dir (or (:output opts) ".")

            ;; Calculate full path
            full-path (if (= output-dir ".")
                        (str (System/getProperty "user.dir") "/" project-name)
                        (str output-dir "/" project-name))
            dir-file (io/file full-path)

            ;; Get database metadata
            db-option (first (filter #(= (:id %) db-choice) database-options))]

        ;; Check if directory exists (unless --yes flag)
        (when (and (.exists dir-file) (not (:yes opts)))
          (println)
          (println (red (str "Directory already exists: " full-path)))
          (println "Use --yes to overwrite, or choose a different output directory.")
          (System/exit 1))

        ;; Check parent directory is valid
        (when-not (valid-directory? (.getParent dir-file))
          (println)
          (println (red "Parent directory does not exist or is not writable"))
          (System/exit 1))

        ;; Dry-run mode - just show what would be generated
        (if (:dry-run opts)
          (do
            (println)
            (println (bold "Dry-run mode - would generate:"))
            (println)
            (println (cyan "┌─ Project Configuration ───────────────────────────────┐"))
            (println (cyan "│") (str "Project Name:  " (bold project-name)))
            (println (cyan "│") (str "Template:      " (bold template-name)))
            (println (cyan "│") (str "Database:      " (bold (:name db-option))))
            (println (cyan "│") (str "Output Dir:    " (bold full-path)))
            (println (cyan "└────────────────────────────────────────────────────────┘"))
            (println)
            (println "Files that would be created:")
            (println "  - deps.edn")
            (println "  - resources/conf/dev/config.edn")
            (println "  - resources/conf/dev/system.clj")
            (println "  - .env.example")
            (println "  - .gitignore")
            (println "  - build.clj")
            (println "  - README.md")
            (println "  - src/boundary/app.clj")
            (println "  - test/boundary/app_test.clj")
            (println)
            (println (dim "Run without --dry-run to actually generate the project."))
            (println))

          ;; Actually generate the project
          (do
            (println)
            (println (bold "Generating project..."))
            (println)

            (let [template-edn (if (:template-path opts)
                                ;; Load from custom path
                                 (edn/read-string (slurp (:template-path opts)))
                                ;; Load from templates directory
                                 (helpers/load-template (name template-id)))
                  resolved (helpers/resolve-extends template-edn)
                  result (file-generators/generate-project!
                          resolved
                          full-path
                          project-name
                          {:db-choice db-choice})]

              (if (:success result)
                (let [;; Extract JWT_SECRET from generated README
                      readme-path (str full-path "/README.md")
                      readme-content (slurp readme-path)
                      jwt-secret (second (re-find #"JWT_SECRET=\"([^\"]+)\"" readme-content))]
                  (if (:template-path opts)
                    ;; Custom template - display simplified success message
                    (do
                      (println)
                      (println (green (bold "✓ Project generated successfully!")))
                      (println)
                      (println (bold "Next steps:"))
                      (println)
                      (println (str "  1. " (bold "cd ") full-path))
                      (println)
                      (when jwt-secret
                        (println "  2. Set environment variables:")
                        (println (str "     " (cyan (str "export JWT_SECRET=\"" jwt-secret "\""))))
                        (println (str "     " (cyan "export BND_ENV=development")))
                        (println))
                      (println "  3. Start the REPL:")
                      (println (str "     " (cyan "clojure -M:repl-clj")))
                      (println)
                      (println "  4. In the REPL, start the system:")
                      (println (str "     " (cyan "(require '[integrant.repl :as ig-repl])")))
                      (println (str "     " (cyan "(ig-repl/go)")))
                      (println)
                      (println "  5. Visit " (blue "http://localhost:3000"))
                      (println)
                      (println (bold "Documentation:"))
                      (println (str "  - README.md in " full-path))
                      (println "  - https://github.com/thijs-creemers/boundary")
                      (println))
                    ;; Built-in template - use full display-success
                    (display-success full-path template-id jwt-secret)))
                (do
                  (println)
                  (println (red (bold "✗ Project generation failed")))
                  (println)
                  (System/exit 1))))))))))

;; =============================================================================
;; Help Text
;; =============================================================================

(defn show-help []
  (println)
  (println (bold "Boundary Framework Setup Wizard"))
  (println)
  (println "  Interactive wizard to create a new Boundary project.")
  (println)
  (println (bold "Usage:"))
  (println "  bb setup                                    # Interactive wizard")
  (println "  bb setup --help                             # Show this help")
  (println "  bb setup [OPTIONS]                          # Non-interactive mode")
  (println)
  (println (bold "Non-Interactive Options:"))
  (println "  --template TEMPLATE   Template to use (minimal, api-only, microservice, web-app, saas)")
  (println "  --name NAME           Project name (kebab-case)")
  (println "  --db DATABASE         Database (sqlite, postgres, both) [default: sqlite]")
  (println "  --output DIR          Output directory [default: current directory]")
  (println "  --yes                 Skip confirmation prompts (overwrite if exists)")
  (println "  --dry-run             Show what would be generated without creating files")
  (println "  --template-path PATH  Use custom template file instead of built-in")
  (println)
  (println (bold "Examples:"))
  (println "  # Interactive")
  (println "  bb setup")
  (println)
  (println "  # Non-interactive with minimal options")
  (println "  bb setup --template minimal --name my-app")
  (println)
  (println "  # Non-interactive with all options")
  (println "  bb setup --template web-app --name my-blog --db postgres --output /tmp")
  (println)
  (println "  # Dry-run to preview")
  (println "  bb setup --template saas --name my-saas --dry-run")
  (println)
  (println "  # Use custom template")
  (println "  bb setup --template-path ./my-template.edn --name my-app")
  (println)
  (println (bold "Templates:"))
  (println "  minimal    - HTTP + DB + health checks (3 libraries)")
  (println "  api-only   - RESTful JSON API with JWT auth (4 libraries)")
  (println "  web-app    - Auth + admin UI (5 libraries)")
  (println "  saas       - Multi-tenant SaaS (10 libraries)")
  (println)
  (println (bold "Database Options:"))
  (println "  sqlite     - Zero-config, perfect for dev")
  (println "  postgres   - Production-grade SQL database")
  (println "  both       - SQLite for dev, PostgreSQL for prod")
  (println)
  (println (bold "Documentation:"))
  (println "  https://github.com/thijs-creemers/boundary")
  (println))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn -main [& args]
  (let [opts (parse-args args)]
    (cond
      ;; Help flag
      (:help opts)
      (show-help)

      ;; Non-interactive mode (any CLI args besides help)
      (or (:template opts) (:name opts) (:db opts) (:output opts) (:dry-run opts) (:template-path opts))
      (non-interactive-setup opts)

      ;; Interactive wizard (no args)
      :else
      (wizard-setup))))

;; Run if executed as script
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
