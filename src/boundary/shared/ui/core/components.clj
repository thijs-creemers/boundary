(ns boundary.shared.ui.core.components
  "Pure UI component functions for HTML generation using Hiccup.
   
   These are reusable, generic components that can be used across all domain modules.
   No domain-specific logic should be placed here - only pure presentation functions."
  (:require [hiccup2.core :as h]))

;; =============================================================================
;; Form Components
;; =============================================================================

(defn text-input
  "Generic text input component.
   
   Args:
     field-key: Keyword for input name/id
     value: Current input value
     opts: Optional map with :placeholder, :type, :required, :pattern, :class, etc.
     
   Returns:
     Hiccup input element"
  [field-key value & [opts]]
  (let [{:keys [type] :or {type "text"}} opts
        base-attrs {:type type
                    :id (name field-key)
                    :name (name field-key)
                    :value (or value "")}
        ;; Remove type from opts since we handle it separately
        filtered-opts (dissoc opts :type)]
    [:input (merge base-attrs filtered-opts)]))

(defn email-input
  "Email input component.
   
   Args:
     field-key: Keyword for input name/id
     value: Current input value
     opts: Optional map with additional attributes
     
   Returns:
     Hiccup email input element"
  [field-key value & [opts]]
  (text-input field-key value (assoc opts :type "email")))

(defn password-input
  "Password input component.
   
   Args:
     field-key: Keyword for input name/id
     value: Current input value
     opts: Optional map with additional attributes
     
   Returns:
     Hiccup password input element"
  [field-key value & [opts]]
  (text-input field-key value (assoc opts :type "password")))

(defn textarea
  "Textarea component.
   
   Args:
     field-key: Keyword for textarea name/id
     value: Current textarea value
     opts: Optional map with :rows, :cols, :placeholder, etc.
     
   Returns:
     Hiccup textarea element"
  [field-key value & [opts]]
  (let [{:keys [rows cols placeholder class]
         :or {rows 4 cols 50}} opts]
    [:textarea (merge {:id (name field-key)
                       :name (name field-key)
                       :rows rows
                       :cols cols}
                      (when placeholder {:placeholder placeholder})
                      (when class {:class class}))
     (or value "")]))

(defn select-field
  "Select dropdown component.
   
   Args:
     field-key: Keyword for select name/id
     options: Vector of [value label] pairs or map of value->label
     selected-value: Currently selected value
     opts: Optional map with additional attributes
     
   Returns:
     Hiccup select element"
  [field-key options selected-value & [opts]]
  (let [{:keys [class]} opts
        option-pairs (if (map? options)
                       (seq options)
                       options)
        ;; Normalize selected-value to string for comparison
        selected-str (if (keyword? selected-value) (name selected-value) (str selected-value))]
    [:select (merge {:id (name field-key)
                     :name (name field-key)}
                    (when class {:class class}))
     (for [[value label] option-pairs]
       (let [;; Convert value to string for HTML attribute
             value-str (if (keyword? value) (name value) (str value))]
         [:option {:value value-str
                   :selected (= value-str selected-str)}
          label]))]))

(defn checkbox
  "Checkbox component.
   
   Args:
     field-key: Keyword for checkbox name/id
     checked?: Boolean indicating if checkbox is checked
     opts: Optional map with additional attributes like :value, :required, :class
     
   Returns:
     Hiccup checkbox input element"
  [field-key checked? & [opts]]
  (let [base-attrs {:type "checkbox"
                    :id (name field-key)
                    :name (name field-key)}
        ;; Only add :checked attribute if the checkbox is checked
        checked-attrs (when checked? {:checked true})]
    [:input (merge base-attrs checked-attrs opts)]))

(defn form-field
  "Form field wrapper with label and error display.
   
   Args:
     field-key: Keyword for field identification
     label: String label for the field
     input-html: Hiccup structure for the input element
     errors: Optional collection of error messages
     
   Returns:
     Hiccup form field structure"
  [field-key label input-html errors]
  [:div {:class "form-field"}
   [:label {:for (name field-key)} label]
   input-html
   (when (seq errors)
     [:div.field-errors
      (for [error errors]
        [:span.error error])])])

;; =============================================================================
;; Button Components
;; =============================================================================

