(ns boundary.platform.migrations.shell.discovery
  "File-based migration discovery implementation.
   
   Responsibilities:
   - Scan migrations directory for SQL files
   - Parse migration metadata from filenames
   - Calculate checksums for migration content
   - Group migrations by module
   - Validate migration file structure
   
   IMPERATIVE SHELL: Contains filesystem I/O operations.
   
   File naming convention:
     migrations/{module}/{version}_{name}.sql         # Up migration
     migrations/{module}/{version}_{name}_down.sql    # Down migration (optional)
   
   Example:
     migrations/user/20240101120000_create_users_table.sql
     migrations/user/20240101120000_create_users_table_down.sql"
  (:require [boundary.platform.migrations.core.checksums :as checksums]
            [boundary.platform.migrations.ports :as ports]
            [boundary.platform.migrations.schema :as schema]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m])
  (:import [java.io File]))

;; -----------------------------------------------------------------------------
;; File System Operations
;; -----------------------------------------------------------------------------

(defn- file-exists?
  "Check if a file exists at the given path.
   
   Args:
     path - File path string
     
   Returns:
     Boolean - true if file exists"
  [^String path]
  (.exists (io/file path)))

(defn- directory-exists?
  "Check if a directory exists at the given path.
   
   Args:
     path - Directory path string
     
   Returns:
     Boolean - true if directory exists and is a directory"
  [^String path]
  (let [f (io/file path)]
    (and (.exists f) (.isDirectory f))))

(defn- list-files-recursive
  "List all files recursively in a directory.
   
   Args:
     dir - Directory path string or File object
     
   Returns:
     Seq of File objects"
  [dir]
  (let [dir-file (io/file dir)]
    (when (.isDirectory dir-file)
      (tree-seq
        (fn [^File f] (.isDirectory f))
        (fn [^File f] (seq (.listFiles f)))
        dir-file))))

(defn- read-file-content
  "Read entire file content as string.
   
   Args:
     file - File object
     
   Returns:
     String - file content"
  [^File file]
  (slurp file))

;; -----------------------------------------------------------------------------
;; Migration File Parsing
;; -----------------------------------------------------------------------------

