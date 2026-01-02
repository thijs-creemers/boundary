(ns boundary.platform.migrations.core.planning
  "Pure functions for migration planning and ordering.
   
   This namespace contains the functional core of the migration system:
   - Version parsing and validation
   - Migration ordering and sequencing
   - Gap detection
   - Dependency resolution
   
   All functions are pure - no I/O, no side effects."
  (:require [malli.core :as m]
            [boundary.platform.migrations.schema :as schema])
  (:import [java.time LocalDateTime]))

;; Version parsing and validation

(def ^:private version-pattern
  "Regex pattern for migration version: YYYYMMDDhhmmss"
  #"^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})$")

(defn parse-version
  "Parse a version string into components.
   
   Args:
     version-str - Version string in YYYYMMDDhhmmss format
     
   Returns:
     Map with :year :month :day :hour :minute :second keys, or nil if invalid
     
   Pure: true
   
   Examples:
     (parse-version \"20240515120000\")
     => {:year 2024 :month 5 :day 15 :hour 12 :minute 0 :second 0}"
  [version-str]
  (when (and version-str (string? version-str))
    (when-let [matches (re-matches version-pattern version-str)]
      (let [[_ year month day hour minute second] matches]
        {:year   (Integer/parseInt year)
         :month  (Integer/parseInt month)
         :day    (Integer/parseInt day)
         :hour   (Integer/parseInt hour)
         :minute (Integer/parseInt minute)
         :second (Integer/parseInt second)}))))

(defn valid-version?
  "Check if a version string is valid.
   
   Args:
     version-str - Version string to validate
     
   Returns:
     Boolean indicating if version is valid
     
   Pure: true"
  [version-str]
  (boolean (parse-version version-str)))

(defn version->timestamp
  "Convert version string to LocalDateTime.
   
   Args:
     version-str - Version string in YYYYMMDDhhmmss format
     
   Returns:
     java.time.LocalDateTime or nil if invalid
     
   Pure: true"
  [version-str]
  (when-let [{:keys [year month day hour minute second]} (parse-version version-str)]
    (try
      (LocalDateTime/of year month day hour minute second)
      (catch Exception _
        nil))))

(defn compare-versions
  "Compare two version strings.
   
   Args:
     v1 - First version string
     v2 - Second version string
     
   Returns:
     -1 if v1 < v2, 0 if v1 = v2, 1 if v1 > v2
     
   Pure: true"
  [v1 v2]
  (compare v1 v2))

;; Filename parsing

(defn parse-migration-filename
  "Parse a migration filename into components.
   
   Args:
     filename - Migration filename (e.g., \"20240515120000_create_users.sql\")
     
   Returns:
     Map with :version :name :direction :extension keys, or nil if invalid
     
   Pure: true
   
   Examples:
     (parse-migration-filename \"20240515120000_create_users.sql\")
     => {:version \"20240515120000\" :name \"create_users\" 
         :direction :up :extension \"sql\"}
     
     (parse-migration-filename \"20240515120000_create_users_down.sql\")
     => {:version \"20240515120000\" :name \"create_users\"
         :direction :down :extension \"sql\"}"
  [filename]
  (when filename
    (when-let [[_ version name direction ext]
               (re-matches #"^(\d{14})_(.+?)(_down)?\.([^.]+)$" filename)]
      {:version   version
       :name      name
       :direction (if direction :down :up)
       :extension ext})))

(defn migration-filename?
  "Check if a filename is a valid migration file.
   
   Args:
     filename - Filename to check
     
   Returns:
     Boolean indicating if filename is valid
     
   Pure: true"
  [filename]
  (boolean (parse-migration-filename filename)))

;; Migration ordering

(defn sort-migrations
  "Sort migrations by version in ascending order.
   
   Args:
     migrations - Collection of migration maps with :version key
     
   Returns:
     Sorted sequence of migrations
     
   Pure: true"
  [migrations]
  (sort-by :version compare migrations))

(defn group-migrations-by-module
  "Group migrations by module.
   
   Args:
     migrations - Collection of migration maps with :module key
     
   Returns:
     Map of module-name -> migrations
     
   Pure: true"
  [migrations]
  (group-by :module migrations))

(defn filter-migrations-by-module
  "Filter migrations for a specific module.
   
   Args:
     migrations - Collection of migration maps
     module-name - Module to filter for
     
   Returns:
     Filtered sequence of migrations
     
   Pure: true"
  [migrations module-name]
  (filter #(= module-name (:module %)) migrations))

;; Gap detection

(defn detect-version-gaps
  "Detect gaps in migration version sequence.
   
   A gap is when there's a significant time difference between consecutive
   migrations that might indicate missing migrations.
   
   Args:
     migrations - Sorted collection of migrations with :version key
     max-gap-minutes - Maximum allowed gap in minutes (default: 1440 = 1 day)
     
   Returns:
     Vector of gap maps with :before :after :gap-minutes keys
     
   Pure: true"
  ([migrations]
   (detect-version-gaps migrations 1440))
  ([migrations max-gap-minutes]
   (let [sorted (sort-migrations migrations)]
     (loop [current (first sorted)
            remaining (rest sorted)
            gaps []]
       (if-not (seq remaining)
         gaps
         (let [next-migration (first remaining)
               current-ts (version->timestamp (:version current))
               next-ts (version->timestamp (:version next-migration))
               gap-minutes (when (and current-ts next-ts)
                            (/ (- (.toEpochSecond next-ts java.time.ZoneOffset/UTC)
                                  (.toEpochSecond current-ts java.time.ZoneOffset/UTC))
                               60))]
           (recur next-migration
                  (rest remaining)
                  (if (and gap-minutes (> gap-minutes max-gap-minutes))
                    (conj gaps {:before (:version current)
                                :after (:version next-migration)
                                :gap-minutes (long gap-minutes)})
                    gaps))))))))

(defn detect-duplicate-versions
  "Detect duplicate version numbers across migrations.
   
   Args:
     migrations - Collection of migrations with :version and :module keys
     
   Returns:
     Vector of duplicate maps with :version :migrations :count keys
     
   Pure: true"
  [migrations]
  (let [version-groups (group-by :version migrations)]
    (->> version-groups
         (filter (fn [[_version migs]] (> (count migs) 1)))
         (map (fn [[version migs]]
                {:version version
                 :migrations (vec migs)
                 :count (count migs)}))
         vec)))

;; Migration planning

(defn pending-migrations
  "Determine which migrations are pending (not yet applied).
   
   Args:
     all-migrations - All available migrations
     applied-migrations - Migrations that have been applied
     
   Returns:
     Sorted sequence of pending migrations
     
   Pure: true"
  [all-migrations applied-migrations]
  (let [applied-versions (set (map :version applied-migrations))]
    (->> all-migrations
         (remove #(contains? applied-versions (:version %)))
         sort-migrations)))

(defn migrations-to-version
  "Get migrations needed to reach a specific version.
   
   Args:
     all-migrations - All available migrations
     applied-migrations - Migrations that have been applied
     target-version - Version to migrate to
     
   Returns:
     Map with :status :migrations keys. Status is :success or :error.
     On error, includes :message key.
     
   Pure: true"
  [all-migrations applied-migrations target-version]
  (let [applied-versions (set (map :version applied-migrations))
        sorted-all (sort-migrations all-migrations)
        target-pos (some #(when (= target-version (:version %)) %) sorted-all)]
    (if-not target-pos
      {:status :error
       :message (str "Version not found: " target-version)}
      (let [pending (pending-migrations all-migrations applied-migrations)
            target-applied? (contains? applied-versions target-version)]
        {:status :success
         :migrations (if target-applied?
                       ;; Rolling back to target version
                       (->> applied-migrations
                            sort-migrations
                            reverse
                            (take-while #(> (compare (:version %) target-version) 0))
                            vec)
                       ;; Migrating up to target version
                       (->> pending
                            (take-while #(<= (compare (:version %) target-version) 0))
                            vec))}))))

(defn last-n-migrations
  "Get the last N applied migrations.
   
   Args:
     applied-migrations - Migrations that have been applied
     n - Number of migrations to get (default: 1)
     
   Returns:
     Sequence of last N migrations in reverse order
     
   Pure: true"
  ([applied-migrations]
   (last-n-migrations applied-migrations 1))
  ([applied-migrations n]
   (->> applied-migrations
        sort-migrations
        reverse
        (take n))))

;; Validation

(defn validate-migration-plan
  "Validate a migration plan before execution.
   
   Checks for:
   - All migrations have required fields
   - No duplicate versions
   - No gaps in sequence (warns only)
   - All versions are valid timestamps
   
   Args:
     migrations - Collection of migrations to validate
     
   Returns:
     Map with :valid? boolean and :errors/:warnings vectors
     
   Pure: true"
  [migrations]
  (let [errors []
        warnings []
        
        ;; Check schema validity
        [errors warnings]
        (reduce
         (fn [[errs warns] migration]
           (if (m/validate schema/MigrationFile migration)
             [errs warns]
             [(conj errs {:type :invalid-schema
                         :migration migration
                         :errors (m/explain schema/MigrationFile migration)})
              warns]))
         [errors warnings]
         migrations)
        
        ;; Check for duplicates
        duplicates (detect-duplicate-versions migrations)
        errors (if (seq duplicates)
                 (conj errors {:type :duplicate-versions
                              :duplicates duplicates})
                 errors)
        
        ;; Check for gaps (warning only)
        gaps (detect-version-gaps migrations)
        warnings (if (seq gaps)
                   (conj warnings {:type :version-gaps
                                  :gaps gaps})
                   warnings)
        
        ;; Check version validity
        [errors warnings]
        (reduce
         (fn [[errs warns] migration]
           (if (version->timestamp (:version migration))
             [errs warns]
             [(conj errs {:type :invalid-version-timestamp
                         :version (:version migration)
                         :migration migration})
              warns]))
         [errors warnings]
         migrations)]
    
    {:valid? (empty? errors)
     :errors errors
     :warnings warnings}))

(defn create-migration-plan
  "Create a validated migration plan.
   
   Args:
     migrations - Migrations to execute
     direction - :up or :down
     opts - Optional map with :dry-run? :module keys
     
   Returns:
     Map conforming to MigrationPlan schema
     
   Pure: true"
  [migrations direction opts]
  (let [validation (validate-migration-plan migrations)]
    (merge
     {:migrations migrations
      :direction direction
      :total-count (count migrations)
      :dry-run? (boolean (:dry-run? opts))
      :module (:module opts)}
     validation)))

;; Status reporting

(defn migration-status
  "Generate migration status report.
   
   Args:
     all-migrations - All available migrations
     applied-migrations - Migrations that have been applied
     opts - Optional map with :module key
     
   Returns:
     Map with :applied :pending :total :by-module keys
     
   Pure: true"
  [all-migrations applied-migrations opts]
  (let [filtered (if-let [module (:module opts)]
                   (filter-migrations-by-module all-migrations module)
                   all-migrations)
        applied-set (set (map :version applied-migrations))
        pending (pending-migrations filtered applied-migrations)
        by-module (group-migrations-by-module filtered)]
    {:applied (filter #(contains? applied-set (:version %)) filtered)
     :pending pending
     :total (count filtered)
     :by-module (into {}
                      (map (fn [[mod migs]]
                             [mod {:total (count migs)
                                   :applied (count (filter #(contains? applied-set (:version %)) migs))
                                   :pending (count (filter #(not (contains? applied-set (:version %))) migs))}])
                           by-module))}))
