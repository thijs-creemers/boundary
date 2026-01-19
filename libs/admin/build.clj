(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'io.github.thijs-creemers/boundary-admin)
(def version (format "0.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src"]
                :scm {:url "https://github.com/thijs-creemers/boundary"
                      :connection "scm:git:git://github.com/thijs-creemers/boundary.git"
                      :developerConnection "scm:git:ssh://git@github.com/thijs-creemers/boundary.git"
                      :tag (str "v" version)}
                :pom-data [[:description "Admin interface library for Boundary framework: auto-CRUD, schema introspection"]
                           [:url "https://github.com/thijs-creemers/boundary"]
                           [:licenses
                            [:license
                             [:name "Eclipse Public License 2.0"]
                             [:url "https://www.eclipse.org/legal/epl-2.0/"]]]]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn install [_]
  (jar nil)
  (b/install {:basis basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))

(defn deploy [_]
  (jar nil)
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   {:installer :remote
    :artifact jar-file
    :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
