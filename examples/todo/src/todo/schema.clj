(ns todo.schema
  "Malli schemas for the todo entity and its input. All keys are kebab-case —
   conversion to the database's snake_case happens only at the persistence
   boundary (see todo.shell.persistence).")

(def Todo
  [:map
   [:id         :uuid]
   [:title      [:string {:min 1 :max 200}]]
   [:done?      :boolean]
   [:created-at inst?]])

(def TodoInput
  [:map
   [:title [:string {:min 1 :max 200}]]])
