#!/usr/bin/env bb
;; scripts/create_admin.clj
;;
;; Interactive wizard to create the first admin user for a new Boundary project.
;;
;; Usage (via bb.edn task):
;;   bb create-admin                                             -- interactive wizard (root project)
;;   bb create-admin --dir examples/ecommerce-api               -- target a sub-project
;;   bb create-admin --env prod                                  -- use production config
;;   bb create-admin --email a@b.com --name "Admin"             -- skip prompts
;;
;; Usage (direct):
;;   bb scripts/create_admin.clj

(ns create-admin
  (:require [clojure.string :as str]
            [babashka.process :as p]))

;; =============================================================================
;; ANSI helpers
;; =============================================================================

(defn bold   [s] (str "\033[1m"  s "\033[0m"))
(defn green  [s] (str "\033[32m" s "\033[0m"))
(defn red    [s] (str "\033[31m" s "\033[0m"))
(defn yellow [s] (str "\033[33m" s "\033[0m"))
(defn cyan   [s] (str "\033[36m" s "\033[0m"))
(defn dim    [s] (str "\033[2m"  s "\033[0m"))

;; =============================================================================
;; Input helpers
;; =============================================================================

(defn prompt
  "Print a prompt and read a line of input."
  [label]
  (print (str (cyan "? ") (bold label) ": "))
  (flush)
  (str/trim (or (read-line) "")))

(defn valid-email? [s]
  (boolean (re-matches #"^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$" s)))

;; =============================================================================
;; Argument parsing
;; =============================================================================

(defn parse-args
  "Parse --key value pairs from args into a map."
  [args]
  (loop [[flag & more :as remaining] args
         opts {}]
    (cond
      (empty? remaining) opts
      (= flag "--help")  (recur more (assoc opts :help true))
      (= flag "-h")      (recur more (assoc opts :help true))
      (and (str/starts-with? flag "--") (seq more))
      (recur (rest more) (assoc opts (keyword (subs flag 2)) (first more)))
      :else (recur more opts))))

;; =============================================================================
;; Help
;; =============================================================================

(defn print-help []
  (println (bold "bb create-admin") "— Create initial admin user for a Boundary project")
  (println)
  (println (bold "Usage:"))
  (println "  bb create-admin                            Interactive wizard (root project)")
  (println "  bb create-admin --dir examples/ecommerce-api  Target a sub-project")
  (println "  bb create-admin --env prod                 Use production config")
  (println "  bb create-admin --email EMAIL --name NAME  Skip email/name prompts")
  (println)
  (println (bold "Options:"))
  (println "  --email EMAIL    Admin user email address")
  (println "  --name  NAME     Admin user full name")
  (println "  --env   ENV      Config environment: dev (default), test, acc, prod")
  (println "  --dir   DIR      Run from this directory (for sub-projects like examples/ecommerce-api)")
  (println "  -h, --help       Show this help")
  (println)
  (println (bold "Notes:"))
  (println "  The password is always collected via a secure prompt (not echoed).")
  (println "  Run database migrations first: clojure -M:migrate up"))

;; =============================================================================
;; Main
;; =============================================================================

(defn -main [& args]
  (let [opts (parse-args args)]

    (when (:help opts)
      (print-help)
      (System/exit 0))

    (println)
    (println (bold (cyan "Boundary — Create Admin User")))
    (println (dim "Sets up the first administrator account for your project."))
    (println)

    ;; Collect email
    (let [email (loop []
                  (let [input (or (:email opts) (prompt "Admin email address"))]
                    (cond
                      (str/blank? input)
                      (do (println (red "  Email is required."))
                          (recur))
                      (not (valid-email? input))
                      (do (println (red "  Not a valid email address."))
                          (recur))
                      :else input)))

          ;; Collect full name
          name  (loop []
                  (let [input (or (:name opts) (prompt "Full name"))]
                    (if (str/blank? input)
                      (do (println (red "  Name is required."))
                          (recur))
                      input)))

          env   (or (:env opts) "dev")
          dir   (:dir opts)]

      (println)
      (println (bold "Summary"))
      (println (str "  Email  : " (cyan email)))
      (println (str "  Name   : " (cyan name)))
      (println (str "  Role   : " (cyan "admin")))
      (println (str "  Config : " (cyan env)))
      (when dir
        (println (str "  Dir    : " (cyan dir))))
      (println)

      ;; Read password here in Babashka where terminal access is guaranteed.
      ;; Pipe it to the subprocess via stdin so it is consumed immediately
      ;; and never stored in the environment or process arguments.
      (let [password (loop []
                       (let [p (if-let [console (System/console)]
                                 (String. (.readPassword console "Password: " (into-array Object [])))
                                 (do (print "Password: ") (flush) (str/trim (or (read-line) ""))))
                             confirm (if-let [console (System/console)]
                                       (String. (.readPassword console "Confirm password: " (into-array Object [])))
                                       (do (print "Confirm password: ") (flush) (str/trim (or (read-line) ""))))]
                         (cond
                           (str/blank? p)   (do (println (red "  Password cannot be empty.")) (recur))
                           (not= p confirm) (do (println (red "  Passwords do not match.")) (recur))
                           (< (count p) 8)  (do (println (red "  Password must be at least 8 characters.")) (recur))
                           :else p)))

            shell-opts (cond-> {:continue true :in (str password "\n")}
                         dir (assoc :dir dir))

            result (p/shell
                    shell-opts
                    "clojure"
                    (str "-J-Denv=" env)
                    "-M:cli:db"
                    "user" "create"
                    "--email" email
                    "--name"  name
                    "--role"  "admin"
                    "--password-prompt")]
        (if (zero? (:exit result))
          (do
            (println)
            (println (green (bold "Admin user created successfully.")))
            (println (dim (str "  You can now log in at your application with: " email))))
          (do
            (println)
            (println (red (bold "Failed to create admin user.")))
            (println (dim "  See the output above for details."))
            (System/exit 1)))))))
