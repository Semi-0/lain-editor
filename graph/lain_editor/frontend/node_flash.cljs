(ns graph.lain-editor.frontend.node-flash
  "Owned Web Animations; repeated updates restart only the affected node's hint."
  (:require [graph.lain-editor.frontend.reactive :as rx]))

(defn mount! [scope]
  (let [active (atom {})]
    (rx/own! scope #(do (doseq [animations (vals @active) animation animations] (.cancel animation))
                       (reset! active {})))
    (fn [{:keys [node group frame label]}]
      (let [id (:id node) reduced (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)"))
            background (.trim (.getPropertyValue (js/getComputedStyle frame) "--background"))
            options #js {:duration (if reduced 160 900) :easing (if reduced "steps(1, end)" "ease-out")}
            fills (.animate frame #js [#js {:fill "#ddd"} #js {:fill background}] options)
            text (.animate label #js [#js {:fill background} #js {:fill "#eee"}] options)]
        (doseq [animation (get @active id)] (.cancel animation))
        (swap! active assoc id [fills text])
        (.setAttribute group "data-update-count" (str (inc (js/Number (or (.getAttribute group "data-update-count") "0")))))
        (set! (.-onfinish fills)
              (fn [_] (when (identical? fills (first (get @active id))) (swap! active dissoc id))))))))
