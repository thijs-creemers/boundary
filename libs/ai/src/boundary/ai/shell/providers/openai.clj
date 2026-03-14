(ns boundary.ai.shell.providers.openai
  "OpenAI API provider adapter.

   Implements IAIProvider against the OpenAI Chat Completions API.
   Requires an API key in the configuration."
  (:require [boundary.ai.ports :as ports]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; HTTP helper
;; =============================================================================

(defn- chat-completion-request!
  "POST to OpenAI /v1/chat/completions and return parsed JSON response.

   Args:
     api-key  - OpenAI API key string
     model    - model ID string
     messages - vector of {:role :content} maps
     opts     - map with :temperature :max-tokens

   Returns:
     Parsed JSON map or throws."
  [api-key model messages opts]
  (let [timeout    (or (:timeout opts) 60000)
        body       (cond-> {:model    model
                            :messages (mapv (fn [{:keys [role content]}]
                                              {:role (name role) :content content})
                                            messages)}
                     (:temperature opts) (assoc :temperature (:temperature opts))
                     (:max-tokens opts)  (assoc :max_tokens (:max-tokens opts)))
        response   (http/post "https://api.openai.com/v1/chat/completions"
                              {:body               (json/generate-string body)
                               :content-type       :json
                               :as                 :json
                               :headers            {"Authorization" (str "Bearer " api-key)}
                               :socket-timeout     timeout
                               :connection-timeout 10000
                               :throw-exceptions   true})]
    (:body response)))

;; =============================================================================
;; OpenAIProvider record
;; =============================================================================

(defrecord OpenAIProvider [api-key model]
  ports/IAIProvider

  (complete [_ messages opts]
    (let [effective-model (or (:model opts) model "gpt-4o-mini")]
      (try
        (log/debug "openai complete" {:model effective-model :messages (count messages)})
        (let [resp   (chat-completion-request! api-key effective-model messages opts)
              text   (get-in resp [:choices 0 :message :content])
              tokens (get-in resp [:usage :total_tokens] 0)]
          {:text     text
           :tokens   tokens
           :provider :openai
           :model    effective-model})
        (catch Exception e
          (log/warn e "openai complete failed" {:model effective-model})
          {:error    (.getMessage e)
           :provider :openai
           :model    effective-model}))))

  (complete-json [this messages _schema opts]
    (let [json-opts (assoc opts :response-format {:type "json_object"})
          result    (ports/complete this messages json-opts)]
      (if (:error result)
        result
        (let [parsed (try
                       (json/parse-string (:text result) true)
                       (catch Exception _
                         (let [json-str (re-find #"(?s)\{.*\}" (:text result))]
                           (when json-str
                             (try (json/parse-string json-str true)
                                  (catch Exception _ nil))))))]
          (if parsed
            (assoc result :data parsed)
            (assoc result :error "OpenAI response was not valid JSON" :raw (:text result)))))))

  (provider-name [_] :openai))

;; =============================================================================
;; Factory
;; =============================================================================

(defn create-openai-provider
  "Create an OpenAIProvider.

   Args:
     config - map with:
       :api-key - OpenAI API key string (required)
       :model   - model ID (default \"gpt-4o-mini\")

   Returns:
     OpenAIProvider record."
  [{:keys [api-key model]}]
  (->OpenAIProvider
   api-key
   (or model "gpt-4o-mini")))
