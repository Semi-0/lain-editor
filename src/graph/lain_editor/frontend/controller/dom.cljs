(ns graph.lain-editor.frontend.controller.dom
  "DOM interoperability only: measurements, focus, native input and browser APIs."
  (:require [clojure.walk :as walk]
            [graph.lain-editor.frontend.workspace.state :as s]
            [graph.lain-editor.frontend.workspace.model :as model]
            [graph.lain-editor.frontend.workspace.desktops :as desktops]
            [graph.lain-editor.frontend.workspace.effects :as effects]
            [graph.lain-editor.frontend.controller.events :as events]
            [graph.lain-editor.frontend.ui.dom :as dom]
            [graph.lain-editor.frontend.ui.fullscreen :as fullscreen]
            [graph.lain-editor.frontend.ui.reactive :as rx]))

(defonce pane-scopes (atom {}))
(defonce previous-focus (atom nil))

(defn interpolate [context data]
  (let [native (:replicant/dom-event context)
        target (or (when native (.-target native)) (:replicant/node context))
        resolve-value (fn [key]
                        (case key
                          :event/native native
                          :event/key (when native (.-key native))
                          :event/target target
                          :event/target.value (when target (.-value target))
                          :event/target.checked (when target (.-checked target))
                          :event/target.files (when target (.-files target))
                          :event/target.open (when target (.-open target))
                          key))]
    (walk/prewalk
     (fn [value]
       (if (and (vector? value) (= 1 (count value))
                (keyword? (first value)) (= "event" (namespace (first value))))
         (resolve-value (first value))
         (if (keyword? value) (resolve-value value) value)))
     data)))

(defn- set-measurements! [id root]
  (let [code (.querySelector root ".source-area")
        result (.querySelector root ".value-view")
        value {:code-height (if code (.-scrollHeight code) 0)
               :result-height (if result (.-scrollHeight result) 0)}]
    (when (not= value (get-in @s/ui [:panes-ui id :measurements]))
      (swap! s/ui assoc-in [:panes-ui id :measurements] value))))

