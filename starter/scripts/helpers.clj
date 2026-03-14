#!/usr/bin/env bb
;; scripts/helpers.clj
;;
;; Template loading and merging logic for Boundary starter setup wizard.
;;
;; Core functions:
;; - load-template: Load template EDN from templates/ directory
;; - resolve-extends: Recursively resolve :extends chain
;; - deep-merge: Deep merge maps, concatenate vectors
;; - template->deps-edn: Generate deps.edn from resolved template
;; - template->config-edn: Generate config.edn from resolved template
;; - template->env-vars: Generate .env content from resolved template

(ns helpers
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.pprint :as pprint]
            [clojure.java.shell :as shell]))

;; Load library metadata for custom template generation
(load-file "scripts/library_metadata.clj")
(require '[library-metadata :as library-metadata])

;; =============================================================================
;; Template Loading
;; =============================================================================

(defn load-template
  "Load template EDN file from templates/ directory.
   Throws if template doesn't exist."
  [template-name]
  (let [path (str "templates/" template-name ".edn")
        file (io/file path)]
    (if (.exists file)
      (edn/read-string (slurp file))
      (throw (ex-info (str "Template not found: " template-name)
                      {:type :template-not-found
                       :template template-name
                       :path path})))))

;; =============================================================================
;; Deep Merging
;; =============================================================================

(defn deep-merge
  "Recursively merge maps. When both values are:
   - maps: recurse and merge
   - vectors: concatenate (deduplicate after)
   - otherwise: take right value"
  [& maps]
  (letfn [(merge-entry [m [k v]]
            (let [existing (get m k)]
              (cond
                ;; Both maps: recurse
                (and (map? existing) (map? v))
                (assoc m k (deep-merge existing v))

                ;; Both vectors: concatenate and dedupe
                (and (vector? existing) (vector? v))
                (assoc m k (vec (distinct (concat existing v))))

                ;; Otherwise: right wins
                :else
                (assoc m k v))))]
    (reduce (fn [result m]
              (reduce merge-entry result m))
            {}
            maps)))

;; =============================================================================
;; Template Extension Resolution
;; =============================================================================

(defn resolve-extends
  "Recursively resolve template :extends chain.
   Example: saas extends web-app extends _base
   Returns fully merged template config."
  [template]
  (if-let [parent-key (get-in template [:meta :extends])]
    (let [parent-name (if (keyword? parent-key)
                        (name parent-key)
                        (str parent-key))
          parent (load-template parent-name)
          resolved-parent (resolve-extends parent)]
      ;; Merge parent into child (child values override)
      (deep-merge resolved-parent template))
    ;; No parent, return as-is
    template))

;; =============================================================================
;; Deps.edn Generation
;; =============================================================================

(defn get-boundary-repo-path
  "Get the Boundary repository path from environment variable or use default.
   Priority:
   1. BOUNDARY_REPO_PATH environment variable
   2. Parent directory (if current dir is starter subdirectory)
   3. Sibling directory (../boundary if starter is standalone)"
  []
  (or (System/getenv "BOUNDARY_REPO_PATH")
      ;; If we're in boundary/starter, parent is the boundary repo
      (let [current-dir (System/getProperty "user.dir")
            current-file (io/file current-dir)]
        (when (and (.exists current-file)
                   (= "starter" (.getName current-file)))
          (let [parent (.getParentFile current-file)]
            (when (and parent
                       (.exists (io/file parent "libs"))  ; Verify it's the boundary repo
                       (.exists (io/file parent "deps.edn")))
              (.getAbsolutePath parent)))))
      ;; If starter is standalone, look for sibling boundary directory
      (let [current-dir (System/getProperty "user.dir")
            parent-dir (-> current-dir io/file .getParentFile)
            boundary-sibling (io/file parent-dir "boundary")]
        (when (and (.exists boundary-sibling)
                   (.exists (io/file boundary-sibling "libs")))  ; Verify it's the boundary repo
          (.getAbsolutePath boundary-sibling)))))

(defn get-boundary-git-sha
  "Get the current git SHA from the boundary repository.
   Falls back to a known working commit if git command fails.
   Uses BOUNDARY_REPO_PATH environment variable if set."
  []
  (if-let [boundary-repo-path (get-boundary-repo-path)]
    (try
      (let [result (shell/sh "git" "rev-parse" "HEAD" :dir boundary-repo-path)]
        (if (zero? (:exit result))
          (str/trim (:out result))
          ;; Fallback to a known commit from 2026-03-14
          "8b1899d8cdb678563cb65b1fc7415905bb787302"))
      (catch Exception _
        ;; If anything fails, use fallback
        "8b1899d8cdb678563cb65b1fc7415905bb787302"))
    "8b1899d8cdb678563cb65b1fc7415905bb787302"))

(defn boundary-lib->dep
  "Convert boundary library keyword to deps.edn dependency entry.
   Uses git dependencies to pull from GitHub until libraries are published to Clojars.
   Uses simple 'boundary/libname' format to avoid conflicts with transitive deps.
   Example: :user -> [boundary/user {:git/url ... :git/sha \"abc123...\" :deps/root \"libs/user\"}]"
  [lib-key]
  (let [lib-name (name lib-key)
        artifact (symbol (str "boundary/" lib-name))
        git-sha (get-boundary-git-sha)]
    [artifact {:git/url "https://github.com/thijs-creemers/boundary"
               :git/sha git-sha
               :deps/root (str "libs/" lib-name)}]))

(defn dependency->dep
  "Convert template dependency map to deps.edn entry.
   Example: {:group \"org.clojure/clojure\" :version \"1.12.4\"}
         -> [org.clojure/clojure {:mvn/version \"1.12.4\"}]"
  [[_dep-key {:keys [group version git/tag git/sha]}]]
  (let [artifact (symbol group)]
    (cond
      ;; Git dependency
      (and tag sha)
      [artifact {:git/tag tag :git/sha sha}]

      ;; Maven dependency
      version
      [artifact {:mvn/version version}]

      :else
      (throw (ex-info "Invalid dependency format"
                      {:dependency [_dep-key {:keys [group version]}]})))))

(defn db-drivers-for-config
  "Return database driver deps based on :db :type in config.
   Returns vector of [artifact version-map] pairs."
  [template db-choice]
  (let [drivers (:db-drivers template)
        base-type (get-in template [:config :db :type])]
    (case db-choice
      :sqlite [(dependency->dep [:sqlite (get drivers :sqlite)])]
      :postgres [(dependency->dep [:postgres (get drivers :postgres)])]
      :both [(dependency->dep [:sqlite (get drivers :sqlite)])
             (dependency->dep [:postgres (get drivers :postgres)])]
      ;; Default: use base type
      [(dependency->dep [(or base-type :sqlite) (get drivers (or base-type :sqlite))])])))

(defn template->deps-edn
  "Generate deps.edn map from resolved template.
   
   Options:
   - :db-choice - :sqlite, :postgres, or :both (default: from template)"
  ([template] (template->deps-edn template {}))
  ([template {:keys [db-choice] :or {db-choice nil}}]
   (let [{:keys [dependencies boundary-libs paths aliases]} template

         ;; Convert boundary libs to deps
         boundary-deps (into {} (map boundary-lib->dep boundary-libs))

         ;; Convert base dependencies
         base-deps (into {} (map dependency->dep dependencies))

         ;; Add database drivers
         db-deps (into {} (db-drivers-for-config template db-choice))

         ;; Merge all deps
         all-deps (merge base-deps boundary-deps db-deps)

         ;; Convert aliases (keys need to preserve structure)
         processed-aliases (into {}
                                 (map (fn [[k v]]
                                        [k (update v :extra-deps
                                                   (fn [deps]
                                                     (when deps
                                                       (into {} (map dependency->dep deps)))))])
                                      aliases))]
     {:paths paths
      :deps all-deps
      :aliases processed-aliases})))

;; =============================================================================
;; Config.edn Generation
;; =============================================================================

(defn env-placeholder->aero-tag
  "Convert :env/VAR_NAME keywords to Aero #env VAR_NAME tagged literals.
   Walks the config tree and replaces all :env/* keywords."
  [config]
  (walk/postwalk
   (fn [form]
     (if (and (keyword? form)
              (= "env" (namespace form)))
       ;; Convert :env/JWT_SECRET -> #env JWT_SECRET
       ;; For now, just keep as keyword - actual #env conversion happens during file write
       (symbol (str "#env " (name form)))
       form))
   config))

(defn template->config-edn
  "Generate resources/conf/dev/config.edn from resolved template.
   Extracts :config section and formats for Aero/Integrant.
   Converts :env/VAR_NAME placeholders to #env VAR_NAME tagged literals."
  [template]
  (let [{:keys [config]} template]
    ;; For templates: keep :env/* keywords as-is
    ;; When writing to file, we'll handle #env tag generation
    config))

;; =============================================================================
;; Environment Variables Generation
;; =============================================================================

(defn template->env-vars
  "Generate .env.example file content from resolved template.
   Lists required and optional environment variables."
  [template]
  (let [{:keys [env-vars]} template
        {:keys [required optional]} env-vars]
    (str/join "\n"
              (concat
               ["# Boundary Framework - Environment Configuration"
                "# Copy this file to .env and customize for your environment"
                ""
                "# ============================================================================="]
               (when (seq required)
                 (concat
                  ["# Required Environment Variables"
                   "# ============================================================================="]
                  (map #(str % "=") required)
                  [""]))
               (when (seq optional)
                 (concat
                  ["# Optional Environment Variables"
                   "# ============================================================================="]
                  (map #(str "# " % "=") optional)
                  [""]))))))

;; =============================================================================
;; System.clj Generation
;; =============================================================================

(defn template->system-clj
  "Generate resources/conf/dev/system.clj Integrant config from template.
   Includes integrant keys from template."
  [template]
  (let [{:keys [integrant-keys]} template]
    ;; For now, return a basic system config structure
    ;; This will be expanded in later implementation
    {:integrant-keys integrant-keys}))

;; =============================================================================
;; README Generation
;; =============================================================================

(defn template->readme-sections
  "Extract README sections from template for injection into README template."
  [template]
  (let [{:keys [readme-sections]} template]
    {:features (str/join "\n" (map #(str "- ✅ " %) (:features readme-sections)))
     :next-steps (str/join "\n"
                           (map-indexed
                            (fn [idx step]
                              (str (inc idx) ". " step))
                            (:next-steps readme-sections)))}))

;; =============================================================================
;; Pretty Printing
;; =============================================================================

(defn env-keyword->aero-string
  "Convert :env/VAR_NAME keyword to #env VAR_NAME string for config files."
  [k]
  (if (and (keyword? k) (= "env" (namespace k)))
    (str "#env " (name k))
    k))

(defn config->aero-string
  "Convert config map to EDN string with #env tags properly formatted.
   Walks the config and replaces :env/VAR_NAME with #env VAR_NAME."
  [config]
  (let [processed (walk/postwalk
                   (fn [form]
                     (if (and (keyword? form)
                              (= "env" (namespace form)))
                       ;; Return a special marker that we'll replace in string output
                       (str "##ENV##" (name form))
                       form))
                   config)
        edn-str (with-out-str (pprint/pprint processed))]
    ;; Replace markers with actual #env tags
    (str/replace edn-str #"\"##ENV##([^\"]+)\"" "#env $1")))

(defn pprint-edn
  "Pretty-print EDN to string with proper formatting."
  [data]
  (with-out-str
    (pprint/pprint data)))

;; =============================================================================
;; Validation
;; =============================================================================

(defn validate-template
  "Validate that template has all required fields.
   Throws ex-info if validation fails."
  [template]
  (let [required-keys [:meta :dependencies :boundary-libs :config :paths :aliases]
        missing-keys (remove #(contains? template %) required-keys)]
    (when (seq missing-keys)
      (throw (ex-info "Template missing required keys"
                      {:type :invalid-template
                       :missing-keys missing-keys
                       :template template}))))
  template)

;; =============================================================================
;; Custom Template Generation
;; =============================================================================

(defn merge-library-configs
  "Merge configuration sections from selected libraries.
   Returns a config map built from library metadata config-template field."
  [selected-libs]
  (reduce
   (fn [acc lib-id]
     (let [lib (library-metadata/get-library lib-id)
           config-template (:config-template lib)]
       (merge acc config-template)))
   {}
   selected-libs))

(defn generate-custom-template
  "Generate a custom template EDN from a set of selected library IDs.
   Returns a template map that can be used with resolve-extends.
   
   Args:
     selected-libs - Set of library IDs (e.g., #{:core :user :admin})
   
   Returns:
     Template map with :boundary-libs, :config, :dependencies, etc."
  [selected-libs]
  (let [;; Load base template
        base (load-template "_base")

        ;; Resolve dependencies to get full library set
        resolved-libs (library-metadata/resolve-dependencies selected-libs)

        ;; Merge configs from all resolved libraries (including dependencies)
        merged-config (merge-library-configs resolved-libs)

        ;; Build template
        template {:meta {:name "Custom Template"
                         :description "Custom library selection"
                         :extends :_base}
                  :boundary-libs (vec (sort resolved-libs))
                  :config merged-config
                  :readme-sections {:features ["Custom selection of Boundary libraries"]
                                    :next-steps ["See individual library documentation for setup"]}}]

    ;; Merge with base template
    (deep-merge base template)))

;; =============================================================================
;; Saved Custom Templates
;; =============================================================================

(defn save-custom-template
  "Save a custom template configuration to a file.
   
   Args:
     template-name - Name for the saved template (kebab-case)
     selected-libs - Set of library IDs
   
   Returns:
     Path to saved file"
  [template-name selected-libs]
  (let [saved-dir (io/file "saved-templates")
        _ (when-not (.exists saved-dir)
            (.mkdirs saved-dir))
        file-path (io/file saved-dir (str template-name ".edn"))
        template-data {:name template-name
                       :created-at (str (java.time.Instant/now))
                       :libraries (vec (sort selected-libs))}]
    (spit file-path (pprint-edn template-data))
    (.getPath file-path)))

(defn load-saved-template
  "Load a saved custom template by name.
   
   Args:
     template-name - Name of saved template
   
   Returns:
     Map with :name, :created-at, :libraries"
  [template-name]
  (let [file-path (io/file "saved-templates" (str template-name ".edn"))]
    (if (.exists file-path)
      (edn/read-string (slurp file-path))
      (throw (ex-info "Saved template not found"
                      {:type :template-not-found
                       :template-name template-name
                       :path (.getPath file-path)})))))

(defn list-saved-templates
  "List all saved custom templates.
   
   Returns:
     Vector of maps with :name, :created-at, :libraries"
  []
  (let [saved-dir (io/file "saved-templates")]
    (if (.exists saved-dir)
      (->> (.listFiles saved-dir)
           (filter #(.endsWith (.getName %) ".edn"))
           (map slurp)
           (map edn/read-string)
           vec)
      [])))

(defn delete-saved-template
  "Delete a saved custom template.
   
   Args:
     template-name - Name of saved template
   
   Returns:
     true if deleted, false if not found"
  [template-name]
  (let [file-path (io/file "saved-templates" (str template-name ".edn"))]
    (if (.exists file-path)
      (do
        (.delete file-path)
        true)
      false)))

(defn edit-saved-template
  "Edit a saved custom template by modifying its library selection.
   
   Args:
     template-name - Name of saved template to edit
     new-libs - New set of library IDs
   
   Returns:
     Path to updated file
   
   Throws:
     Exception if template doesn't exist"
  [template-name new-libs]
  (let [file-path (io/file "saved-templates" (str template-name ".edn"))]
    (when-not (.exists file-path)
      (throw (ex-info "Template not found" {:template template-name})))

    ;; Load existing template to preserve created-at
    (let [existing (edn/read-string (slurp file-path))
          updated-data {:name template-name
                        :created-at (:created-at existing)
                        :updated-at (str (java.time.Instant/now))
                        :libraries (vec (sort new-libs))}]
      (spit file-path (pprint-edn updated-data))
      (.getPath file-path))))

(defn duplicate-saved-template
  "Duplicate a saved custom template with a new name.
   
   Args:
     source-name - Name of template to duplicate
     new-name - Name for the duplicate
   
   Returns:
     Path to new file
   
   Throws:
     Exception if source doesn't exist or new name already exists"
  [source-name new-name]
  (let [source-path (io/file "saved-templates" (str source-name ".edn"))
        dest-path (io/file "saved-templates" (str new-name ".edn"))]
    (when-not (.exists source-path)
      (throw (ex-info "Source template not found" {:template source-name})))
    (when (.exists dest-path)
      (throw (ex-info "Destination template already exists" {:template new-name})))

    ;; Load source and create new with different name and timestamp
    (let [source (edn/read-string (slurp source-path))
          new-data {:name new-name
                    :created-at (str (java.time.Instant/now))
                    :libraries (:libraries source)}]
      (spit dest-path (pprint-edn new-data))
      (.getPath dest-path))))

(defn rename-saved-template
  "Rename a saved custom template, preserving all metadata.
   
   Args:
     old-name - Current name of template
     new-name - New name for template
   
   Returns:
     Path to renamed file
   
   Throws:
     Exception if old template doesn't exist or new name already exists"
  [old-name new-name]
  (let [old-path (io/file "saved-templates" (str old-name ".edn"))
        new-path (io/file "saved-templates" (str new-name ".edn"))]
    (when-not (.exists old-path)
      (throw (ex-info "Template not found" {:template old-name})))
    (when (.exists new-path)
      (throw (ex-info "Destination template already exists" {:template new-name})))

    ;; Load, update name, save to new location, delete old
    (let [template (edn/read-string (slurp old-path))
          updated-data (assoc template :name new-name)]
      (spit new-path (pprint-edn updated-data))
      (.delete old-path)
      (.getPath new-path))))

;; =============================================================================
;; Available Templates
;; =============================================================================

(defn list-available-templates
  "List all available template files in templates/ directory.
   Returns vector of template names (without .edn extension)."
  []
  (let [templates-dir (io/file "templates")]
    (when (.exists templates-dir)
      (->> (.listFiles templates-dir)
           (filter #(and (.isFile %) (.endsWith (.getName %) ".edn")))
           (map #(str/replace (.getName %) #"\.edn$" ""))
           (remove #(str/starts-with? % "_"))  ; Exclude _base.edn
           (sort)
           (vec)))))