(defn- parse-migration-filename
  "Parse migration filename into components.
   
   Expected format: {version}_{name}.sql or {version}_{name}_down.sql
   
   Args:
     filename - String filename (without directory path)
     
   Returns:
     Map with :version, :name, :down? keys, or nil if invalid format
     
   Example:
     (parse-migration-filename \"20240101120000_create_users.sql\")
     ;=> {:version \"20240101120000\" :name \"create_users\" :down? false}"
  [filename]
  (when (str/ends-with? filename ".sql")
    (let [base-name (subs filename 0 (- (count filename) 4))  ; Remove .sql
          down? (str/ends-with? base-name "_down")
          clean-name (if down?
                       (subs base-name 0 (- (count base-name) 5))  ; Remove _down
                       base-name)
          parts (str/split clean-name #"_" 2)]
      (when (= 2 (count parts))
        (let [[version name] parts]
          (when (re-matches #"\d{14}" version)  ; Validate version format
            {:version version
             :name name
             :down? down?}))))))

(defn- extract-module-from-path
  "Extract module name from file path.
   
   Expected structure: migrations/{module}/{filename}
   
   Args:
     file - File object
     base-dir - Base migrations directory path
     
   Returns:
     String module name, or nil if structure doesn't match"
  [^File file ^String base-dir]
  (let [file-path (.getAbsolutePath file)
        base-path (.getAbsolutePath (io/file base-dir))
        relative-path (when (str/starts-with? file-path base-path)
                        (subs file-path (inc (count base-path))))  ; +1 for separator
        path-parts (when relative-path
                     (str/split relative-path #"/"))]
    (when (and path-parts (>= (count path-parts) 2))
      (first path-parts))))

(defn- file->migration-file
  "Convert File object to MigrationFile map.
   
   Args:
     file - File object
     base-dir - Base migrations directory path
     
   Returns:
     MigrationFile map or nil if file doesn't match expected structure"
  [^File file base-dir]
  (when-let [module (extract-module-from-path file base-dir)]
    (when-let [parsed (parse-migration-filename (.getName file))]
      (let [content (read-file-content file)
            checksum (checksums/calculate-checksum content)]
        {:version (:version parsed)
         :name (:name parsed)
         :module module
         :file-path (.getAbsolutePath file)
         :content content
         :checksum checksum
         :down? (:down? parsed)}))))

;; -----------------------------------------------------------------------------
;; Migration Discovery
;; -----------------------------------------------------------------------------

(defn- group-up-down-migrations
  "Group up and down migrations together.
   
   Args:
     migration-files - Seq of MigrationFile maps
     
   Returns:
     Map of {[module version] {:up MigrationFile :down MigrationFile}} 
     (down may be nil)"
  [migration-files]
  (reduce
    (fn [acc mig]
      (let [key [(:module mig) (:version mig)]
            direction (if (:down? mig) :down :up)]
        (update acc key assoc direction mig)))
    {}
    migration-files))

(defn- validate-migration-files
  "Validate migration files for consistency.
   
   Args:
     grouped-migrations - Map from group-up-down-migrations
     
   Returns:
     Vector of error maps, or empty vector if valid
     
   Error map: {:type :error-type :module string :version string :message string}"
  [grouped-migrations]
  (into []
        (mapcat (fn [[[module version] {:keys [up down]}]]
                  (cond
                    ;; Missing up migration
                    (nil? up)
                    [{:type :missing-up-migration
                      :module module
                      :version version
                      :message (str "Down migration exists without corresponding up migration")}]
                    
                    ;; Down migration without matching name
                    (and down (not= (:name up) (:name down)))
                    [{:type :name-mismatch
                      :module module
                      :version version
                      :message (str "Up and down migration names don't match: "
                                    (:name up) " vs " (:name down))}]
                    
                    ;; Invalid schema
                    (not (m/validate schema/MigrationFile up))
                    [{:type :invalid-schema
                      :module module
                      :version version
                      :message (str "Up migration fails schema validation")}]
                    
                    (and down (not (m/validate schema/MigrationFile down)))
                    [{:type :invalid-schema
                      :module module
                      :version version
                      :message (str "Down migration fails schema validation")}]
                    
                    :else [])))
        grouped-migrations))

;; -----------------------------------------------------------------------------
;; Discovery Implementation
;; -----------------------------------------------------------------------------

(defrecord FilesystemMigrationDiscovery [base-dir]
  ports/IMigrationFileDiscovery
  
  (discover-migrations [this base-path opts]
    (let [search-path (or base-path base-dir)
          module-filter (:module opts)]
      (if-not (directory-exists? search-path)
        []
        (let [all-files (list-files-recursive search-path)
              sql-files (filter #(and (.isFile ^File %)
                                      (str/ends-with? (.getName ^File %) ".sql"))
                                all-files)
              migration-files (keep #(file->migration-file % search-path) sql-files)
              grouped (group-up-down-migrations migration-files)
              
              ;; Extract migrations with direction and down migration info
              migrations (into []
                               (comp
                                 (map val)
                                 (mapcat (fn [{:keys [up down]}]
                                           (cond-> []
                                             up (conj (assoc up :direction :up :has-down? (some? down)))
                                             down (conj (assoc down :direction :down)))))
                                 (filter #(or (nil? module-filter)
                                             (= module-filter (:module %)))))
                               grouped)]
          migrations))))
  
  (read-migration-file [this file-path]
    (let [file (io/file file-path)]
      (when (.exists file)
        (file->migration-file file base-dir))))
  
  (list-migration-modules [this base-path]
    (let [search-path (or base-path base-dir)]
      (if-not (directory-exists? search-path)
        []
        (let [dir (io/file search-path)
              subdirs (filter #(.isDirectory ^File %) (seq (.listFiles dir)))]
          (mapv #(.getName ^File %) subdirs)))))
  
  (validate-migration-structure [this base-path]
    (let [search-path (or base-path base-dir)]
      (if-not (directory-exists? search-path)
        [{:valid? false
          :errors [{:type :directory-not-found
                    :path search-path
                    :message (str "Migrations directory not found: " search-path)}]}]
        (let [all-files (list-files-recursive search-path)
              sql-files (filter #(and (.isFile ^File %)
                                      (str/ends-with? (.getName ^File %) ".sql"))
                                all-files)
              migration-files (keep #(file->migration-file % search-path) sql-files)
              grouped (group-up-down-migrations migration-files)
              validation-errors (validate-migration-files grouped)]
          (if (empty? validation-errors)
            [{:valid? true :errors []}]
            [{:valid? false :errors validation-errors}]))))))

;; -----------------------------------------------------------------------------
;; Factory
;; -----------------------------------------------------------------------------

(defn create-discovery
  "Create a filesystem-based migration discovery service.
   
   Args:
     base-dir - Base directory path for migrations (default: \"migrations\")
     
   Returns:
     IMigrationFileDiscovery implementation
     
   Example:
     (def discovery (create-discovery \"migrations\"))
     (ports/discover-migrations discovery \"migrations\" {:module \"user\"})"
  ([]
   (create-discovery "migrations"))
  ([base-dir]
   (->FilesystemMigrationDiscovery base-dir)))
