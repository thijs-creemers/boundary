(ns boundary.shared.utils.case-conversion
  (:require [clojure.string]))

(defn camel-case->kebab-case-string [s]
  (when s
    (-> s
        (clojure.string/replace #"([A-Z]+)([A-Z][a-z])" "$1-$2")
        (clojure.string/replace #"([a-z\\d])([A-Z])" "$1-$2")
        (clojure.string/lower-case))))

(defn kebab-case->camel-case-string [s]
  (when s
    (let [[first & rest] (clojure.string/split s #"-")]
      (apply str first (map clojure.string/capitalize rest)))))

(defn camel-case->kebab-case-map [m]
  (when m
    (reduce-kv (fn [acc k v]
                 (let [new-key (keyword (camel-case->kebab-case-string (name k)))]
                   (assoc acc new-key v)))
               {}
               m)))

(defn kebab-case->camel-case-map [m]
  (when m
    (reduce-kv (fn [acc k v]
                 (let [new-key (keyword (kebab-case->camel-case-string (name k)))]
                   (assoc acc new-key v)))
               {}
               m)))

(defn deep-transform-keys [transform-fn m]
  (cond
    (map? m) (reduce-kv (fn [acc k v]
                         (let [new-key (if (keyword? k)
                                        (keyword (transform-fn (name k)))
                                        k)]
                           (assoc acc new-key (deep-transform-keys transform-fn v))))
                       {}
                       m)
    (vector? m) (mapv #(deep-transform-keys transform-fn %) m)
    (seq? m) (map #(deep-transform-keys transform-fn %) m)
    :else m))
