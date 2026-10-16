(ns graph.lain-editor.frontend.dom
  "Small native DOM effects; no application state lives in elements."
  (:require [graph.lain-editor.frontend.reactive :as rx]))

(defn element
  ([tag] (element tag "" ""))
  ([tag class] (element tag class ""))
  ([tag class text]
   (doto (.createElement js/document tag)
     (aset "className" class) (aset "textContent" text))))

(defn svg [tag attrs]
  (let [node (.createElementNS js/document "http://www.w3.org/2000/svg" tag)]
    (doseq [[k v] attrs] (.setAttribute node (name k) (str v)))
    node))

(defn append! [parent & children]
  (doseq [child children] (.appendChild parent child)) parent)

(defn replace! [parent & children]
  (.replaceChildren parent)
  (apply append! parent children))

(defn listen! [scope target event f]
  (.addEventListener target event f)
  (rx/own! scope #(.removeEventListener target event f)))

(defn text! [node value] (set! (.-textContent node) value))
(defn value! [node value]
  (when (not= (.-value node) value) (set! (.-value node) value)))

(defn button [label action]
  (let [node (element "button" "" label)]
    (set! (.-onclick node) (fn [_] (action))) node))

(defn pane-root [id]
  (some #(when (= id (.. % -dataset -paneId)) %)
        (array-seq (.querySelectorAll js/document ".pane"))))

(defn focus-pane! [id]
  (when-let [root (pane-root id)]
    (.focus (.querySelector root ".code"))))

(defn draggable! [scope handle container axis update]
  (.setAttribute handle "role" "separator")
  (listen! scope handle "pointerdown"
           (fn [event]
             (.preventDefault event)
             (.setPointerCapture handle (.-pointerId event))
             (let [bounds (.getBoundingClientRect container)]
               (set! (.-onpointermove handle)
                     (fn [event]
                       (let [ratio (case axis
                                     "row" (/ (- (.-clientX event) (.-left bounds)) (.-width bounds))
                                     "column" (/ (- (.-clientY event) (.-top bounds)) (.-height bounds))
                                     (throw (js/Error. "Unknown split axis")))]
                         (update (max 0.1 (min 0.9 ratio)))))))))
  (let [end #(set! (.-onpointermove handle) nil)]
    (listen! scope handle "pointerup" end)
    (listen! scope handle "pointercancel" end))
  (rx/own! scope #(set! (.-onpointermove handle) nil)))
