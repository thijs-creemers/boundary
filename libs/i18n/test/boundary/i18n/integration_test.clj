(ns boundary.i18n.integration-test
  "Integration tests for i18n language switching end-to-end.

   Verifies that setting a user's :language preference causes UI
   output to render in the correct locale. Uses real catalogue from
   disk rather than mocks."
  (:require [boundary.i18n.shell.catalogue :as catalogue]
            [boundary.i18n.shell.render :as render]
            [boundary.i18n.shell.middleware :as middleware]
            [boundary.i18n.core.translate :as translate]
            [clojure.test :refer [deftest is testing]]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- make-t-fn
  "Build a t-fn for the given locale chain using the real catalogue."
  [cat locale-chain]
  (fn
    ([key]         (translate/t cat locale-chain key))
    ([key params]  (translate/t cat locale-chain key params))
    ([key params n] (translate/t cat locale-chain key params n))))

;; =============================================================================
;; Language switching
;; =============================================================================

(deftest ^:integration dutch-user-sees-dutch-strings
  (testing "Dutch user sees Dutch strings in rendered output"
    (let [cat      (catalogue/load-catalogue "boundary/i18n/translations")
          t-fn     (make-t-fn cat [:nl :en])
          hiccup   [:div
                    [:span [:t :user/badge-active]]
                    [:span [:t :user/badge-inactive]]]
          html     (render/render hiccup t-fn)]
      (is (re-find #"Actief" html)   "should contain Dutch 'Actief'")
      (is (re-find #"Inactief" html) "should contain Dutch 'Inactief'")
      (is (not (re-find #"\bActive\b" html))   "should not contain English 'Active'")
      (is (not (re-find #"\bInactive\b" html)) "should not contain English 'Inactive'")))

  (testing "English user sees English strings in rendered output"
    (let [cat      (catalogue/load-catalogue "boundary/i18n/translations")
          t-fn     (make-t-fn cat [:en])
          hiccup   [:div [:span [:t :user/badge-active]]]
          html     (render/render hiccup t-fn)]
      (is (re-find #"Active" html) "should contain English 'Active'"))))

(deftest ^:integration locale-fallback-to-english
  (testing "Unknown locale falls back to English"
    (let [cat    (catalogue/load-catalogue "boundary/i18n/translations")
          t-fn   (make-t-fn cat [:fr :en])
          hiccup [:div [:span [:t :user/badge-active]]]
          html   (render/render hiccup t-fn)]
      (is (re-find #"Active" html) "should fall back to English 'Active' for unsupported :fr locale"))))

(deftest ^:integration interpolation-across-locales
  (testing "Interpolation works in Dutch locale"
    (let [cat    (catalogue/load-catalogue "boundary/i18n/translations")
          t-fn   (make-t-fn cat [:nl :en])
          hiccup [:div [:t :user/dashboard-welcome {:name "Thijs"}]]
          html   (render/render hiccup t-fn)]
      (is (re-find #"Thijs" html) "interpolated name should appear in output")))

  (testing "Interpolation works in English locale"
    (let [cat    (catalogue/load-catalogue "boundary/i18n/translations")
          t-fn   (make-t-fn cat [:en])
          hiccup [:div [:t :user/dashboard-welcome {:name "Alice"}]]
          html   (render/render hiccup t-fn)]
      (is (re-find #"Alice" html) "interpolated name should appear in output"))))

;; =============================================================================
;; wrap-i18n middleware
;; =============================================================================

(deftest ^:integration wrap-i18n-injects-t-fn
  (testing "wrap-i18n injects :i18n/t and :i18n/locale-chain into request"
    (let [cat      (catalogue/load-catalogue "boundary/i18n/translations")
          captured (atom nil)
          handler  (fn [req] (reset! captured req) {:status 200 :body ""})
          wrapped  (middleware/wrap-i18n handler {:catalogue     cat
                                                  :default-locale :en})
          request  {:session {:user {:language "nl"}}}]
      (wrapped request)
      (is (fn? (:i18n/t @captured))           "should inject :i18n/t function")
      (is (= [:nl :en] (:i18n/locale-chain @captured)) "should inject Dutch locale chain")))

  (testing "wrap-i18n uses default locale when no user language set"
    (let [cat      (catalogue/load-catalogue "boundary/i18n/translations")
          captured (atom nil)
          handler  (fn [req] (reset! captured req) {:status 200 :body ""})
          wrapped  (middleware/wrap-i18n handler {:catalogue      cat
                                                  :default-locale :en})
          request  {:session {}}]
      (wrapped request)
      (is (= [:en] (:i18n/locale-chain @captured)) "should use English as default")))

  (testing "injected t-fn renders Dutch when user language is nl"
    (let [cat     (catalogue/load-catalogue "boundary/i18n/translations")
          result  (atom nil)
          handler (fn [req]
                    (let [t-fn (:i18n/t req)]
                      (reset! result (t-fn :user/badge-active)))
                    {:status 200 :body ""})
          wrapped (middleware/wrap-i18n handler {:catalogue      cat
                                                 :default-locale :en})
          request {:session {:user {:language "nl"}}}]
      (wrapped request)
      (is (= "Actief" @result) "t-fn from middleware should return Dutch translation"))))
