(ns graph.lain-editor.frontend.palette
  "Palette visibility, query, commands, and highlighting are reactive cells."
  (:require [graph.lain-editor.frontend.dom :as dom]
            [graph.lain-editor.frontend.effects :as effects]
            [graph.lain-editor.frontend.reactive :as rx]
            [graph.lain-editor.frontend.state :as s]))

(defn open! []
  (reset! s/palette {:open? true :target @s/selected-id :query "" :active 0}))

(defn close! [] (swap! s/palette assoc :open? false))

(defn- execute! [command]
  (when (:enabled command) (close!) (effects/perform! command)))

(defn- search-input! [scope search]
  (dom/listen! scope search "input"
               #(swap! s/palette assoc :query (.. % -target -value) :active 0))
  (dom/listen! scope search "keydown"
               (fn [event]
                 (case (.-key event)
                   ("ArrowDown" "ArrowUp")
                   (let [step (if (= "ArrowDown" (.-key event)) 1 -1)
                         total (max 1 (count @s/visible-commands))]
                     (.preventDefault event)
                     (swap! s/palette update :active #(mod (+ % step total) total)))
                   "Enter" (do (.preventDefault event)
                               (execute! (get @s/visible-commands (:active @s/palette))))
                   nil))))

(defn- dialog-input! [scope dialog]
  (dom/listen! scope dialog "cancel" (fn [event] (.preventDefault event) (close!)))
  (dom/listen! scope dialog "click"
               (fn [event]
                 (.stopPropagation event)
                 (when (= dialog (.-target event))
                   (let [bounds (.getBoundingClientRect dialog) x (.-clientX event) y (.-clientY event)]
                     (when (or (< x (.-left bounds)) (> x (.-right bounds))
                               (< y (.-top bounds)) (> y (.-bottom bounds))) (close!)))))))

(defn mount! [scope]
  (let [dialog (dom/element "dialog" "command-palette") search (dom/element "input")
        heading (dom/element "div" "palette-heading") list (dom/element "div" "command-list")
        hint (dom/element "div" "palette-hint" "↑ ↓ select · Enter execute · Esc dismiss")
        previous (atom nil)
        heading-cell (rx/derive! scope
                                 (fn [document target]
                                   (let [index (first (keep-indexed #(when (= target (:id %2)) (inc %1)) (:panes document)))]
                                     (str "Block " (.padStart (str index) 2 "0") " / commands")))
                                 s/workspace s/command-target)]
    (.setAttribute dialog "aria-label" "Block commands")
    (set! (.-type search) "search") (set! (.-placeholder search) "Type a command…")
    (.setAttribute search "aria-label" "Search commands")
    (dom/append! dialog heading search list hint) (dom/append! (.-body js/document) dialog)
    (rx/watch! scope heading-cell #(dom/text! heading %))
    (rx/watch! scope s/visible-commands
               (fn [commands]
                 (dom/replace! list)
                 (if (empty? commands)
                   (dom/text! list "No matching commands")
                   (doseq [command commands]
                     (let [button (dom/button (:label command) #(execute! command))]
                       (set! (.-disabled button) (not (:enabled command))) (dom/append! list button))))))
    (rx/watch! scope (rx/derive! scope vector s/palette s/visible-commands)
               (fn [[state _]]
                 (dom/value! search (:query state))
                 (doseq [[index button] (map-indexed vector (array-seq (.querySelectorAll list "button")))]
                   (.toggle (.-classList button) "command-active" (= index (:active state))))
                 (when-let [active (.querySelector list ".command-active")]
                   (.scrollIntoView active #js {:block "nearest"}))
                 (cond
                   (and (:open? state) (not (.-open dialog)))
                   (do (reset! previous (.-activeElement js/document)) (.showModal dialog) (.focus search))
                   (and (not (:open? state)) (.-open dialog))
                   (do (.close dialog) (when (and @previous (.-isConnected @previous)) (.focus @previous)))
                   :else nil)))
    (search-input! scope search) (dialog-input! scope dialog)
    (rx/own! scope #(.remove dialog))))
