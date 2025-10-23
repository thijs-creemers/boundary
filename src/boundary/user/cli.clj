(ns boundary.user.cli
  "CLI commands for user management."
  (:require [boundary.user.ports :as ports]
            [clojure.tools.logging :as log]))

(defn parse-args
  "Parse command-line arguments into a map."
  [args]
  (loop [remaining args
         result {}]
    (if (empty? remaining)
      result
      (let [[flag value & rest] remaining]
        (if (and flag value (.startsWith flag "--"))
          (let [key (keyword (subs flag 2))]
            (recur rest (assoc result key value)))
          (recur (rest remaining) result))))))

(defn create-user
  "Create a new user."
  [user-service {:keys [email name role tenant-id]}]
  (try
    (let [user-data {:email email
                    :name name
                    :role (keyword role)
                    :tenant-id (java.util.UUID/fromString tenant-id)}]
      (log/info "Creating user" {:email email})
      (let [result (ports/create-user user-service user-data)]
        (println "User created successfully:")
        (println "  ID:" (:id result))
        (println "  Email:" (:email result))
        (println "  Name:" (:name result))
        (println "  Role:" (name (:role result)))))
    (catch Exception e
      (log/error "Failed to create user" {:error (.getMessage e)})
      (println "Error creating user:" (.getMessage e))
      (System/exit 1))))

(defn run-cli
  "Main CLI dispatcher."
  [user-service args]
  (if (empty? args)
    (do
      (println "Usage: boundary user <command> [options]")
      (println "")
      (println "Commands:")
      (println "  create    Create a new user")
      (println "")
      (println "Options:")
      (println "  --email      User email address")
      (println "  --name       User full name")
      (println "  --role       User role (admin, user, viewer)")
      (println "  --tenant-id  Tenant UUID")
      (System/exit 0))
    (let [[module command & rest-args] args
          parsed-args (parse-args rest-args)]
      (when (= module "user")
        (case command
          "create" (create-user user-service parsed-args)
          (do
            (println "Unknown command:" command)
            (System/exit 1)))))))
