(ns boundary.scaffolder.shell.file-system
  "File system adapter for module generation.
   
   Provides concrete implementation of file system operations."
  (:require [boundary.scaffolder.ports :as ports]
            [clojure.java.io :as io])
  (:import [java.io File]))

(defrecord FileSystemAdapter [base-path]
  ports/IFileSystemAdapter

  (file-exists? [this path]
    (let [full-path (io/file base-path path)]
      (.exists full-path)))

  (read-file [this path]
    (let [full-path (io/file base-path path)]
      (when (.exists full-path)
        (slurp full-path))))

  (write-file [this path content]
    (let [full-path (io/file base-path path)]
      ;; Create parent directories if needed
      (io/make-parents full-path)
      (spit full-path content)
      true))

  (delete-file [this path]
    (let [full-path (io/file base-path path)]
      (when (.exists full-path)
        (io/delete-file full-path)
        true)))

  (list-files [this directory]
    (let [dir (io/file base-path directory)]
      (when (.exists dir)
        (->> (.listFiles dir)
             (filter #(.isFile %))
             (map #(.getName %))
             vec)))))

(defn create-file-system-adapter
  "Create a new file system adapter.
   
   Args:
     base-path - Base path for file operations (defaults to current directory)
   
   Returns:
     FileSystemAdapter instance"
  ([]
   (create-file-system-adapter "."))
  ([base-path]
   (->FileSystemAdapter base-path)))
