(ns graph.lain-editor.frontend.main
  "One-way composition root: source -> Missionary projection -> pure views -> Replicant."
  (:require [replicant.dom :as r]
            [graph.lain-editor.frontend.controller.dom :as browser]
            [graph.lain-editor.frontend.controller.events :as events]
            [graph.lain-editor.frontend.controller.topology :as topology]
            [graph.lain-editor.frontend.ui.dom :as dom]
            [graph.lain-editor.frontend.ui.reactive :as rx]
            [graph.lain-editor.frontend.view.workspace :as workspace-view]
            [graph.lain-editor.frontend.view.save-dialog :as save-view]
            [graph.lain-editor.frontend.view.file-browser :as file-browser]
            [graph.lain-editor.frontend.workspace.source-save :as source-save]
            [graph.lain-editor.frontend.workspace.desktops :as desktops]
            [graph.lain-editor.frontend.workspace.state :as s]
            [graph.lain-editor.frontend.workspace.effects :as effects]
            [graph.lain-editor.frontend.view.connection :as connection-view]))

(defonce application (atom nil))

(defn- interpret! [context actions]
  (let [native (:replicant/dom-event context)
        actions (if (keyword? (first actions)) [actions] actions)]
    (try
      (s/transact!
       (fn []
         (doseq [[action & args :as event] (browser/interpolate context actions)]
           (case action
             :connection/resume (effects/resume!)
             :connection/export (effects/export-workspace!)
             :event/prevent-default (when native (.preventDefault native))
             :event/stop-propagation (when native (.stopPropagation native))
             :workspace/divider-start (browser/dispatch! event)
             :palette/backdrop (browser/dispatch! event)
             :file/open-key (when (contains? #{"Enter" " "} (first args))
                              (when native (.preventDefault native))
                              (.click (.getElementById js/document "open")))
             :file/restore (events/dispatch! event native)
             :file/load (events/dispatch! event native)
             (cond
               (= "dom" (namespace action)) (browser/dispatch! event)
               (= "server-files" (namespace action)) (effects/server-files-event! event)
               (= "save-dialog" (namespace action))
               (do (when (and native (= action :save-dialog/submit)) (.preventDefault native))
                   (source-save/dispatch! event))
               :else
               (do
                 (when (and native
                            (or (= action :palette/close)
                                (and (= action :palette/key)
                                     (contains? #{"ArrowUp" "ArrowDown" "Enter" "Escape"} (first args)))
                                (= action :workspace/context-menu)
                                (and (= action :layer/binding-key) (= "Enter" (first args)))))
                   (.preventDefault native))
                 (events/dispatch! event native)))))))
      (catch :default error
        (reset! s/message (.-message error))
        (js/console.error "Editor event failed" (clj->js actions) error)))))

(defn- render! [root view-state]
  (let [active (.-activeElement js/document)
        editable? (and active (contains? #{"TEXTAREA" "INPUT"} (.-tagName active)))
        selection (when (and editable? (number? (.-selectionStart active)))
                    [(.-selectionStart active) (.-selectionEnd active) (.-selectionDirection active)])
        scroll (when editable? [(.-scrollTop active) (.-scrollLeft active)])]
    (r/render root
              (conj (workspace-view/view view-state)
                    (save-view/view (get-in view-state [:ui :save-dialog]))
                    (file-browser/view (get-in view-state [:ui :file-browser]))
                    (connection-view/view (:connection view-state))))
    (when (and selection (identical? active (.-activeElement js/document))
               (.-isConnected active))
      (.setSelectionRange active (first selection) (second selection) (nth selection 2))
      (set! (.-scrollTop active) (first scroll))
      (set! (.-scrollLeft active) (second scroll))))
  (browser/after-render!))

(defn- renderer! [owner root]
  (let [pending (atom nil) scheduled (atom nil) alive? (atom true)
        draw! (fn [_]
                (reset! scheduled nil)
                (when @alive?
                  (try
                    (render! root @pending)
                    (catch :default error
                      (let [message (str "View error: " (.-message error))]
                        (when (not= @s/message message) (reset! s/message message))
                        (js/console.error "Editor render failed" error))))))
        view-state (rx/derive! owner s/snapshot s/source)]
    (rx/watch! owner view-state
               (fn [value]
                 (reset! pending value)
                 (when-not @scheduled
                   (reset! scheduled (js/requestAnimationFrame draw!)))))
    (rx/own! owner #(do (reset! alive? false)
                       (when @scheduled (js/cancelAnimationFrame @scheduled))
                       (r/unmount root)))))

(defn- pane-processes! [owner]
  (let [panes (atom {})
        ids (rx/derive! owner desktops/pane-ids s/workspace)]
    (rx/watch! owner ids
               (fn [visible]
                 (let [wanted (set visible)]
                   (doseq [[id process] @panes]
                     (when-not (contains? wanted id)
                       (swap! panes dissoc id)
                       (rx/dispose! process)))
                   (doseq [id visible]
                     (when-not (contains? @panes id)
                       (let [process (rx/scope)]
                         (swap! panes assoc id process)
                         (topology/attach! process id)))))))
    (rx/own! owner #(doseq [[_ process] @panes] (rx/dispose! process)))))

(defn dispose! []
  (when-let [owner @application]
    (reset! application nil)
    (rx/dispose! owner)))

(defn init! []
  (dispose!)
  (let [owner (rx/scope #(reset! s/message (str "Reactive view: " (.-message %)))) root (.getElementById js/document "app")]
    (when-not root (throw (ex-info "Editor root is missing" {})))
    (reset! application owner)
    (rx/own! owner source-save/close!)
    (.setAttribute (.-body js/document) "data-frontend" "replicant-missionary")
    (r/set-dispatch! interpret!)
    (browser/attach! owner)
    (rx/own! owner (topology/register!))
    (events/attach! owner)
    (pane-processes! owner)
    (renderer! owner root)
    (let [timer (js/setInterval #(when (and (effects/connected?) (not @s/busy?) (not (.-hidden js/document)))
                                  (swap! s/tick inc)) 2000)]
      (rx/own! owner #(js/clearInterval timer)))
    (dom/listen! owner js/window "beforeunload" dispose!)))

(when (exists? js/document) (init!))
