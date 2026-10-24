(ns graph.lain-editor.frontend.development.effects
  "Cancellable interpreter for premise-versioned development declarations."
  (:require [graph.lain-editor.frontend.workspace.effects :as editor-effects]))

(defn interpret! [pane effect dispatch!]
  (let [controller (js/AbortController.)
        succeed (fn [type result]
                  (when-not (.-aborted (.-signal controller))
                    (dispatch! (assoc result :event/type type))))
        fail (fn [type error]
               (when-not (or (.-aborted (.-signal controller)) (editor-effects/cancelled? error))
                 (dispatch! {:event/type type :message (.-message error)})))
        [command success failure]
        (case (:effect/op effect)
          :development/start
          [{:op "development-start" :pane pane :model (:model effect)}
           :development/start-succeeded :development/start-failed]
          :development/commit
          [{:op "development-commit" :pane pane :generation (:generation effect)
            :commitId (:commit-id effect) :clientId (:client-id effect)
            :index (:index effect) :expectedVersion (:expected-version effect) :text (:text effect)}
           :development/commit-succeeded :development/commit-failed]
          :development/close
          [{:op "development-close" :pane pane :generation (:generation effect)}
           :development/close-succeeded :development/close-failed]
          (throw (ex-info "Unknown development effect" {:effect effect})))]
    (-> (editor-effects/api! command (.-signal controller))
        (.then #(succeed success %))
        (.catch #(fail failure %)))
    #(.abort controller)))
