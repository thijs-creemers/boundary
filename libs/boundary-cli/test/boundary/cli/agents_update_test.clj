(ns boundary.cli.agents-update-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [boundary.cli.agents-update :as sut]))

(def ^:private template
  (str "# {{project-name}} — Developer Reference\n"
       "intro\n"
       "<!-- gen:fc-is -->\nNEW fc-is rules for {{project-ns}}\n<!-- /gen:fc-is -->\n"
       "middle\n"
       "<!-- gen:naming -->\nNEW naming\n<!-- /gen:naming -->\n"
       "<!-- gen:pitfalls -->\nNEW pitfalls\n<!-- /gen:pitfalls -->\n"
       "<!-- boundary:available-modules -->\n"
       "| payments   | PSP abstraction | boundary add payments |\n"
       "| search     | Full-text       | boundary add search   |\n"
       "| geo        | Geocoding (new) | boundary add geo      |\n"
       "<!-- /boundary:available-modules -->\n"
       "<!-- boundary:installed-modules -->\n- core\n<!-- /boundary:installed-modules -->\n"))

(def ^:private project-agents
  (str "# shop — Developer Reference\n"
       "intro\n"
       "<!-- gen:fc-is -->\nOLD fc-is rules\n<!-- /gen:fc-is -->\n"
       "middle\n"
       "## My custom team notes\ndo not lose this\n"
       "<!-- gen:naming -->\nOLD naming\n<!-- /gen:naming -->\n"
       "<!-- gen:pitfalls -->\nNEW pitfalls\n<!-- /gen:pitfalls -->\n"
       "<!-- boundary:available-modules -->\n"
       "| search     | Full-text       | boundary add search   |\n"
       "<!-- /boundary:available-modules -->\n"
       "<!-- boundary:installed-modules -->\n"
       "- core\n"
       "- payments (`org.boundary-app/boundary-payments`) — [docs](https://x)\n"
       "<!-- /boundary:installed-modules -->\n"))

(def ^:private subs' {:project-name "shop" :project-ns "shop"})

(deftest update-refreshes-stale-blocks-only
  (let [{:keys [content updated missing]} (sut/update-agents-content project-agents template subs')]
    (testing "stale blocks are refreshed with rendered template content"
      (is (str/includes? content "NEW fc-is rules for shop"))
      (is (str/includes? content "NEW naming"))
      (is (str/includes? content "boundary add geo")
          "a module added to the framework since generation appears after update")
      (is (= ["gen:fc-is" "gen:naming" "boundary:available-modules"] updated)))
    (testing "an already-current block is not reported as updated"
      (is (not (some #{"gen:pitfalls"} updated))))
    (testing "no markers are missing in a generated project"
      (is (empty? missing)))))

(deftest update-preserves-user-content-and-project-state
  (let [{:keys [content]} (sut/update-agents-content project-agents template subs')]
    (testing "text outside markers is untouched"
      (is (str/includes? content "## My custom team notes\ndo not lose this")))
    (testing "installed-modules block is project state — never synced from template"
      (is (str/includes? content "- payments (`org.boundary-app/boundary-payments`)")))
    (testing "installed modules are re-removed from the refreshed available table"
      (is (not (str/includes? content "boundary add payments")))
      (is (str/includes? content "boundary add search")))))

(deftest update-is-idempotent
  (let [first-pass  (:content (sut/update-agents-content project-agents template subs'))
        second-pass (sut/update-agents-content first-pass template subs')]
    (is (= first-pass (:content second-pass)))
    (is (empty? (:updated second-pass)))))

(deftest missing-markers-are-reported-not-fatal
  (let [no-markers "# shop — Developer Reference\nhand-rolled file\n"
        {:keys [content missing]} (sut/update-agents-content no-markers template subs')]
    (is (= content no-markers))
    (is (= ["gen:fc-is" "gen:naming" "gen:pitfalls" "boundary:available-modules"] missing))))

(deftest project-name-parsing
  (is (= "shop" (sut/project-name-from-agents project-agents)))
  (is (nil? (sut/project-name-from-agents "no title here"))))

(deftest installed-module-names-parsing
  (is (= #{"core" "payments"} (sut/installed-module-names project-agents)))
  (is (= #{} (sut/installed-module-names "no blocks"))))
