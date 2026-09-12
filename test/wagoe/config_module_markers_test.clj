(ns wagoe.config-module-markers-test
  "`:enabled?` on a `:wagoe/*` key means \"I am a module\".

   `wagoe.platform.shell.modules/scaffolded-module-keys` treats any such key as
   a scaffolded module and goes looking for its `shell.module-wiring`. A
   settings key that borrows the marker therefore aborts the boot naming a
   namespace nobody wrote — which is what a `:wagoe/session-pruner` carrying
   `:enabled? true` did to every shipped dev config (BOU-429 review).

   The rule was written in a docstring and enforced by nothing. Settings keys
   spell a toggle the way `:wagoe/pagination` does, with `:enable-<thing>`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.config :as config]
            [wagoe.platform.shell.modules :as modules]))

(def ^:private framework-owned-configs
  "Configs whose every module is a framework module.

   `examples/shop` is deliberately absent: it has a scaffolded `:wagoe/product`
   with a real `shop.product.shell.module-wiring`, which is the marker used
   correctly. `example-smoke.sh` boots it, which is the stronger check."
  ["resources/conf/dev/config.edn"
   "resources/conf/test/config.edn"
   "libs/wagoe-cli/resources/wagoe/cli/templates/dev-config.edn.tmpl"
   "libs/wagoe-cli/resources/wagoe/cli/templates/test-config.edn.tmpl"])

(def ^:private core-keys
  "Mirrors `wagoe.platform.shell.system.config/core-keys`, which is private."
  #{:wagoe/settings :wagoe/http :wagoe/router :wagoe/logging :wagoe/metrics
    :wagoe/tracing :wagoe/error-reporting
    :wagoe/sqlite :wagoe/h2 :wagoe/postgresql :wagoe/mysql})

(defn- keys-claiming-to-be-modules
  "The `:active` `:wagoe/*` keys whose map carries `:enabled?`.

   Read textually rather than through Aero: two of these are templates with
   `{{placeholders}}` no reader resolves, and the marker is visible either way."
  [text]
  (let [active (let [start (str/index-of text ":active")
                     end   (str/index-of text ":inactive")]
                 (if (and start end (< start end)) (subs text start end) text))]
    (->> (str/split active #"(?=\n\s*:wagoe/)")
         (keep (fn [chunk]
                 (when-let [[_ k] (re-find #"^\s*(:wagoe/[a-z0-9-]+)" chunk)]
                   (when (re-find #":enabled\?\s" chunk)
                     (keyword (subs k 1))))))
         set)))

(deftest ^:unit only-a-module-carries-the-module-marker
  (let [known (into core-keys (keys modules/framework-modules))
        seen  (atom #{})]

    (testing "the module table was read"
      (is (< 15 (count modules/framework-modules))))

    (doseq [path framework-owned-configs
            :let [file (io/file path)]]
      (testing path
        (is (.exists file) "shipped config is gone — fix this list")
        (when (.exists file)
          (let [marked (keys-claiming-to-be-modules (slurp file))]
            (swap! seen into marked)
            (is (empty? (remove known marked))
                (str "these settings keys look like scaffolded modules, so the boot "
                     "hunts for a shell.module-wiring that does not exist: "
                     (pr-str (sort (remove known marked)))))))))

    (testing "and the reader found markers somewhere, so this cannot pass empty"
      (is (seq @seen)))))

(deftest ^:integration the-dev-config-this-repo-ships-still-assembles
  (testing "the textual check above stands in for this: discovery itself"
    (let [active (:active (config/load-config {:profile :dev}))
          claimed (modules/scaffolded-module-keys active #{})]
      (is (empty? (remove (into core-keys (keys modules/framework-modules)) claimed))
          (str "dev config declares a module the framework cannot wire: "
               (pr-str (sort claimed)))))))