(defn button
  "Generic button component.
   
   Args:
     text: Button text
     opts: Optional map with :type, :class, :disabled, :onclick, etc.
     
   Returns:
     Hiccup button element"
  [text & [opts]]
  (let [{:keys [type class disabled onclick]} opts]
    [:button (merge {:type (or type "button")}
                    (when class {:class class})
                    (when disabled {:disabled disabled})
                    (when onclick {:onclick onclick}))
     text]))

(defn submit-button
  "Submit button component.
   
   Args:
     text: Button text
     opts: Optional map with :class, :loading-text, :hx-indicator, etc.
     
   Returns:
     Hiccup submit button element"
  [text & [opts]]
  (let [{:keys [class loading-text hx-indicator]
         :or {class "button primary"}} opts]
    [:button (merge {:type "submit"
                     :class class}
                    (when loading-text {:data-loading-text loading-text})
                    (when hx-indicator {:hx-indicator hx-indicator}))
     text]))

(defn link-button
  "Link styled as button component.
   
   Args:
     text: Link text
     href: Link URL
     opts: Optional map with :class, :target, etc.
     
   Returns:
     Hiccup link element styled as button"
  [text href & [opts]]
  (let [{:keys [class target]} opts]
    [:a (merge {:href href
                :class (or class "button")}
               (when target {:target target}))
     text]))

;; =============================================================================
;; Table Components
;; =============================================================================

(defn data-table
  "Generic data table component.
   
   Args:
     headers: Vector of header strings
     rows: Vector of row vectors (each row is vector of cell values)
     opts: Optional map with :id, :class, HTMX attributes, etc.
     
   Returns:
     Hiccup table structure"
  [headers rows & [opts]]
  (let [{:keys [id class hx-get hx-target hx-trigger]} opts]
    (if (empty? rows)
      [:div.empty-state "No data available."]
      [:table (merge {:class (or class "data-table")}
                     (when id {:id id})
                     (when hx-get {:hx-get hx-get})
                     (when hx-target {:hx-target hx-target})
                     (when hx-trigger {:hx-trigger hx-trigger}))
       [:thead
        [:tr
         (for [header headers]
           [:th header])]]
       [:tbody
        (for [row rows]
          [:tr
           (for [cell row]
             [:td cell])])]])))

;; =============================================================================
;; Message Components
;; =============================================================================

(defn success-message
  "Success message component.
   
   Args:
     message: Success message text
     opts: Optional map with additional attributes
     
   Returns:
     Hiccup success message structure"
  [message & [opts]]
  (let [{:keys [class]} opts]
    [:div {:class (or class "alert alert-success")}
     message]))

(defn error-message
  "Error message component.
   
   Args:
     message: Error message text
     opts: Optional map with additional attributes
     
   Returns:
     Hiccup error message structure"
  [message & [opts]]
  (let [{:keys [class]} opts]
    [:div {:class (or class "alert alert-error")}
     message]))

(defn info-message
  "Info message component.
   
   Args:
     message: Info message text
     opts: Optional map with additional attributes
     
   Returns:
     Hiccup info message structure"
  [message & [opts]]
  (let [{:keys [class]} opts]
    [:div {:class (or class "alert alert-info")}
     message]))

(defn validation-errors
  "Validation errors display component.
   
   Args:
     errors: Map of field->errors or collection of error messages
     
   Returns:
     Hiccup validation errors structure"
  [errors]
  (when (seq errors)
    [:div.validation-errors
     [:h4 "Please correct the following errors:"]
     (cond
       (map? errors)
       [:ul
        (for [[field field-errors] errors
              error field-errors]
          (let [field-label (when field (name field))]
            [:li (if field-label
                   (str field-label ": " error)
                   (str error))]))]

       (coll? errors)
       [:ul
        (for [error errors]
          [:li error])]

       :else
       [:p errors])]))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn render-html
  "Convert Hiccup data structure to HTML string.
   
   Args:
     hiccup-data: Hiccup data structure
     
   Returns:
     HTML string"
  [hiccup-data]
  ;; Hiccup2 returns RawString, must call str to get plain String for Ring
  (str (h/html hiccup-data)))

(defn htmx-attrs
  "Helper to generate HTMX attributes map.
   
   Args:
     opts: Map with HTMX options like :hx-get, :hx-post, :hx-target, etc.
     
   Returns:
     Map of HTMX attributes"
  [opts]
  (let [htmx-keys [:hx-get :hx-post :hx-put :hx-delete :hx-patch
                   :hx-target :hx-trigger :hx-swap :hx-indicator
                   :hx-confirm :hx-boost]]
    (select-keys opts htmx-keys)))