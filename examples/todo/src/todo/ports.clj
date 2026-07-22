(ns todo.ports
  "The port (protocol) the shell depends on. The core never touches it — it
   works on plain data. Swap the H2 implementation for another store without
   changing the core or the service.")

(defprotocol ITodoRepository
  (save-todo! [this todo] "Persist a todo entity.")
  (all-todos  [this]      "Return all todos, oldest first.")
  (set-done!  [this id done?] "Mark the todo with `id` done/undone."))
