(ns graph.lain-editor.frontend.development.model
  "Pure state transitions for the premise-versioned development feature."
  (:require [graph.lain-editor.development-protocol :as protocol]))

(defn initial [portable-model]
  {:model portable-model
   :view {:status (if portable-model :starting :ordinary) :message nil}
   :remote {:generation nil :checkpoint nil :projection nil}})

(defn expected-version [portable-model index]
  (let [commits (filter #(= index (:index %)) (get-in portable-model [:instance :commits]))]
    (when (seq commits) (dec (count commits)))))

(defn- effect [state declaration]
  {:state state :effects [declaration]})

(defn transition [state {:event/keys [type] :as event}]
  (case type
    :development/bootstrap
    (if-let [model (:model state)]
      (effect (assoc state :view {:status :starting :message "Replaying development history…"})
              {:effect/op :development/start :model model})
      {:state state :effects []})

    :development/enable
    (let [model (:model event)]
      (protocol/validate-portable-model! model)
      (effect (assoc state :model model
                     :view {:status :starting :message "Starting premise-versioned development…"})
              {:effect/op :development/start :model model}))

    :development/start-succeeded
    {:state (-> state
                (assoc :model (assoc (:model state) :instance (:instance event)))
                (assoc :remote {:generation (:generation event)
                                :checkpoint nil
                                :projection (:view event)})
                (assoc :view {:status :active :message "Premise-versioned development is active"}))
     :effects []}

    :development/start-failed
    {:state (assoc state :remote {:generation nil :checkpoint nil :projection nil}
                        :view {:status :failed :message (:message event)})
     :effects []}

    :development/commit
    (if (and (= :active (get-in state [:view :status]))
             (get-in state [:remote :generation]))
      (let [model (:model state) index (:index event)]
        (effect (assoc state :view {:status :committing :message "Committing implementation premise…"})
                {:effect/op :development/commit
                 :generation (get-in state [:remote :generation])
                 :commit-id (:commit-id event)
                 :client-id (:clientId model)
                 :index index
                 :expected-version (expected-version model index)
                 :text (:text event)}))
      {:state state :effects []})

    :development/commit-succeeded
    {:state (-> state
                (assoc :model (assoc (:model state) :instance (:instance event)))
                (assoc-in [:remote :generation] (:generation event))
                (assoc-in [:remote :projection] (:view event))
                (assoc :view {:status :active
                              :message (if (get-in event [:receipt :replayed?])
                                         "Commit already applied"
                                         (str "Committed premise version "
                                              (get-in event [:receipt :version])))}))
     :effects []}

    :development/commit-failed
    {:state (assoc state :view {:status :active :message (:message event)})
     :effects []}

    :development/disable
    (if-let [generation (get-in state [:remote :generation])]
      (effect (assoc state :view {:status :closing :message "Closing development session…"})
              {:effect/op :development/close :generation generation})
      {:state (initial nil) :effects []})

    :development/close-succeeded
    {:state (initial nil) :effects []}

    :development/close-failed
    {:state (assoc state :view {:status :active :message (:message event)})
     :effects []}

    (throw (ex-info "Unknown development event" {:event event}))))

(defn view-model [{:keys [model view remote]}]
  {:enabled? (some? model)
   :status (:status view)
   :message (:message view)
   :blocks (get-in remote [:projection :blocks])
   :generation (:generation remote)
   :commit-count (count (get-in model [:instance :commits]))
   :can-commit? (and (= :active (:status view)) (some? (:generation remote)))
   :busy? (contains? #{:starting :committing :closing} (:status view))})
