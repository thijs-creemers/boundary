(ns boundary.workflow.ports)

(defprotocol IWorkflowRepository
  "Workflow process and state persistence."

             ;; Process instance operations
  (create-process-instance [this process-entity]
    "Create new workflow process instance.")

  (find-process-by-id [this process-id]
    "Retrieve process instance by ID.")

  (update-process-state [this process-id new-state state-data]
    "Update process state with transition data.")

  (find-active-processes-by-type [this tenant-id process-type]
    "Find all active processes of specific type.")

             ;; State transition history
  (record-state-transition [this transition-entity]
    "Record state transition for audit and replay.")

  (find-transition-history [this process-id]
    "Retrieve complete state transition history for process.")

             ;; Task management
  (create-workflow-task [this task-entity]
    "Create new workflow task.")

  (find-pending-tasks-by-assignee [this assignee-id tenant-id]
    "Find tasks assigned to specific user.")

  (complete-task [this task-id completion-data]
    "Mark task as completed with result data."))