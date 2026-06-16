(ns boundary.mcp.shell.dispatch
  "Shell-level request dispatch. Pure protocol methods delegate to
   boundary.mcp.core.handlers; resources/read is handled here because it needs
   I/O (snapshot reflection, JSON serialization), the security gate, and the
   guardrail payload on denial.

   `deps` is {:registry :security :audit :system-source}."
  (:require [boundary.mcp.core.handlers :as handlers]
            [boundary.mcp.core.protocol :as proto]
            [boundary.mcp.core.resources :as resources]
            [boundary.mcp.core.security :as security]
            [boundary.mcp.ports :as ports]
            [boundary.mcp.shell.codec :as codec]
            [boundary.mcp.shell.guardrail :as guardrail]))

(defn- resource-read
  [deps id params]
  (let [{sec :security, audit :audit, src :system-source} deps
        uri      (:uri params)
        decision (security/authorize sec {:name       (str "resource:" uri)
                                          :capability resources/resource-capability})]
    (if (:allow? decision)
      (let [data (resources/read-resource (ports/snapshot src) uri)]
        (if (nil? data)
          (proto/error id :invalid-params (str "Unknown resource: " uri) {:uri uri})
          (do
            (when audit (ports/record! audit {:event :resource-read :uri uri}))
            (proto/success id {:contents [{:uri      uri
                                           :mimeType (resources/mime-type uri)
                                           :text     (codec/encode data)}]}))))
      (let [resp (guardrail/error-for-denial id decision)]
        (when audit (ports/record! audit {:event :resource-read-denied
                                          :uri   uri
                                          :code  (get-in resp [:error :data :code])}))
        resp))))

(defn dispatch
  "Dispatch a parsed JSON-RPC message against `deps`. resources/read is handled
   in the shell; every other method delegates to the pure core handler."
  [deps msg]
  (let [{:keys [id method params]} msg]
    (case method
      "resources/read" (when (proto/request? msg) (resource-read deps id params))
      (handlers/handle (:registry deps) msg))))
