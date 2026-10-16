(ns graph.lain-editor.frontend.state
  "Javelin input cells and pure derived frontend dataflow."
  (:require [javelin.core :as j :refer-macros [dosync]]
            [graph.lain-editor.frontend.model :as model]
            [graph.lain-editor.layers :as layers]
            [graph.lain-editor.frontend.desktops :as desktops]
            [graph.lain-editor.frontend.reactive :as rx]))

(defonce workspace (j/cell (model/initial-workspace)))
(defonce selected-id (j/cell (:id (first (:panes @workspace)))))
(defonce busy? (j/cell false))
(defonce pending-id (j/cell nil))
(defonce generations (j/cell {}))
(defonce errors (j/cell {}))
(defonce results (j/cell {}))
(defonce expansions (j/cell {}))
(defonce refreshes (j/cell {}))
(defonce tick (j/cell 0))
(defonce message (j/cell "Local runtime · independent blocks"))
(defonce palette (j/cell {:open? false :target nil :query "" :active 0}))

(defonce selected-pane ((j/formula model/pane) workspace selected-id))
(defonce shape ((j/formula #(hash-map :desktop (:activeDesktop %)
                                            :tree (model/layout-shape (get-in % (desktops/layout-path %))))) workspace))
(defonce command-target ((j/formula :target) palette))
(defonce commands ((j/formula (fn [document id generations busy?]
                               (into (model/commands document id generations busy?)
                                     (model/desktop-commands document busy?))))
                   workspace command-target generations busy?))
(defonce query ((j/formula :query) palette))
(defonce visible-commands ((j/formula model/matching-commands) commands query))

(defn change-pane! [id f & args]
  (apply swap! workspace model/update-pane id f args))

(defn pane-cell [scope id]
  (rx/derive! scope #(model/pane % id) workspace))

(defn request-cell [scope id descriptor]
  (rx/derive!
   scope
   (fn [p generations expansions refreshes]
     (let [generation (get generations id) mode (layers/inspection-mode p)]
       (when (and generation mode)
         {:pane id :generation generation :display (assoc (:display p) :mode mode)
          :parent (when (= "graph" mode) (get expansions id)) :refresh (get refreshes id 0)})))
   descriptor generations expansions refreshes))

(defn refresh! [id]
  (swap! refreshes update id (fnil inc 0)))

(defn set-display! [id field value]
  (dosync
   (if (= :mode field)
     (change-pane! id layers/show-output value)
     (change-pane! id assoc-in [:display field] value))
   (swap! expansions dissoc id)))

(defn select-pane! [id]
  (dosync (swap! workspace desktops/select-pane id) (reset! selected-id id)))

(defn switch-desktop! [id]
  (dosync (swap! workspace desktops/switch id)
          (reset! selected-id (desktops/selected-id @workspace))))
