(ns wagoe.push.shell.module-wiring-test
  "The FCM settings shape must survive the whole chain: what the catalogue
   writes, ig-config passes, and the init-key destructures (BOU-346 — three
   disagreeing shapes meant configured FCM threw at boot, always)."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.push.shell.module-wiring :as wiring]))

(deftest ^:unit fcm-settings-reach-the-init-key-in-its-own-shape
  (testing "the catalogue's map lands as :provider/:project-id/:credentials-path"
    (let [fcm (get-in (wiring/ig-config
                       {:fcm-credentials {:project-id "p" :credentials-path "/c.json"}}
                       {})
                      [:components :wagoe.push/fcm-provider])]
      (is (= :fcm (:provider fcm)))
      (is (= "p" (:project-id fcm)))
      (is (= "/c.json" (:credentials-path fcm)))))

  (testing "a partial or absent map falls back to :mock — unset #env values
            must not select a provider that cannot construct"
    (doseq [settings [{} {:fcm-credentials nil}
                      {:fcm-credentials {:project-id nil :credentials-path nil}}
                      {:fcm-credentials {:project-id "p"}}]]
      (is (= :mock (get-in (wiring/ig-config settings {})
                           [:components :wagoe.push/fcm-provider :provider]))
          (pr-str settings)))))
