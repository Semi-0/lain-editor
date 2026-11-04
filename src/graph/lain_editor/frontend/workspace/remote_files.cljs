(ns graph.lain-editor.frontend.workspace.remote-files
  "Browser-owned application of server file-open requests; loading never runs code."
  (:require [graph.lain-editor.frontend.ui.dom :as dom]
            [graph.lain-editor.frontend.workspace.effects :as effects]
            [graph.lain-editor.frontend.workspace.file-links :as file-links]
            [graph.lain-editor.frontend.workspace.model :as model]
            [graph.lain-editor.frontend.ui.reactive :as rx]
            [graph.lain-editor.frontend.workspace.state :as s]))

(defonce client-id (str (random-uuid)))

(defn snapshot []
  {:desktop (:activeDesktop @s/workspace) :selected @s/selected-id
   :panes (mapv #(assoc (select-keys % [:id :title :filename])
                        :generation (get @s/generations (:id %))
                        :sessionId (get @s/session-ids (:id %))
                        :fileVersion (get-in @s/ui [:file-links (:id %) :version])
                        :fileConflict (boolean (get-in @s/ui [:file-links (:id %) :external]))) (:panes @s/workspace))
   :closedBlocks (mapv (fn [entry]
                         (merge (select-keys entry [:id :order])
                                (select-keys (:pane entry) [:title :filename])))
                       (get @s/workspace :closedBlocks []))})

(defn apply-load [document {:keys [pane desktop filename path source]}]
  (when-not (= desktop (:activeDesktop document))
    (throw (ex-info "Active desktop changed before file load; submit again" {})))
  (model/spawn-pane document (model/loaded-source (model/new-pane pane) (or path filename) source)))

(defn apply-command [document command]
  (case (:kind command)
    "reopen-closed"
    (model/reopen-closed-block document (:archive command) (:desktop command) (:pane command))
    (apply-load document command)))

(defn attach! [scope]
  (let [pending? (atom false) alive? (atom true) ack (atom nil)
        active (atom nil)
        poll! (fn []
                (when (and (effects/connected?) @alive? (not @pending?))
                  (let [controller (js/AbortController.)
                        current? #(and @alive? (identical? controller @active))]
                    (reset! active controller)
                    (reset! pending? true)
                    (-> (effects/api! {:op "workspace-poll" :workspace client-id
                                       :snapshot (snapshot) :ack @ack}
                                      (.-signal controller))
                        (.then (fn [result]
                                 (when (current?)
                                   (reset! ack nil)
                                   (when (and (not @s/busy?) (:command result))
                                     (let [command (:command result)]
                                       (if (= "editor-command" (:kind command))
                                         (-> (effects/extend-external! command)
                                             (.then (fn [result]
                                                      (reset! ack {:request (:request command) :status "applied" :result result})))
                                             (.catch (fn [error]
                                                       (reset! ack {:request (:request command) :status "failed" :error (.-message error)}))))
                                         (try
                                           (s/transact!
                                            #(do
                                               (swap! s/workspace apply-command command)
                                               (s/select-pane! (:pane command))
                                               (reset! s/message
                                                       (if (= "reopen-closed" (:kind command))
                                                         "API reopened closed block · not executed"
                                                         (str "API loaded " (:path command) " · not executed")))))
                                           (when (= "load-file" (:kind command))
                                             (let [epoch (effects/lifecycle-token)]
                                               (-> (effects/api! {:op "files/watch" :path (:path command)})
                                                   (.then (fn [snapshot]
                                                            (when (and @alive? (effects/current-lifecycle? epoch)
                                                                       (= (:source command) (:source snapshot)))
                                                              (file-links/register! (:pane command) snapshot))))
                                                   (.catch (fn [error] (reset! s/message (str "File watcher: " (.-message error))))))))
                                           (dom/focus-pane! (:pane command))
                                           (reset! ack {:request (:request command) :status "applied"})
                                           (catch :default error
                                             (reset! ack {:request (:request command) :status "failed"
                                                          :error (.-message error)})))))))))
                        (.catch (fn [error]
                                  (when (and (current?) (not (effects/cancelled? error)))
                                    (reset! s/message (str "Workspace API: " (.-message error))))))
                        (.finally #(when (current?)
                                     (reset! active nil)
                                     (reset! pending? false)))))))
        timer (js/setInterval poll! 1000)]
    (poll!)
    (rx/own! scope #(do (reset! alive? false)
                        (js/clearInterval timer)
                        (when-let [controller @active] (.abort controller))))))
