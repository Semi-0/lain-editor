(ns graph.lain-editor.frontend.main
  "Browser composition root. Reactive declarations are separate from effects."
  (:require [graph.lain-editor.frontend.dom :as dom]
            [graph.lain-editor.frontend.desktop-bar :as desktop-bar]
            [graph.lain-editor.frontend.effects :as effects]
            [graph.lain-editor.frontend.layout :as layout]
            [graph.lain-editor.frontend.palette :as palette]
            [graph.lain-editor.frontend.reactive :as rx]
            [graph.lain-editor.frontend.remote-files :as remote-files]
            [graph.lain-editor.frontend.state :as s]))

(defonce application (rx/scope))

(defn- menus! [scope]
  (let [position (fn [event]
                   (let [menu (.-target event)]
                     (when (.matches menu ".options[open]")
                       (let [anchor (.getBoundingClientRect (.querySelector menu "summary"))
                             popover (.querySelector menu ".popover") w (.-offsetWidth popover) h (.-offsetHeight popover)]
                         (set! (.. popover -style -left) (str (max 8 (min (- (.-right anchor) w) (- js/innerWidth w 8))) "px"))
                         (set! (.. popover -style -top) (str (max 8 (min (+ (.-bottom anchor) 6) (- js/innerHeight h 8))) "px"))))))]
    (.addEventListener js/document "toggle" position true)
    (rx/own! scope #(.removeEventListener js/document "toggle" position true)))
  (dom/listen! scope js/document "click"
               (fn [event]
                 (doseq [menu (array-seq (.querySelectorAll js/document ".options[open]"))]
                   (when-not (.contains menu (.-target event)) (set! (.-open menu) false)))))
  (dom/listen! scope js/document "keydown"
               (fn [event]
                 (when (= "Escape" (.-key event))
                   (doseq [menu (array-seq (.querySelectorAll js/document ".options[open]"))]
                     (set! (.-open menu) false) (.focus (.querySelector menu "summary")))))))

(defn- keyboard! [scope]
  (dom/listen! scope js/document "keydown"
               (fn [event]
                 (let [modifier? (or (.-metaKey event) (.-ctrlKey event)) key (.-key event)]
                   (cond
                     (and modifier? (= "k" (.toLowerCase key)))
                     (do (.preventDefault event) (palette/open!))
                     (and modifier? (= "Enter" key) (not (:open? @s/palette)))
                     (do (.preventDefault event) (effects/run! @s/selected-id "run"))
                     (and modifier? (= "s" (.toLowerCase key)) (not (:open? @s/palette)))
                     (do (.preventDefault event)
                         (when-not (.querySelector js/document "dialog.source-save[open]")
                           (effects/save-block! @s/selected-id)))
                     :else nil)))))

(defn- workspace-controls! [scope]
  (let [file (.getElementById js/document "open") menu (.querySelector js/document ".workspace-options")]
    (dom/listen! scope (.getElementById js/document "commands") "click" (fn [_] (palette/open!)))
    (dom/listen! scope (.getElementById js/document "save") "click"
                 (fn [_] (effects/save-workspace!) (set! (.-open menu) false)))
    (dom/listen! scope (.getElementById js/document "save-as") "click"
                 (fn [_] (effects/save-workspace-as!) (set! (.-open menu) false)))
    (dom/listen! scope file "change"
                 (fn [_]
                   (set! (.-open menu) false)
                   (when-let [input (aget (.-files file) 0)]
                     (-> (.text input)
                         (.then #(effects/restore! (js->clj (js/JSON.parse %) :keywordize-keys true)
                                                   {:workspace-path nil}))
                         (.catch #(reset! s/message (.-message %)))
                         (.finally #(set! (.-value file) ""))))))
    (dom/listen! scope (.getElementById js/document "open-label") "keydown"
                 (fn [event]
                   (when (contains? #{"Enter" " "} (.-key event)) (.preventDefault event) (.click file))))
    (rx/watch! scope s/busy?
               (fn [busy?]
                 (doseq [control (array-seq (.querySelectorAll js/document ".workspace-actions button, .workspace-actions input"))]
                   (set! (.-disabled control) busy?))))))

(defn- storage! [scope]
  ;; Read the saved document before subscribing. A failed import cannot overwrite it.
  (let [saved (.getItem js/localStorage effects/storage-key)
        saved-path (.getItem js/localStorage effects/workspace-path-storage-key)
        workspace-key (random-uuid) path-key (random-uuid)]
    (add-watch s/workspace workspace-key (fn [_ _ _ document] (effects/persist! document)))
    (add-watch s/workspace-path path-key (fn [_ _ _ path] (effects/persist-workspace-path! path)))
    (rx/own! scope #(do (remove-watch s/workspace workspace-key)
                        (remove-watch s/workspace-path path-key)))
    (if saved
      (try (effects/restore! (js->clj (js/JSON.parse saved) :keywordize-keys true)
                             {:workspace-path saved-path})
           (catch :default error (reset! s/message (.-message error))))
      (effects/persist! @s/workspace))))

(defn init! []
  (rx/dispose! application)
  (.setAttribute (.-body js/document) "data-frontend" "clojurescript-javelin")
  (layout/mount! application (.getElementById js/document "workspace"))
  (palette/mount! application)
  (desktop-bar/mount! application)
  (rx/watch! application s/message #(dom/text! (.getElementById js/document "message") %))
  (rx/watch! application (rx/derive! application #(count (:panes %)) s/workspace)
             #(dom/text! (.getElementById js/document "count") (str (.padStart (str %) 2 "0") " blocks")))
  (menus! application) (keyboard! application) (workspace-controls! application)
  (let [timer (js/setInterval #(when (and (not @s/busy?) (not (.-hidden js/document))) (swap! s/tick inc)) 2000)]
    (rx/own! application #(js/clearInterval timer)))
  (dom/listen! application js/window "beforeunload" (fn [_] (rx/dispose! application)))
  (storage! application)
  (remote-files/attach! application))

(init!)
