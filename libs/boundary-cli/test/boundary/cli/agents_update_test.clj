(ns boundary.cli.agents-update-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [boundary.cli.agents-update :as agents-update]
            [boundary.cli.templates :as templates]))

(def ^:private template
  (str "# {{project-name}} — Developer Reference\n"
       "intro\n"
       "<!-- gen:fc-is -->\nNEW fc-is rules for {{project-ns}}\n<!-- /gen:fc-is -->\n"
       "middle\n"
       "<!-- gen:naming -->\nNEW naming\n<!-- /gen:naming -->\n"
       "<!-- gen:pitfalls -->\nNEW pitfalls: never run `boundary add payments` twice\n<!-- /gen:pitfalls -->\n"
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
       "<!-- gen:pitfalls -->\nOLD pitfalls\n<!-- /gen:pitfalls -->\n"
       "<!-- boundary:available-modules -->\n"
       "| search     | Full-text       | boundary add search   |\n"
       "<!-- /boundary:available-modules -->\n"
       "<!-- boundary:installed-modules -->\n"
       "- core\n"
       "- payments (`org.boundary-app/boundary-payments`) — [docs](https://x)\n"
       "<!-- /boundary:installed-modules -->\n"))

(def ^:private subs' {:project-name "shop" :project-ns "shop"})

(deftest update-refreshes-stale-blocks-test
  (let [{:keys [content updated missing]} (agents-update/update-agents-content project-agents template subs')]
    (testing "stale blocks are refreshed with rendered template content"
      (is (str/includes? content "NEW fc-is rules for shop"))
      (is (str/includes? content "NEW naming"))
      (is (str/includes? content "boundary add geo")
          "a module added to the framework since generation appears after update")
      (is (= ["gen:fc-is" "gen:naming" "gen:pitfalls" "boundary:available-modules"] updated)))
    (testing "no markers are missing in a generated project"
      (is (empty? missing)))))

(deftest update-preserves-user-content-and-project-state-test
  (let [{:keys [content]} (agents-update/update-agents-content project-agents template subs')]
    (testing "text outside markers is untouched"
      (is (str/includes? content "## My custom team notes\ndo not lose this")))
    (testing "installed-modules block is project state — never synced from template"
      (is (str/includes? content "- payments (`org.boundary-app/boundary-payments`)")))
    (testing "installed modules are re-removed from the refreshed available table"
      (is (not (str/includes? content "| payments")))
      (is (str/includes? content "boundary add search")))
    (testing "prose mentioning `boundary add <installed>` outside the table survives"
      (is (str/includes? content "never run `boundary add payments` twice")
          "row removal must be scoped to the available-modules block"))))

(deftest update-is-idempotent-test
  (let [first-pass  (:content (agents-update/update-agents-content project-agents template subs'))
        second-pass (agents-update/update-agents-content first-pass template subs')]
    (is (= first-pass (:content second-pass)))
    (is (empty? (:updated second-pass)))))

(deftest missing-markers-are-reported-not-fatal-test
  (let [no-markers "# shop — Developer Reference\nhand-rolled file\n"
        {:keys [content missing]} (agents-update/update-agents-content no-markers template subs')]
    (is (= content no-markers))
    (is (= ["gen:fc-is" "gen:naming" "gen:pitfalls" "boundary:available-modules"] missing))))

(deftest duplicated-markers-touch-first-pair-only-test
  (let [doubled (str project-agents
                     "\n## user copy\n"
                     "<!-- gen:naming -->\nUSER COPY of naming\n<!-- /gen:naming -->\n")
        {:keys [content]} (agents-update/update-agents-content doubled template subs')]
    (is (str/includes? content "NEW naming") "first pair refreshed")
    (is (str/includes? content "USER COPY of naming") "user-duplicated pair untouched")))

(deftest project-name-parsing-test
  (is (= "shop" (agents-update/project-name-from-agents project-agents)))
  (is (nil? (agents-update/project-name-from-agents "no title here"))))

(deftest installed-module-names-parsing-test
  (is (= #{"core" "payments"} (agents-update/installed-module-names project-agents)))
  (is (= #{} (agents-update/installed-module-names "no blocks"))))

(deftest module-row-pattern-word-boundary-test
  (let [row          "| search | Full-text | boundary add search |\n"
        advanced-row "| search-advanced | Fancy | boundary add search-advanced |\n"]
    (testing "matches the module's own row"
      (is (= "" (str/replace row (templates/module-row-pattern "search") ""))))
    (testing "does not match a row whose name merely starts with the module name"
      (is (= advanced-row
             (str/replace advanced-row (templates/module-row-pattern "search") ""))))))
