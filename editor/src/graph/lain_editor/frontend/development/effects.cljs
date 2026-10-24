(ns graph.lain-editor.frontend.development.effects
  "Interpreter for premise-versioned development effect declarations."
  (:require [graph.lain-editor.frontend.workspace.effects :as editor-effects]))

(defn interpret! [pane effect dispatch!]
  (let [succeed (fn [type result]
                  (dispatch! (assoc result :event/type type)))
        fail (fn [type error]
               (dispatch! {:event/type type :message (.-message error)}))]
    (case (:effect/op effect)
      :development/start
      (-> (editor-effects/api! {:op "development-start" :pane pane
                                :model (:model effect)})
          (.then #(succeed :development/start-succeeded %))
          (.catch #(fail :development/start-failed %)))

      :development/commit
      (-> (editor-effects/api! {:op "development-commit" :pane pane
                                :generation (:generation effect)
                                :commitId (:commit-id effect)
                                :clientId (:client-id effect)
                                :index (:index effect)
                                :expectedVersion (:expected-version effect)
                                :text (:text effect)})
          (.then #(succeed :development/commit-succeeded %))
          (.catch #(fail :development/commit-failed %)))

      :development/close
      (-> (editor-effects/api! {:op "development-close" :pane pane
                                :generation (:generation effect)})
          (.then #(succeed :development/close-succeeded %))
          (.catch #(fail :development/close-failed %)))

      (throw (js/Error. (str "Unknown development effect " (:effect/op effect)))))))