(defn- pane-mounted! [id root]
  (when-let [old (get @pane-scopes id)] (rx/dispose! old))
  (let [owner (rx/scope)]
    (swap! pane-scopes assoc id owner)
    (when (exists? js/ResizeObserver)
      (let [observer (js/ResizeObserver. (fn [_] (set-measurements! id root)))]
        (doseq [element (array-seq (.querySelectorAll root ".source-area, .value-view"))]
          (.observe observer element))
        (rx/own! owner #(.disconnect observer))))
    (when-let [shell (.querySelector root ".topology-fullscreen-shell")]
      (fullscreen/observe!
       owner js/document shell
       (fn [active?]
         (when (not= active? (get-in @s/ui [:panes-ui id :fullscreen?] false))
           (swap! s/ui assoc-in [:panes-ui id :fullscreen?] active?)))
       #(swap! s/ui assoc-in [:panes-ui id :fullscreen-error] %)))
    (set-measurements! id root)))

(defn- pane-unmounted! [id]
  (when-let [owner (get @pane-scopes id)]
    (swap! pane-scopes dissoc id)
    (rx/dispose! owner)))

(defn- modal! [dialog open?]
  (when dialog
    (if open?
      (when-not (.-open dialog)
        (reset! previous-focus (.-activeElement js/document))
        (.showModal dialog)
        (when-let [input (.querySelector dialog "input")]
          (.focus input)))
      (when (.-open dialog)
        (.close dialog)
        (when-let [element @previous-focus]
          (when (.-isConnected element) (.focus element)))
        (reset! previous-focus nil)))))

(defn- divider-start! [path axis event]
  (.preventDefault event)
  (let [handle (.-currentTarget event)
        container (.-parentElement handle)
        bounds (.getBoundingClientRect container)
        owner (rx/scope)
        pointer (.-pointerId event)
        move! (fn [event]
                (let [ratio (case axis
                              "row" (/ (- (.-clientX event) (.-left bounds)) (.-width bounds))
                              "column" (/ (- (.-clientY event) (.-top bounds)) (.-height bounds))
                              (throw (ex-info "Unknown divider axis" {:axis axis})))]
                  (swap! s/workspace assoc-in (conj path :ratio) (max 0.1 (min 0.9 ratio)))))
        finish! (fn [_] (rx/dispose! owner))]
    (.setPointerCapture handle pointer)
    (dom/listen! owner handle "pointermove" move!)
    (dom/listen! owner handle "pointerup" finish!)
    (dom/listen! owner handle "pointercancel" finish!)
    (rx/own! owner #(when (.hasPointerCapture handle pointer) (.releasePointerCapture handle pointer)))
    (when-let [pane-owner (get @pane-scopes (:id (first (:panes @s/workspace))))]
      (rx/own! pane-owner #(rx/dispose! owner)))))

(defn- fullscreen! [id]
  (when-let [root (dom/pane-root id)]
    (let [target (or (.querySelector root ".topology-fullscreen-shell") root)]
      (swap! s/ui update-in [:panes-ui id] dissoc :fullscreen-error)
      (fullscreen/toggle! js/document target #(swap! s/ui assoc-in [:panes-ui id :fullscreen-error] %)))))

(defn dispatch! [[action & args :as event]]
  (case action
    :dom/pane-mounted (apply pane-mounted! args)
    :dom/pane-updated (apply set-measurements! args)
    :dom/pane-unmounted (pane-unmounted! (first args))
    :dom/palette-updated (modal! (.querySelector js/document "dialog.command-palette") (first args))
    :dom/modal-open (modal! (first args) true)
    :dom/focus-pane (dom/focus-pane! (first args))
    :dom/fullscreen (fullscreen! (first args))
    :workspace/divider-start (apply divider-start! args)
    :file/open-key (let [event (first args)]
                     (when (contains? #{"Enter" " "} (.-key event))
                       (.preventDefault event)
                       (.click (.getElementById js/document "open"))))
    :palette/backdrop (let [event (first args)]
                        (when (= (.-target event) (.-currentTarget event))
                          (swap! s/palette assoc :open? false)))
    (throw (ex-info "Unknown DOM command" {:event event}))))

(defn after-render! []
  (when-let [id (:focus-request @s/ui)]
    (when-let [root (dom/pane-root id)]
      (when-let [code (.querySelector root ".code")] (.focus code)))
    (swap! s/ui dissoc :focus-request)))

(defn attach! [owner]
  (rx/own! owner (dom/set-focus-handler! #(swap! s/ui assoc :focus-request %)))
  (rx/own! owner (events/register-handler! "dom" (fn [event _native] (dispatch! event))))
  (rx/own! owner #(doseq [id (keys @pane-scopes)] (pane-unmounted! id)))
  (dom/listen! owner js/document "keydown"
               (fn [event]
                 (let [modifier? (or (.-metaKey event) (.-ctrlKey event)) key (.-key event)]
                   (cond
                     (and modifier? (= "k" (.toLowerCase key)))
                     (do (.preventDefault event)
                         (events/dispatch! [:palette/open @s/selected-id]))
                     (and modifier? (= "Enter" key) (not (:open? @s/palette)))
                     (do (.preventDefault event) (effects/run! @s/selected-id "run"))
                     (and modifier? (= "s" (.toLowerCase key)) (not (:open? @s/palette)))
                     (do (.preventDefault event) (effects/save-block! @s/selected-id))
                     (= "Escape" key)
                     (doseq [menu (array-seq (.querySelectorAll js/document ".options[open]"))]
                       (set! (.-open menu) false))
                     :else nil))))
  (dom/listen! owner js/document "click"
               (fn [event]
                 (doseq [menu (array-seq (.querySelectorAll js/document ".options[open]"))]
                   (when-not (.contains menu (.-target event)) (set! (.-open menu) false)))))
  (let [position (fn [event]
                   (let [menu (.-target event)]
                     (when (.matches menu ".options[open]")
                       (when-let [popover (.querySelector menu ".popover")]
                         (let [bounds (.getBoundingClientRect (.querySelector menu "summary"))
                               width (.-offsetWidth popover) height (.-offsetHeight popover)]
                           (set! (.. popover -style -left)
                                 (str (max 8 (min (- (.-right bounds) width) (- js/innerWidth width 8))) "px"))
                           (set! (.. popover -style -top)
                                 (str (max 8 (min (+ (.-bottom bounds) 6) (- js/innerHeight height 8))) "px")))))))]
    (.addEventListener js/document "toggle" position true)
    (rx/own! owner #(.removeEventListener js/document "toggle" position true))))
