(ns wagoe.cloud-blueprint-test
  "The Dockerfile a cloud blueprint names must have the app as its last stage.

   Docker builds the last stage when no `--target` is given, and Render's
   blueprint spec has no field to give one. `resources/conf/dev/Dockerfile` ends
   in a `development` stage whose command is `tail -f /dev/null`, so both
   documented cloud deployments shipped a container that starts and serves
   nothing (BOU-439).

   Read statically: a deploy to Render is not something CI can perform, but the
   stage order and the command are in the file, and that is where the defect
   was."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private blueprints
  "Each cloud config, and how it names the Dockerfile it builds."
  [{:path "resources/deploy/cloud/render.yaml"
    :pattern #"(?m)^\s*dockerfilePath:\s*(\S+)"
    :target-pattern nil}
   {:path "resources/deploy/cloud/fly.toml"
    :pattern #"(?m)^\s*dockerfile\s*=\s*\"([^\"]+)\""
    ;; Fly can select a stage; Render cannot. Honoured when present.
    :target-pattern #"(?m)^\s*build-target\s*=\s*\"([^\"]+)\""}])

(defn- stages
  "[stage-name lines] for each `FROM … AS name`, in file order."
  [dockerfile-text]
  (let [lines (str/split-lines dockerfile-text)
        starts (keep-indexed
                (fn [i line]
                  (when-let [[_ nm] (re-find #"(?i)^FROM\s+\S+\s+AS\s+(\S+)" line)]
                    [i (str/lower-case nm)]))
                lines)]
    (map (fn [[[i nm] [j _]]]
           [nm (subvec (vec lines) i (or j (count lines)))])
         (partition 2 1 (concat starts [[(count lines) nil]])))))

(defn- start-command
  "What a stage actually runs.

   ENTRYPOINT when there is one — CMD is then its arguments, which is why the
   root image's `CMD [\"server\"]` says nothing about the program. CMD only when
   the stage has no ENTRYPOINT."
  [stage-lines]
  (let [pick (fn [kind]
               (last (filter #(re-find (re-pattern (str "(?i)^" kind "\\s")) %)
                             stage-lines)))]
    (or (pick "ENTRYPOINT") (pick "CMD"))))

(deftest ^:unit a-cloud-blueprint-builds-an-image-that-runs-the-app
  (doseq [{:keys [path pattern target-pattern]} blueprints]
    (testing path
      (let [blueprint (io/file path)]
        (is (.exists blueprint) "blueprint is gone — fix this list")
        (when (.exists blueprint)
          (let [text       (slurp blueprint)
                [_ named]  (re-find pattern text)
                dockerfile (io/file (str/replace (or named "") #"^\./" ""))
                target     (second (when target-pattern (re-find target-pattern text)))]

            (testing "it names a Dockerfile that exists"
              (is (some? named) "no Dockerfile named at all")
              (is (.exists dockerfile) (str "names " named ", which is not there")))

            (when (.exists dockerfile)
              (let [all      (stages (slurp dockerfile))
                    [nm ls]  (if target
                               (or (first (filter #(= target (first %)) all))
                                   (last all))
                               (last all))
                    command  (start-command ls)]

                (testing (str "and its build target is the " nm " stage")
                  (is (some? command)
                      (str nm " ends on no CMD or ENTRYPOINT"))

                  (when command
                    (is (str/includes? command "java")
                        (str "the stage this deploys starts `" (str/trim command)
                             "`, which does not run the application"))
                    (is (not (str/includes? command "tail"))
                        "this is the sleep-forever development stage")))))))))))

(deftest ^:unit the-development-stage-is-still-the-thing-being-guarded-against
  (testing "the dev Dockerfile really does end on a command that serves nothing"
    ;; Otherwise the assertions above pass because the hazard disappeared
    ;; quietly, and nobody learns why the blueprints point where they do.
    (let [all (stages (slurp (io/file "resources/conf/dev/Dockerfile")))
          [nm ls] (last all)]
      (is (= "development" nm))
      (is (str/includes? (start-command ls) "tail")))))
