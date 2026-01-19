(ns boundary.user.core.profile-ui
  "Profile-specific UI components for user profile management.
   
   This namespace contains pure functions for generating profile-related Hiccup
   structures including profile viewing, editing, password changes, and MFA setup."
  (:require [boundary.shared.ui.core.components :as ui]
            [boundary.shared.ui.core.layout :as layout]
            [boundary.shared.ui.core.icons :as icons]
            [clojure.string :as str]))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- format-date
  "Format an Instant for display."
  [instant]
  (when instant
    (-> instant
        str
        (str/replace #"T" " ")
        (str/replace #"\.\d+Z" ""))))

(defn- format-role
  "Format role keyword for display."
  [role]
  (when role
    (-> role name str/capitalize)))

;; =============================================================================
;; Profile Information Components
;; =============================================================================

(defn profile-info-card
  "Display user profile information card (read-only view).
   
   Args:
     user: User entity map
     
   Returns:
     Hiccup structure for profile info card"
  [user]
  [:div.profile-card
    [:div.card-header
     [:h2 "Profile Information"]
     [:button.button.secondary {:type "button"
                                :hx-get "/web/profile/edit"
                                :hx-target "#profile-info-card"
                                :hx-swap "outerHTML"}
      (icons/icon :edit {:size 16})
      " Edit"]]
   [:div#profile-info-card.card-body
    [:div.info-row
     [:label "Name"]
     [:span (:name user)]]
    [:div.info-row
     [:label "Email"]
     [:span (:email user)]]
    [:div.info-row
     [:label "Role"]
     [:span.role-badge {:class (name (:role user))}
      (format-role (:role user))]]
    [:div.info-row
     [:label "Member since"]
     [:span (format-date (:created-at user))]]]])

(defn profile-edit-form
  "Display editable profile form (HTMX partial).
   
   Args:
     user: User entity map
     errors: Validation errors map (optional)
     
   Returns:
     Hiccup structure for profile edit form"
  ([user] (profile-edit-form user {}))
  ([user errors]
   [:div#profile-info-card.card-body
    [:form {:hx-post "/web/profile"
            :hx-target "#profile-info-card"
            :hx-swap "outerHTML"}
     (ui/form-field :name "Name"
                    (ui/text-input :name (:name user) {:required true})
                    (:name errors))
     [:div.info-row
      [:label "Email"]
      [:span (:email user) " "
       [:small.text-muted "(cannot be changed)"]]]
     [:div.info-row
      [:label "Role"]
      [:span.role-badge {:class (name (:role user))}
       (format-role (:role user)) " "
       [:small.text-muted "(managed by admins)"]]]
     [:div.form-actions
      [:button.button.primary {:type "submit"}
       (icons/icon :save {:size 16})
       " Save Changes"]
      [:button.button.secondary {:type "button"
                                 :hx-get "/web/profile"
                                 :hx-target "#profile-info-card"
                                 :hx-swap "outerHTML"}
       "Cancel"]]]]))

(defn preferences-card
  "Display user preferences card.
   
   Args:
     user: User entity map
     
   Returns:
     Hiccup structure for preferences card"
  [user]
  [:div.profile-card
    [:div.card-header
     [:h2 "Preferences"]
     [:button.button.secondary {:type "button"
                                :hx-get "/web/profile/preferences/edit"
                                :hx-target "#preferences-card"
                                :hx-swap "outerHTML"}
      (icons/icon :edit {:size 16})
      " Edit"]]
   [:div#preferences-card.card-body
    [:div.info-row
     [:label "Date Format"]
     [:span (case (:date-format user)
              :iso "ISO (2024-01-10)"
              :us "US (01/10/2024)"
              :eu "EU (10/01/2024)"
              "Not set")]]
    [:div.info-row
     [:label "Time Format"]
     [:span (case (:time-format user)
              :12h "12-hour (3:30 PM)"
              :24h "24-hour (15:30)"
              "Not set")]]]])

(defn preferences-edit-form
  "Display editable preferences form (HTMX partial).
   
   Args:
     user: User entity map
     errors: Validation errors map (optional)
     
   Returns:
     Hiccup structure for preferences edit form"
  ([user] (preferences-edit-form user {}))
  ([user errors]
   [:div#preferences-card.card-body
    [:form {:hx-post "/web/profile/preferences"
            :hx-target "#preferences-card"
            :hx-swap "outerHTML"}
     [:div.form-field
      [:label {:for "date-format"} "Date Format"]
      [:select#date-format {:name "date-format"}
       [:option {:value "iso" :selected (= :iso (:date-format user))} "ISO (2024-01-10)"]
       [:option {:value "us" :selected (= :us (:date-format user))} "US (01/10/2024)"]
       [:option {:value "eu" :selected (= :eu (:date-format user))} "EU (10/01/2024)"]]
      (when (:date-format errors)
        [:span.error (first (:date-format errors))])]
     [:div.form-field
      [:label {:for "time-format"} "Time Format"]
      [:select#time-format {:name "time-format"}
       [:option {:value "12h" :selected (= :12h (:time-format user))} "12-hour (3:30 PM)"]
       [:option {:value "24h" :selected (= :24h (:time-format user))} "24-hour (15:30)"]]
      (when (:time-format errors)
        [:span.error (first (:time-format errors))])]
     [:div.form-actions
      [:button.button.primary {:type "submit"}
       (icons/icon :save {:size 16})
       " Save Preferences"]
      [:button.button.secondary {:type "button"
                                 :hx-get "/web/profile"
                                 :hx-target "#preferences-card"
                                 :hx-swap "outerHTML"}
       "Cancel"]]]]))

;; =============================================================================
;; Password Change Components
;; =============================================================================

(defn password-change-card
  "Display password change card in security section.
   
   Args:
     expanded?: Boolean indicating if form should be shown
     errors: Validation errors map (optional)
     
   Returns:
     Hiccup structure for password change card"
  ([expanded?] (password-change-card expanded? {}))
  ([expanded? errors]
    [:div.profile-card
     [:div.card-header
      [:h2 "Password"]
      [:span.text-muted "••••••••"]]
    (if-not expanded?
      [:div#password-section.card-body
       [:button.button.secondary {:type "button"
                                  :hx-get "/web/profile/password/form"
                                  :hx-target "#password-section"
                                  :hx-swap "outerHTML"}
        (icons/icon :key {:size 16})
        " Change Password"]]
      [:div#password-section.card-body
       [:form {:hx-post "/web/profile/password"
               :hx-target "#password-section"
               :hx-swap "outerHTML"}
        (ui/form-field :current-password "Current Password"
                       (ui/password-input :current-password "" {:required true})
                       (:current-password errors))
        (ui/form-field :new-password "New Password"
                       (ui/password-input :new-password "" {:required true})
                       (:new-password errors))
        (ui/form-field :confirm-password "Confirm New Password"
                       (ui/password-input :confirm-password "" {:required true})
                       (:confirm-password errors))
        [:div.form-actions
         [:button.button.primary {:type "submit"}
          (icons/icon :save {:size 16})
          " Change Password"]
         [:button.button.secondary {:type "button"
                                    :hx-get "/web/profile"
                                    :hx-target "body"
                                    :hx-swap "innerHTML"}
          "Cancel"]]]])]))

(defn password-change-success
  "Display success message after password change.
   
   Returns:
     Hiccup structure for success message"
  []
  [:div#password-section.card-body
   [:div.alert.alert-success
    (icons/icon :check-circle {:size 20})
    " Password changed successfully"]
   [:button.button.secondary {:hx-get "/web/profile"
                              :hx-target "body"
                              :hx-swap "innerHTML"}
    "Back to Profile"]])

;; =============================================================================
;; MFA Status Components
;; =============================================================================

(defn mfa-status-card
  "Display MFA status card with enable/disable actions.
   
   Args:
     user: User entity map
     mfa-status: MFA status map from mfa-service
     
   Returns:
     Hiccup structure for MFA status card"
  [user mfa-status]
   [:div.profile-card
    [:div.card-header
     [:h2 "Two-Factor Authentication"]
    (if (:enabled mfa-status)
      [:span.status-badge.active
       (icons/icon :shield {:size 16})
       " Enabled"]
      [:span.status-badge.inactive
       (icons/icon :shield {:size 16})
       " Not Enabled"])]
   [:div.card-body
    (if (:enabled mfa-status)
      ;; MFA is enabled
      [:div
       [:div.info-row
        [:label "Status"]
        [:span (icons/icon :check-circle {:size 16 :class "text-success"})
         " Two-factor authentication is active"]]
       (when (:enabled-at mfa-status)
         [:div.info-row
          [:label "Enabled since"]
          [:span (format-date (:enabled-at mfa-status))]])
        [:div.info-row
         [:label "Backup codes"]
         [:span (:backup-codes-remaining mfa-status) " remaining"]]
        [:div.form-actions
         [:a.button.danger {:href "/web/profile/mfa/disable"}
          (icons/icon :unlock {:size 16})
          " Disable MFA"]]]
      ;; MFA is not enabled
      [:div
       [:p "Add an extra layer of security to your account by enabling two-factor authentication."]
       [:div.form-actions
        [:a.button.primary {:href "/web/profile/mfa/setup"}
         (icons/icon :shield {:size 16})
         " Enable Two-Factor Authentication"]]])]])

;; =============================================================================
;; MFA Setup Components
;; =============================================================================

(defn mfa-setup-page
  "Display MFA setup page with instructions.
   
   Args:
     opts: Optional map with :user, :flash, etc.
     
   Returns:
     Complete HTML page for MFA setup"
  [& [opts]]
  (layout/page-layout
   "Set Up Two-Factor Authentication"
   [:div.profile-page
    [:div.page-header
     [:h1
      [:a {:href "/web/profile"} (icons/icon :chevron-left {:size 20})]
      " Set Up Two-Factor Authentication"]]
    [:div.profile-content
     [:div.mfa-setup-intro
      [:p "Two-factor authentication adds an extra layer of security to your account. You'll need:"]
      [:ul
       [:li "An authenticator app (Google Authenticator, Authy, 1Password, etc.)"]
       [:li "Access to your current password"]]
      [:div.form-actions
       [:button.button.primary {:hx-post "/web/profile/mfa/setup"
                                :hx-target "#mfa-setup-content"
                                :hx-swap "innerHTML"}
        (icons/icon :shield {:size 16})
        " Start Setup"]
       [:a.button.secondary {:href "/web/profile"}
        "Cancel"]]]
     [:div#mfa-setup-content]]]
   opts))

(defn mfa-qr-code-step
  "Display QR code and verification step (HTMX fragment).
   
   Args:
     secret: TOTP secret
     qr-code-url: QR code image URL
     issuer: Application name
     account-name: User's email
     errors: Validation errors (optional)
     
   Returns:
     Hiccup structure for QR code step"
  ([secret qr-code-url issuer account-name]
   (mfa-qr-code-step secret qr-code-url issuer account-name [] {}))
  ([secret qr-code-url issuer account-name backup-codes]
   (mfa-qr-code-step secret qr-code-url issuer account-name backup-codes {}))
  ([secret qr-code-url issuer account-name backup-codes errors]
   [:div.mfa-setup-steps
    [:div.mfa-step
     [:h3 "Step 1: Scan QR Code"]
     [:p "Scan this QR code with your authenticator app:"]
     [:div.qr-code-container
      [:img {:src qr-code-url
             :alt "QR Code for Two-Factor Authentication"
             :width "200"
             :height "200"}]]
     [:div.manual-entry
      [:p "Can't scan? Enter this code manually:"]
      [:div.secret-code
       [:code secret]
       [:button.button.secondary.small {:type "button"
                                        :onclick (str "navigator.clipboard.writeText('" secret "');"
                                                      "this.textContent='Copied!';"
                                                      "setTimeout(()=>this.textContent='Copy',2000);")}
        "Copy"]]]]
    [:div.mfa-step
     [:h3 "Step 2: Verify Setup"]
     [:p "Enter the 6-digit code from your authenticator app:"]
     [:form {:hx-post "/web/profile/mfa/verify"
             :hx-target "#mfa-setup-content"
             :hx-swap "innerHTML"}
       ;; Store secret and backup codes in hidden fields for verification
      [:input {:type "hidden" :name "secret" :value secret}]
      [:input {:type "hidden" :name "backup-codes" :value (pr-str backup-codes)}]
      (ui/form-field :verification-code "Verification Code"
                     (ui/text-input :verification-code ""
                                    {:required true
                                     :maxlength "6"
                                     :pattern "[0-9]{6}"
                                     :placeholder "000000"})
                     (:verification-code errors))
      [:div.form-actions
       [:button.button.primary {:type "submit"}
        (icons/icon :check {:size 16})
        " Verify Code"]
       [:a.button.secondary {:href "/web/profile"}
        "Cancel"]]]]]))

(defn mfa-backup-codes-display
  "Display backup codes after MFA enable (HTMX fragment or full page).
   
   Args:
     backup-codes: Vector of backup code strings
     full-page?: Boolean indicating if this should be a full page
     opts: Optional map with :user, :flash, etc.
     
   Returns:
     Hiccup structure for backup codes display"
  ([backup-codes] (mfa-backup-codes-display backup-codes false {}))
  ([backup-codes full-page?] (mfa-backup-codes-display backup-codes full-page? {}))
  ([backup-codes full-page? opts]
   (let [content
         [:div.mfa-backup-codes
          [:div.alert.alert-success
           (icons/icon :check-circle {:size 20})
           " Two-Factor Authentication Enabled!"]
          [:h2 "Save Your Backup Codes"]
          [:p "Store these codes somewhere safe. Each code can only be used once if you lose access to your authenticator app."]
          [:div.backup-codes-grid
           (for [[idx code] (map-indexed vector backup-codes)]
             [:div.backup-code-item {:key idx}
              [:span.code-number (str (inc idx) ".")]
              [:code code]])]
          [:div.backup-codes-actions
           [:button.button.secondary {:type "button"
                                      :onclick (str "const codes = "
                                                    (pr-str (str/join "\n" backup-codes))
                                                    ";"
                                                    "navigator.clipboard.writeText(codes);"
                                                    "this.textContent='Copied!';"
                                                    "setTimeout(()=>this.textContent='Copy All',2000);")}
            (icons/icon :copy {:size 16})
            " Copy All"]
           [:button.button.secondary {:type "button"
                                      :onclick "window.print();"}
            (icons/icon :download {:size 16})
            " Print"]]
          [:div.backup-codes-confirm
           [:label
            [:input {:type "checkbox" :id "codes-saved"}]
            " I have saved these backup codes in a safe place"]]
          [:div.form-actions
           [:a.button.primary {:href "/web/profile"}
            "Done - Return to Profile"]]]]
     (if full-page?
       (layout/page-layout
        "Two-Factor Authentication Backup Codes"
        [:div.profile-page
         [:div.page-header
          [:h1 "Backup Codes"]]
         [:div.profile-content
          content]]
        opts)
       content))))

;; =============================================================================
;; MFA Disable Components
;; =============================================================================

(defn mfa-disable-confirm-page
  "Display MFA disable confirmation page with password verification.
   
   Args:
     errors: Validation errors map (optional)
     opts: Optional map with :user, :flash, etc.
     
   Returns:
     Complete HTML page for MFA disable confirmation"
  ([errors] (mfa-disable-confirm-page errors {}))
  ([errors opts]
   (layout/page-layout
    "Disable Two-Factor Authentication"
    [:div.profile-page
     [:div.page-header
      [:h1
       [:a {:href "/web/profile"} (icons/icon :chevron-left {:size 20})]
       " Disable Two-Factor Authentication"]]
     [:div.profile-content
      [:div.alert.alert-warning
       (icons/icon :alert-circle {:size 20})
       " Warning: Disabling two-factor authentication will make your account less secure."]
      [:div.profile-card
       [:div.card-body
        [:p "To disable two-factor authentication, please confirm your password:"]
        [:form {:method "POST" :action "/web/profile/mfa/disable"}
         (ui/form-field :password "Current Password"
                        (ui/password-input :password "" {:required true})
                        (:password errors))
         [:div.form-actions
          [:button.button.danger {:type "submit"}
           (icons/icon :unlock {:size 16})
           " Disable Two-Factor Authentication"]
          [:a.button.secondary {:href "/web/profile"}
           "Cancel"]]]]]]]
    opts)))

;; =============================================================================
;; Main Profile Page
;; =============================================================================

(defn profile-page
  "Display main profile page with all sections.
   
   Args:
     user: User entity map
     mfa-status: MFA status map from mfa-service
     opts: Optional map with :user (for layout), :flash, etc.
     
   Returns:
     Complete HTML page for user profile"
  [user mfa-status & [opts]]
   (layout/page-layout
    "Profile & Security"
    [:div.profile-page
     [:div.page-header
      [:h1 "Profile & Security"]]
     [:div.profile-content
      (profile-info-card user)
      (preferences-card user)
      (password-change-card false)
      (mfa-status-card user mfa-status)
      [:div.profile-card
       [:div.card-header
        [:h2 "Active Sessions"]]
       [:div.card-body
        [:div.info-row
         [:label "Sessions"]
         [:span
          [:a {:href (str "/web/users/" (:id user) "/sessions")}
           "Manage Sessions "
           (icons/icon :chevron-right {:size 16})]]]]]]]
   opts))
