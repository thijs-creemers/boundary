#!/usr/bin/env bb
;; scripts/file_generators.clj
;;
;; File generation functions for Boundary starter projects.
;; Takes resolved templates and writes actual project files to disk.
;;
;; Core functions:
;; - create-directory-structure! - Create src/test/resources directories
;; - write-deps-edn! - Write deps.edn with proper formatting
;; - write-config-edn! - Write config.edn with Aero tags
;; - write-env-example! - Write .env.example
;; - write-readme! - Generate README.md from template
;; - write-gitignore! - Generate .gitignore
;; - write-build-clj! - Generate build.clj
;; - write-system-clj! - Generate Integrant system.clj
;; - generate-project! - Full project generation

(ns file-generators
  (:require [clojure.java.io :as io]))

;; Load helpers for template processing
(load-file "scripts/helpers.clj")
(require '[helpers :as helpers])

;; =============================================================================
;; Directory Structure
;; =============================================================================

(defn create-directory-structure!
  "Create standard Boundary project directory structure.
   Returns map of created directories."
  [output-dir]
  (let [dirs ["src/boundary"
              "test/boundary"
              "resources/conf/dev"
              "resources/conf/dev/admin"
              "resources/public"
              "target"
              ".clj-kondo"]]
    (doseq [dir dirs]
      (let [dir-path (io/file output-dir dir)]
        (.mkdirs dir-path)))
    {:output-dir output-dir
     :created-dirs dirs}))

;; =============================================================================
;; deps.edn Generation
;; =============================================================================

(defn write-deps-edn!
  "Write deps.edn file from template.
   
   Options:
   - :db-choice - :sqlite, :postgres, or :both"
  ([template output-dir] (write-deps-edn! template output-dir {}))
  ([template output-dir {:keys [db-choice] :or {db-choice :sqlite}}]
   (let [deps (helpers/template->deps-edn template {:db-choice db-choice})
         output-file (io/file output-dir "deps.edn")
         content (helpers/pprint-edn deps)]
     (spit output-file content)
     {:file (.getPath output-file)
      :size (.length output-file)})))

;; =============================================================================
;; config.edn Generation
;; =============================================================================

(defn write-config-edn!
  "Write resources/conf/dev/config.edn with Aero tags."
  [template output-dir]
  (let [config (helpers/template->config-edn template)
        output-file (io/file output-dir "resources/conf/dev/config.edn")
        content (helpers/config->aero-string config)]
    (spit output-file content)
    {:file (.getPath output-file)
     :size (.length output-file)}))

;; =============================================================================
;; .env.example Generation
;; =============================================================================

(defn write-env-example!
  "Write .env.example file from template."
  [template output-dir]
  (let [env-content (helpers/template->env-vars template)
        output-file (io/file output-dir ".env.example")]
    (spit output-file env-content)
    {:file (.getPath output-file)
     :size (.length output-file)}))

;; =============================================================================
;; .gitignore Generation
;; =============================================================================

(def gitignore-content
  "# Clojure
.cpcache/
.nrepl-port
target/
*.class
*.jar
!project.jar

# Environment
.env
.env.local

# IDE
.idea/
.vscode/
*.iml
.lsp/
.clj-kondo/.cache/

# Database
*.db
*.db-shm
*.db-wal
dev-database.db

# Logs
logs/
*.log

# OS
.DS_Store
Thumbs.db

# Build
pom.xml
pom.xml.asc
")

(defn write-gitignore!
  "Write .gitignore file."
  [output-dir]
  (let [output-file (io/file output-dir ".gitignore")]
    (spit output-file gitignore-content)
    {:file (.getPath output-file)
     :size (.length output-file)}))

;; =============================================================================
;; build.clj Generation
;; =============================================================================

(defn build-clj-content
  "Generate build.clj content with project name."
  [project-name]
  (str "(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib '" project-name "/app)
(def version \"0.1.0\")
(def class-dir \"target/classes\")
(def basis (b/create-basis {:project \"deps.edn\"}))
(def uber-file (format \"target/%s-%s.jar\" (name lib) version))

(defn clean [_]
  (b/delete {:path \"target\"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs [\"resources\" \"src\"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs [\"src\"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'conf.dev.system})
  (println \"Built\" uber-file))
"))

(defn write-build-clj!
  "Write build.clj file."
  [output-dir project-name]
  (let [content (build-clj-content project-name)
        output-file (io/file output-dir "build.clj")]
    (spit output-file content)
    {:file (.getPath output-file)
     :size (.length output-file)}))

;; =============================================================================
;; system.clj Generation
;; =============================================================================

(def system-clj-content
  "(ns conf.dev.system
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [integrant.core :as ig])
  (:import (java.io PushbackReader)))

(defn read-config [profile]
  (aero/read-config (-> (str \"conf/\" (name profile) \"/config.edn\")
                        io/resource
                        io/reader
                        PushbackReader.)
                    {:profile profile}))

(defn -main [& _]
  (let [profile (or (System/getenv \"BND_ENV\") \"development\")
        config (read-config (keyword profile))]
    (ig/init config)))
")

(defn write-system-clj!
  "Write resources/conf/dev/system.clj Integrant bootstrap."
  [output-dir]
  (let [output-file (io/file output-dir "resources/conf/dev/system.clj")]
    (spit output-file system-clj-content)
    {:file (.getPath output-file)
     :size (.length output-file)}))

;; =============================================================================
;; README.md Generation
;; =============================================================================

(defn readme-content
  "Generate README.md content from template."
  [template project-name]
  (let [sections (helpers/template->readme-sections template)
        template-name (get-in template [:meta :name])
        description (get-in template [:meta :description])]
    (str "# " project-name "

**Template**: " template-name "
**Description**: " description "

---

## Features

" (:features sections) "

---

## Quick Start

```bash
# Set environment variables
export BND_ENV=development
export JWT_SECRET=\"" (apply str (repeatedly 32 #(rand-nth "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"))) "\"

# Start REPL
clojure -M:repl-clj

# In REPL
(require '[integrant.repl :as ig-repl])
(require 'conf.dev.system)
(ig-repl/go)

# Visit http://localhost:3000
```

---

## Next Steps

" (:next-steps sections) "

---

## Testing

Generated app tests depend on project libraries (for example Integrant/Reitit).
If you run tests via `bb -e/load-file`, make sure you include the project classpath, or prefer the Clojure test alias.

```bash
# Run all tests
clojure -M:test:db/h2

# Run with watch
clojure -M:test:db/h2 --watch

# Run specific test
clojure -M:test:db/h2 --focus your-test-ns
```

---

## Build

```bash
# Build uberjar
clojure -T:build clean
clojure -T:build uber

# Run standalone
java -jar target/" project-name "-*.jar
```

---

## Project Structure

```
" project-name "/
├── src/boundary/          # Application code (FC/IS pattern)
├── test/boundary/         # Tests
├── resources/
│   ├── conf/dev/          # Configuration files
│   └── public/            # Static assets
├── deps.edn               # Dependencies
├── build.clj              # Build configuration
└── README.md              # This file
```

---

## Documentation

- [Boundary Framework](https://github.com/thijs-creemers/boundary)
- [AGENTS.md](https://github.com/thijs-creemers/boundary/blob/main/AGENTS.md) - Commands and conventions

---

**Generated with Boundary Framework**
")))

(defn write-readme!
  "Write README.md file from template."
  [template output-dir project-name]
  (let [content (readme-content template project-name)
        output-file (io/file output-dir "README.md")]
    (spit output-file content)
    {:file (.getPath output-file)
     :size (.length output-file)}))

;; =============================================================================
;; Placeholder App Files
;; =============================================================================

(def app-clj-content
  "(ns boundary.app
  (:require [clojure.tools.logging :as log]))

(defn hello-world []
  (log/info \"Hello from Boundary!\")
  {:status 200
   :headers {\"Content-Type\" \"text/plain\"}
   :body \"Hello, Boundary!\"})
")

(defn write-app-clj!
  "Write src/boundary/app.clj placeholder."
  [output-dir]
  (let [output-file (io/file output-dir "src/boundary/app.clj")]
    (spit output-file app-clj-content)
    {:file (.getPath output-file)
     :size (.length output-file)}))

(def app-test-clj-content
  "(ns boundary.app-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.app :as app]))

(deftest hello-world-test
  (testing \"hello-world returns 200 OK\"
    (let [response (app/hello-world)]
      (is (= 200 (:status response)))
      (is (= \"Hello, Boundary!\" (:body response))))))
")

(defn write-app-test-clj!
  "Write test/boundary/app_test.clj placeholder."
  [output-dir]
  (let [output-file (io/file output-dir "test/boundary/app_test.clj")]
    (spit output-file app-test-clj-content)
    {:file (.getPath output-file)
     :size (.length output-file)}))

;; =============================================================================
;; Full Project Generation
;; =============================================================================

(defn generate-project!
  "Generate complete project from template.
   
   Args:
   - template: Resolved template map (from resolve-extends)
   - output-dir: Target directory for project
   - project-name: Project name (for build.clj, README)
   - opts: Options map
     - :db-choice - :sqlite, :postgres, or :both
   
   Returns:
   Map with generation results and file paths."
  ([template output-dir project-name]
   (generate-project! template output-dir project-name {}))
  ([template output-dir project-name opts]
   (let [start-time (System/currentTimeMillis)]
     (println (str "Generating project: " project-name))
     (println (str "Output directory: " output-dir))
     (println (str "Template: " (get-in template [:meta :name])))
     (println)

     ;; Step 1: Create directory structure
     (println "📁 Creating directory structure...")
     (let [dirs (create-directory-structure! output-dir)]
       (println (str "   Created " (count (:created-dirs dirs)) " directories"))
       (println))

     ;; Step 2: Write deps.edn
     (println "📦 Writing deps.edn...")
     (let [result (write-deps-edn! template output-dir opts)]
       (println (str "   ✓ " (:file result) " (" (:size result) " bytes)"))
       (println))

     ;; Step 3: Write config.edn
     (println "⚙️  Writing config.edn...")
     (let [result (write-config-edn! template output-dir)]
       (println (str "   ✓ " (:file result) " (" (:size result) " bytes)"))
       (println))

     ;; Step 4: Write .env.example
     (println "🌍 Writing .env.example...")
     (let [result (write-env-example! template output-dir)]
       (println (str "   ✓ " (:file result) " (" (:size result) " bytes)"))
       (println))

     ;; Step 5: Write .gitignore
     (println "🚫 Writing .gitignore...")
     (let [result (write-gitignore! output-dir)]
       (println (str "   ✓ " (:file result) " (" (:size result) " bytes)"))
       (println))

     ;; Step 6: Write build.clj
     (println "🔨 Writing build.clj...")
     (let [result (write-build-clj! output-dir project-name)]
       (println (str "   ✓ " (:file result) " (" (:size result) " bytes)"))
       (println))

     ;; Step 7: Write system.clj
     (println "🔧 Writing system.clj...")
     (let [result (write-system-clj! output-dir)]
       (println (str "   ✓ " (:file result) " (" (:size result) " bytes)"))
       (println))

     ;; Step 8: Write README.md
     (println "📖 Writing README.md...")
     (let [result (write-readme! template output-dir project-name)]
       (println (str "   ✓ " (:file result) " (" (:size result) " bytes)"))
       (println))

     ;; Step 9: Write placeholder app files
     (println "📝 Writing application files...")
     (write-app-clj! output-dir)
     (println "   ✓ src/boundary/app.clj")
     (write-app-test-clj! output-dir)
     (println "   ✓ test/boundary/app_test.clj")
     (println)

     (let [elapsed (- (System/currentTimeMillis) start-time)]
       (println "✅ Project generation complete!")
       (println (str "   Time: " elapsed "ms"))
       (println)
       (println "Next steps:")
       (println (str "   cd " output-dir))
       (println "   clojure -M:repl-clj")
       (println "   (ig-repl/go)")

       {:success true
        :project-name project-name
        :output-dir output-dir
        :template-name (get-in template [:meta :name])
        :elapsed-ms elapsed}))))
