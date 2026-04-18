(ns boundary.devtools.schema
  "Malli validation schemas for the devtools library."
  (:require [malli.core :as m]))

(def GuidanceLevel
  "Valid guidance levels."
  [:enum :full :minimal :off])

(def GuidanceConfig
  "Schema for guidance configuration."
  [:map
   [:guidance-level {:default :full} GuidanceLevel]])

(def ErrorCode
  "Schema for a Boundary error code."
  [:map
   [:code :string]
   [:category :keyword]
   [:title :string]
   [:description :string]
   [:fix [:maybe :string]]])
