(ns boundary.shared.utils.type-conversion)

(defn uuid->string [uuid]
  (when uuid (.toString uuid)))

(defn string->uuid [s]
  (when s (java.util.UUID/fromString s)))

(defn instant->string [instant]
  (when instant (.toString instant)))

(defn string->instant [s]
  (when s (java.time.Instant/parse s)))

(defn keyword->string [k]
  (when k (name k)))

(defn string->keyword [s]
  (when s (keyword s)))