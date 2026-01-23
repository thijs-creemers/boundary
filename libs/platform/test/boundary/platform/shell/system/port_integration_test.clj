(ns boundary.platform.shell.system.port-integration-test
  "Integration tests for HTTP server port allocation and system startup"
  (:require [boundary.config :as config]
            [boundary.platform.shell.system.wiring]
            [boundary.user.shell.module-wiring]
            [boundary.platform.shell.utils.port-manager :as port-manager]
            [clojure.test :refer [deftest testing is]]
            [integrant.core :as ig]
            [clojure.tools.logging :as log])
  (:import [java.net ServerSocket BindException]))

;; =============================================================================
;; Test Fixtures and Helpers
;; =============================================================================

(defn- occupy-port
  "Start a server socket on a port to simulate port conflict"
  [port]
  (try
    (ServerSocket. port)
    (catch Exception _
      nil)))

(defn- release-port
  "Close a server socket to free up a port"
  [socket]
  (when socket
    (try
      (.close socket)
      (catch Exception _))))

(defn- create-test-config
  "Create test configuration with specific port settings"
  [& {:keys [port host port-range]}]
  {:active
   {:boundary/settings
    {:name "boundary-test"
     :version "0.1.0-test"}

    :boundary/http
    (merge {:port (or port 59990)
            :host (or host "127.0.0.1")
            :join? false}
           (when port-range {:port-range port-range}))

    :boundary/sqlite
    {:db ":memory:"
     :pool {:minimum-idle 1
            :maximum-pool-size 3}}

    :boundary/logging
    {:provider :no-op}

    :boundary/metrics
    {:provider :no-op}

    :boundary/error-reporting
    {:provider :no-op}}})

(defn- start-system-with-port
  "Start a minimal system with HTTP server on specified port"
  [port & {:keys [port-range]}]
  (let [test-config (create-test-config :port port
                                        :port-range port-range)
        ig-config (config/ig-config test-config)]
    (try
      (ig/init ig-config)
      (catch Exception e
        {:error e}))))

(defn- stop-system
  "Stop a system safely"
  [system]
  (when (and system (not (:error system)))
    (try
      (ig/halt! system)
      (catch Exception e
        (log/warn "Error stopping test system" {:error (str e)})))))

;; =============================================================================
;; System Startup Tests
;; =============================================================================

(deftest test-http-server-startup-with-available-port
  (testing "HTTP server starts successfully when port is available"
    (let [available-port (port-manager/find-available-port 59980)
          system (start-system-with-port available-port)]
      (try
        (is (not (:error system)) "System should start without errors")
        (when (not (:error system))
          (is (contains? system :boundary/http-server))
          (let [server (:boundary/http-server system)]
            (is (some? server))
            ;; Verify server is actually running by checking if it's started
            (is (.isStarted server))))
        (finally
          (stop-system system))))))

(deftest test-http-server-port-conflict-resolution
  (testing "HTTP server resolves port conflicts using port-range fallback"
    ;; Set environment to development to enable port-range fallback
    (with-redefs [port-manager/development-environment? (constantly true)]
      (let [blocked-port 59980
            port-range {:start 59980 :end 59990}
            blocking-socket (occupy-port blocked-port)]
        (try
          (let [system (start-system-with-port blocked-port
                                               :port-range port-range)]
            (try
              (is (not (:error system)))
              (is (contains? system :boundary/http-server))
              (let [server (:boundary/http-server system)]
                (is (some? server))
                (is (.isStarted server))
                ;; Server should be running on a different port than requested
                (let [actual-port (.getPort (first (.getConnectors server)))]
                  (is (not= blocked-port actual-port))
                  (is (>= actual-port (:start port-range)))
                  (is (<= actual-port (:end port-range)))))
              (finally
                (stop-system system))))
          (finally
            (release-port blocking-socket)))))))

