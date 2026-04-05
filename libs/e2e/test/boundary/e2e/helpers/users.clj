(ns boundary.e2e.helpers.users
  "API-level helpers for e2e tests: login, register, MFA enable/disable.
   Uses clj-http directly (not spel) because the e2e suite needs to inspect
   Set-Cookie headers and make fine-grained assertions on status codes."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [boundary.e2e.helpers.totp :as totp]
            [boundary.e2e.helpers.reset :as reset]))

(defn- api-post
  ([path body] (api-post path body nil))
  ([path body {:keys [cookie]}]
   (http/post (str (reset/default-base-url) path)
              (cond-> {:content-type     :json
                       :accept           :json
                       :body             (json/generate-string body)
                       :throw-exceptions false
                       :as               :json}
                cookie (assoc-in [:headers "Cookie"] (str "session-token=" cookie))))))

(defn- api-get
  ([path] (api-get path nil))
  ([path {:keys [cookie]}]
   (http/get (str (reset/default-base-url) path)
             (cond-> {:accept           :json
                      :throw-exceptions false
                      :as               :json}
               cookie (assoc-in [:headers "Cookie"] (str "session-token=" cookie))))))

(defn login
  "POST /api/v1/auth/login — returns the full ring response including headers."
  [{:keys [email password]}]
  (api-post "/api/v1/auth/login" {:email email :password password}))

(defn register
  "POST /api/v1/auth/register — returns the full ring response."
  [{:keys [email password name]}]
  (api-post "/api/v1/auth/register" {:email email :password password :name name}))

(defn enable-mfa!
  "Runs the two-step MFA enable flow (setup → enable with TOTP) using the
   provided session-token. Returns the setup result `{:secret :backupCodes ...}`
   so tests can generate fresh codes or verify backup codes."
  [session-token]
  (let [setup-resp  (api-post "/api/v1/auth/mfa/setup" {} {:cookie session-token})
        _           (when-not (= 200 (:status setup-resp))
                      (throw (ex-info "mfa/setup failed" {:resp setup-resp})))
        setup       (:body setup-resp)
        code        (totp/current-code (:secret setup))
        enable-resp (api-post "/api/v1/auth/mfa/enable"
                              {:secret           (:secret setup)
                               :backupCodes      (:backupCodes setup)
                               :verificationCode code}
                              {:cookie session-token})]
    (when-not (= 200 (:status enable-resp))
      (throw (ex-info "mfa/enable failed" {:resp enable-resp})))
    setup))

(defn disable-mfa!
  "POST /api/v1/auth/mfa/disable — no body."
  [session-token]
  (api-post "/api/v1/auth/mfa/disable" {} {:cookie session-token}))

(defn mfa-status
  "GET /api/v1/auth/mfa/status — returns response with body."
  [session-token]
  (api-get "/api/v1/auth/mfa/status" {:cookie session-token}))
