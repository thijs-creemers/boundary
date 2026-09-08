(ns wagoe.storage.shell.module-wiring-test
  "The :wagoe/storage Integrant key must build a working storage service from
   the catalogue-advertised config shape ({:provider :local :root ...})."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.storage.shell.module-wiring]
            [wagoe.storage.shell.http-handlers :as http-handlers]
            [wagoe.storage.shell.service :as service]
            [wagoe.storage.ports :as ports]
            [integrant.core :as ig]
            [clojure.java.io :as io]))

(def ^:private test-root "target/test-wiring-storage")

(defn- cleanup []
  (let [dir (io/file test-root)]
    (when (.exists dir)
      (doseq [f (reverse (file-seq dir))] (io/delete-file f true)))))

(deftest ^:integration storage-init-key-builds-service-from-catalogue-shape
  (cleanup)
  (try
    (let [component (ig/init-key :wagoe/storage {:provider :local :root test-root})]
      (testing "returns the provider + a usable IStorageService"
        (is (= :local (:provider component)))
        (is (satisfies? service/IStorageService (:service component))))
      (testing "the service round-trips a file to :root"
        (let [{:keys [success data]} (service/upload-file
                                      (:service component)
                                      {:bytes (.getBytes "hi") :content-type "text/plain"}
                                      {:filename "a.txt"} {})]
          (is success)
          (is (true? (ports/file-exists? (:storage component) (:key data))))))
      (ig/halt-key! :wagoe/storage component))
    (finally (cleanup))))

(deftest ^:unit storage-init-key-rejects-unknown-provider
  (is (thrown? clojure.lang.ExceptionInfo
               (ig/init-key :wagoe/storage {:provider :dropbox}))))

(deftest ^:unit storage-routes-key-emits-api-routes
  (cleanup)
  (try
    (let [component (ig/init-key :wagoe/storage {:provider :local :root test-root})
          routes    (ig/init-key :wagoe/storage-routes {:storage component})]
      (testing "routes component exposes Reitit :api data"
        ;; [path {method {...}}] — ADR-037.
        (is (vector? (:api routes)))
        (is (every? #(and (vector? %) (string? (first %)) (map? (second %)))
                    (:api routes)))
        (is (every? #(re-find #"^/storage" (first %)) (:api routes))
            "paths carry no /api prefix (versioning adds it)"))
      (ig/halt-key! :wagoe/storage component))
    (finally (cleanup))))

(deftest ^:unit ig-config-assembles-the-service-and-mounts-routes-on-request
  ;; BOU-346: the routes component existed and no application ever mounted it.
  ;; The module owns its graph now — but mounting stays opt-in, because the
  ;; endpoints carry no authorization: anonymous upload and delete by key.
  (let [base {:provider :local :root "/tmp/x"}]
    (testing "assembled by default, mounted by nobody"
      (let [{:keys [components routes]} (wagoe.storage.shell.module-wiring/ig-config base {})]
        (is (contains? components :wagoe/storage))
        (is (contains? components :wagoe/storage-routes))
        (is (empty? routes)
            "publishing anonymous upload/delete must be a decision, not a default")))
    (testing ":expose-http? true mounts them"
      (let [{:keys [routes]} (wagoe.storage.shell.module-wiring/ig-config
                              (assoc base :expose-http? true) {})]
        (is (= 1 (count routes)))))))

(deftest ^:unit routes-address-the-sharded-keys-the-adapter-returns
  ;; The local adapter shards every key it hands back — `2a/photo.jpg` — so a
  ;; single-segment :file-key could never match the key it had just returned.
  (let [paths (map first (http-handlers/storage-routes nil {}))]
    (doseq [op ["download" "delete" "url"]]
      (is (some #(= % (str "/storage/" op "/{*file-key}")) paths)
          (str op " must catch the whole key, slashes included")))))
