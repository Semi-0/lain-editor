(ns graph.lain-editor.frontend.development.view
  "Declarative projection and event-only DOM binding for development mode."
  (:require [graph.lain-editor.development-protocol :as protocol]
            [graph.lain-editor.frontend.development.dataflow :as dataflow]
            [graph.lain-editor.frontend.dom :as dom]
            [graph.lain-editor.frontend.reactive :as rx]))

(def specification-revision "stack-spec-2026-10-20-r4")

(defn mount! [scope pane-id descriptor persist-model!]
  (let [root (dom/element "details" "development-panel")
        summary (dom/element "summary" "" "Incremental development")
        status (dom/element "div" "development-status")
        projection (dom/element "pre" "development-projection")
        enable (dom/button "Enable premise-versioned mode" (fn [] nil))
        commit (dom/button "Commit current source" (fn [] nil))
        disable (dom/button "Return to ordinary mode" (fn [] nil))
        feature (dataflow/create! scope {:pane pane-id
                                         :portable-model (:development @descriptor)
                                         :persist-model! persist-model!})
        dispatch! (:dispatch! feature)]
    (set! (.-onclick enable)
          #(let [client-id (str "editor-" pane-id)]
             (dispatch! {:event/type :development/enable
                         :model (protocol/portable-model
                                 specification-revision client-id
                                 (protocol/empty-instance client-id))})))
    (set! (.-onclick commit)
          #(dispatch! {:event/type :development/commit
                       :commit-id (str (random-uuid)) :index 0
                       :text (:source @descriptor)}))
    (dom/listen! scope root "lain-development-commit"
                 (fn [_]
                   (dispatch! {:event/type :development/commit
                               :commit-id (str (random-uuid)) :index 0
                               :text (:source @descriptor)})))
    (set! (.-onclick disable)
          #(dispatch! {:event/type :development/disable}))
    (rx/watch! scope (:view-model feature)
               (fn [{:keys [enabled? message commit-count can-commit? busy? blocks]}]
                 (dom/text! status (or message "Ordinary isolated session"))
                 (dom/text! projection (if (seq blocks) (pr-str blocks) ""))
                 (set! (.-hidden projection) (not (seq blocks)))
                 (set! (.-hidden enable) enabled?)
                 (set! (.-hidden commit) (not enabled?))
                 (set! (.-hidden disable) (not enabled?))
                 (set! (.-disabled enable) busy?)
                 (set! (.-disabled commit) (or busy? (not can-commit?)))
                 (set! (.-disabled disable) busy?)
                 (set! (.-textContent commit) (str "Commit current source · " commit-count " versions"))))
    (dom/append! root summary status projection enable commit disable)
    (when (:development @descriptor)
      (dispatch! {:event/type :development/bootstrap}))
    root))