(deftest test-http-server-config-integration
  (testing "HTTP server receives complete configuration including port-range"
    (let [test-config (create-test-config :port 59975
                                          :port-range {:start 59970 :end 59979})
          ig-config (config/ig-config test-config)
          http-server-config (:boundary/http-server ig-config)]

      ;; Verify the HTTP server config includes all expected keys
      (is (= 59975 (:port http-server-config)))
      (is (= "127.0.0.1" (:host http-server-config)))
      (is (false? (:join? http-server-config)))
      (is (= {:start 59970 :end 59979} (get-in http-server-config [:config :port-range])))
      (is (contains? http-server-config :handler)))))

;; =============================================================================
;; Error Handling Tests
;; =============================================================================

(deftest test-system-startup-failure-handling
  (testing "System handles startup failures gracefully"
    ;; Create a scenario where all ports in a small range are blocked
    (let [port-range {:start 59960 :end 59962}
          sockets (mapv occupy-port (range 59960 59963))]
      (try
        ;; With all ports blocked and no random fallback in this test scenario,
        ;; the system should either fail gracefully or find an alternative port
        (let [system (start-system-with-port 59960 :port-range port-range)]
          (if (:error system)
            ;; If system failed to start, verify it's due to port unavailability
            (is (or (instance? BindException (:error system))
                    (instance? RuntimeException (:error system))))
            ;; If system started, it should be on a different port (random fallback)
            (do
              (is (some? (:boundary/http-server system)))
              (stop-system system))))
        (finally
          (doseq [socket sockets]
            (release-port socket)))))))

;; =============================================================================
;; Docker Environment Simulation Tests
;; =============================================================================

(deftest test-docker-environment-behavior
  (testing "System behavior in Docker environment"
    (with-redefs [port-manager/docker-environment? (constantly true)]
      (let [system (start-system-with-port 59955)]
        (try
          (is (not (:error system)))
          ;; In Docker mode, should use the requested port directly
          (let [server (:boundary/http-server system)
                actual-port (.getPort (first (.getConnectors server)))]
            (is (= 59955 actual-port)))
          (finally
            (stop-system system)))))))

;; =============================================================================
;; Configuration Validation Tests  
;; =============================================================================

(deftest test-config-loading-and-validation
  (testing "Configuration loading includes port management settings"
    (let [test-config (create-test-config :port 59950
                                          :port-range {:start 59950 :end 59959})
          http-config (config/http-config test-config)]

      (is (= 59950 (:port http-config)))
      (is (= "127.0.0.1" (:host http-config)))
      (is (false? (:join? http-config)))
      (is (= {:start 59950 :end 59959} (:port-range http-config))))))

(deftest test-config-without-port-range
  (testing "Configuration works without port-range specified"
    (let [test-config (create-test-config :port 59945)
          http-config (config/http-config test-config)]

      (is (= 59945 (:port http-config)))
      (is (nil? (:port-range http-config))))))

;; =============================================================================
;; Performance and Resource Tests
;; =============================================================================

(deftest test-multiple-system-startups
  (testing "Multiple system startups with port allocation"
    ;; Set environment to development to enable port-range fallback
    (with-redefs [port-manager/development-environment? (constantly true)]
      ;; Start multiple systems to verify port allocation works correctly
      (let [systems (atom [])]
        (try
          (doseq [i (range 3)]
            (let [base-port (+ 59900 (* i 10))
                  port-range {:start base-port :end (+ base-port 9)}
                  system (start-system-with-port base-port :port-range port-range)]
              (when (not (:error system))
                (swap! systems conj system))))

          ;; Verify at least some systems started successfully
          (is (pos? (count @systems)))

          ;; Verify systems are using different ports
          (let [ports (map #(.getPort (first (.getConnectors (:boundary/http-server %)))) @systems)]
            (is (= (count ports) (count (distinct ports)))))

          (finally
            (doseq [system @systems]
              (stop-system system))))))))