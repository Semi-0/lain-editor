(ns graph.lain-editor.development.protocol
  "Portable contracts for opt-in premise-versioned editor development."
  (:require [clojure.string :as str]))

(def mode "versioned-premise")
(def instance-schema "compiler-2/versioned-instance")
(def instance-schema-version 1)

(def operations
  #{"development-start" "development-commit" "development-checkpoint"
    "development-export" "development-close"})

(declare validate-portable-model!)

(defn- ensure! [condition message data]
  (when-not condition (throw (ex-info message data))))

(defn- nonblank-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn empty-instance [client-id]
  {:schema instance-schema
   :schema-version instance-schema-version
   :clients [{:client-id client-id :mode mode}]
   :commits []})

(defn validate-instance! [instance]
  (ensure! (map? instance) "Development instance must be an object" {:instance instance})
  (ensure! (= instance-schema (:schema instance)) "Unknown development instance schema" {:instance instance})
  (ensure! (= instance-schema-version (:schema-version instance)) "Unknown development instance version" {:instance instance})
  (ensure! (vector? (:clients instance)) "Development clients must be an array" {:instance instance})
  (ensure! (vector? (:commits instance)) "Development commits must be an array" {:instance instance})
  instance)

(defn portable-model [specification-revision client-id instance]
  (let [model {:mode mode
               :specificationRevision specification-revision
               :clientId client-id
               :instance instance}]
    (validate-portable-model! model)))

(defn validate-portable-model! [{:keys [mode specificationRevision clientId instance] :as model}]
  (ensure! (map? model) "Development model must be an object" {:model model})
  (ensure! (= mode graph.lain-editor.development.protocol/mode) "Unknown development mode" {:mode mode})
  (ensure! (nonblank-string? specificationRevision) "Development model needs a specification revision" {:model model})
  (ensure! (nonblank-string? clientId) "Development model needs a client ID" {:model model})
  (validate-instance! instance)
  (ensure! (= #{clientId} (set (map :client-id (:clients instance))))
           "Development instance must contain exactly its logical client"
           {:client-id clientId :clients (:clients instance)})
  model)

(defn validate-pane! [pane]
  (ensure! (nonblank-string? pane) "Development operation needs a pane ID" {:pane pane})
  pane)

(defn validate-generation! [generation]
  (ensure! (nonblank-string? generation) "Development operation needs a generation" {:generation generation})
  generation)

(defn start-request [{:keys [pane model outcomes membershipComplete] :as request}]
  (validate-pane! pane)
  (validate-portable-model! model)
  (ensure! (or (nil? outcomes) (vector? outcomes)) "Development outcomes must be an array" {:outcomes outcomes})
  (ensure! (or (nil? membershipComplete) (boolean? membershipComplete))
           "Membership completeness must be boolean" {:membershipComplete membershipComplete})
  request)
(defn commit-request
  [{:keys [pane generation commitId clientId index expectedVersion text
           outcomeUpdates evidenceUpdates membershipComplete]
    :as request}]
  (validate-pane! pane)
  (validate-generation! generation)
  (doseq [[value label] [[commitId "commit ID"] [clientId "client ID"]]]
    (ensure! (nonblank-string? value) (str "Development commit needs " label) {:request request}))
  (ensure! (and (integer? index) (not (neg? index))) "Development commit index must be non-negative" {:index index})
  (ensure! (or (nil? expectedVersion) (integer? expectedVersion)) "Expected version must be an integer or null" {:expectedVersion expectedVersion})
  (ensure! (string? text) "Development commit source must be text" {:text text})
  (ensure! (or (nil? outcomeUpdates) (vector? outcomeUpdates))
           "Constraint outcome updates must be an array" {:outcomeUpdates outcomeUpdates})
  (ensure! (or (nil? evidenceUpdates) (vector? evidenceUpdates))
           "Evidence updates must be an array" {:evidenceUpdates evidenceUpdates})
  (ensure! (or (nil? membershipComplete) (boolean? membershipComplete))
           "Membership completeness must be boolean" {:membershipComplete membershipComplete})
  request)
