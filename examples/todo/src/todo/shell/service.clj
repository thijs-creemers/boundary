(ns todo.shell.service
  "Imperative shell: orchestrates validation, the pure core, and persistence.
   Validation lives here (not in the core) so the core receives clean data."
  (:require [todo.core.todo :as core]
            [todo.ports :as ports]))

(defn add-todo!
  "Validate a title, build the entity in the core, persist it via the repo, and
   return the created todo. `now` is injected so the core stays deterministic."
  [repo title now]
  (if (core/valid-input? {:title title})
    (let [todo (core/prepare-todo title (random-uuid) now)]
      (ports/save-todo! repo todo)
      todo)
    (throw (ex-info "Invalid todo title"
                    {:type :validation-error :title title}))))

(defn list-todos [repo] (ports/all-todos repo))

(defn complete! [repo id] (ports/set-done! repo id true))
