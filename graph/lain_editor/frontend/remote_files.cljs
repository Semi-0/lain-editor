(ns graph.lain-editor.frontend.remote-files
  "Browser-owned application of server file-open requests; loading never runs code."
  (:require [graph.lain-editor.frontend.dom :as dom]
            [graph.lain-editor.frontend.effects :as effects]
            [graph.lain-editor.frontend.model :as model]
            [graph.lain-editor.frontend.reactive :as rx]
            [graph.lain-editor.frontend.state :as s]))

(defonce client-id (str (random-uuid)))

(defn snapshot []
  {:desktop (:activeDesktop @s/workspace) :selected @s/selected-id
   :panes (mapv #(select-keys % [:id :title :filename]) (:panes @s/workspace))})

(defn apply-load [document {:keys [pane desktop filename source]}]
  (when-not (= desktop (:activeDesktop document))
    (throw (ex-info "Active desktop changed before file load; submit again" {})))
  (model/spawn-pane document (model/loaded-source (model/new-pane pane) filename source)))

(defn attach! [scope]
  (let [pending? (atom false) alive? (atom true) ack (atom nil)
        poll! (fn []
                (when (and @alive? (not @pending?) (not @s/busy?))
                  (reset! pending? true)
                  (-> (effects/api! {:op "workspace-poll" :workspace client-id
                                     :snapshot (snapshot) :ack @ack})
                      (.then (fn [result]
                               (reset! ack nil)
                               (when (and @alive? (:command result))
                                 (let [command (:command result)]
                                   (try
                                     (swap! s/workspace apply-load command)
                                     (s/select-pane! (:pane command))
                                     (dom/focus-pane! (:pane command))
                                     (reset! s/message (str "API loaded " (:path command) " · not executed"))
                                     (reset! ack {:request (:request command) :status "applied"})
                                     (catch :default error
                                       (reset! ack {:request (:request command) :status "failed"
                                                    :error (.-message error)})))))))
                      (.catch (fn [error]
                                (when @alive? (reset! s/message (str "Workspace API: " (.-message error))))))
                      (.finally #(reset! pending? false)))))
        timer (js/setInterval poll! 1000)]
    (poll!)
    (rx/own! scope #(do (reset! alive? false) (js/clearInterval timer)))))
