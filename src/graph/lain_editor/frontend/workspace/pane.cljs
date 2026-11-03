(ns graph.lain-editor.frontend.workspace.pane
  "Pure pane components. Events describe controller commands; rendering performs no effects."
  (:require [clojure.string :as str]
            [graph.lain-editor.workspace.layers :as layers]
            [graph.lain-editor.frontend.workspace.model :as model]
            [graph.lain-editor.frontend.workspace.block-display :as block-display]
            [graph.lain-editor.frontend.workspace.composition :as composition]
            [graph.lain-editor.frontend.workspace.extension-editors :as extension-editors]
            [graph.lain-editor.frontend.workspace.layer-panel :as layer-panel]
            [graph.lain-editor.frontend.development.view :as development-view]
            [graph.lain-editor.frontend.view.topology :as topology]))

(defn- extension-view [vm pane pane-ui]
  (let [id (:id pane)
        disabled? (or (:busy? vm) (str/blank? (:extensionDraft pane))
                      (model/extensions-edited? pane) (not (contains? (:generations vm) id)))]
    [:details.extension
     {:replicant/key [id :pending-extension]
      :open (boolean (get-in pane-ui [:panels :extension]))
      :on {:toggle [[:panel/toggle id :extension [:event/target.open]]]}}
     [:summary {:hidden true} "Extend this running block"]
     [:textarea {:replicant/key [id :extension-draft]
                 :aria-label "Extension code" :placeholder "(define next (+ result 1))"
                 :readonly (:busy? vm) :value (:extensionDraft pane)
                 :on {:input [[:workspace/edit-extension-draft id [:event/target.value]]]}}]
     [:button {:type "button" :disabled disabled?
               :on {:click [[:workspace/command {:action :extend :target id :enabled (not disabled?)}]]}}
      "Apply extension"]]))

(defn- source-view [vm pane index]
  (let [id (:id pane) error (get (:errors vm) id)
        status (if (= (:pending-id vm) id) "Preparing…" (model/status-text pane error))]
    [:div.source-area
     [:div.source-meta {:hidden (str/blank? (:filename pane))}
      [:span (:filename pane)]]
     [:textarea.code
      {:replicant/key [id :source] :aria-label (str "Lain code " (inc index))
       :spellcheck false :readonly (:busy? vm) :value (:source pane)
       :rows (extension-editors/source-rows (:source pane))
       :on {:input [[:workspace/edit-source id [:event/target.value]]]}}]
     (extension-editors/view pane (:busy? vm) (get-in vm [:applied-extensions (:id pane)])
                             (get-in vm [:panes-ui (:id pane) :hidden-extension-editors] #{}))
     [:div.statebar {:hidden (not (some? error))}
      [:input {:replicant/key [id :source-file] :type "file" :hidden true
               :accept ".lain,.clj,.txt" :disabled (:busy? vm)
               :on {:change [[:file/load id [:event/target.files]]]}}]
      [:span {:class (cond-> ["state"] error (conj "error"))} status]]]))

(defn- layer-view [vm pane index layer]
  (case (:id layer)
    "code" (source-view vm pane index)
    "result" (topology/result-view vm (:id pane))
    "cell" (topology/cell-view vm (:id pane))
    "topology" [:div.display-body.topology-content (topology/view vm (:id pane))]
    (throw (ex-info "Unknown editor layer" {:layer layer}))))

(defn pane-view [vm pane index]
  (let [id (:id pane) pane-ui (get (:panes-ui vm) id {})
        mode (block-display/effective-mode pane-ui)
        presented (block-display/presented-pane pane mode)
        visible-layers (filter :visible (layers/for-pane presented))
        resizable? (and (= "code" (:id (first visible-layers))) (> (count visible-layers) 1))
        graph-size (get pane-ui :graph-size 54)]
    [:section
     {:replicant/key id :class (cond-> ["pane"] (= id (:selected-id vm)) (conj "selected"))
      :data-pane-id id :data-generation (get (:generations vm) id) :tabindex 0 :aria-label (str "Editor " (inc index))
      :replicant/on-mount [[:dom/pane-mounted id [:event/target]]]
      :replicant/on-render [[:dom/pane-updated id [:event/target]]]
      :replicant/on-unmount [[:dom/pane-unmounted id]]
      :on {:pointerdown [[:workspace/select id]]
           :focusin [[:workspace/select id]]
           :contextmenu [[:event/prevent-default] [:workspace/select id] [:palette/open id]]}}
     [:div {:class (case mode
                     :normal ["block-display"]
                     :hidden ["block-display" "block-display-hidden"]
                     :fill ["block-display" "block-display-fill"])}
      [:div.topology-fullscreen-shell {:replicant/key [id :fullscreen-shell]}
       [:span.fullscreen-status {:role "status" :aria-label "Fullscreen block visualizer status"}
        (:fullscreen-error pane-ui)]
       [:div.topology-content.topology-fullscreen-content
        (cond->
         (into [:div (cond-> (composition/stage-properties presented (:measurements pane-ui))
                       (:graph-size pane-ui) (assoc-in [:style :--code-size] (str (- 100 graph-size) "%")))]
               (map-indexed
               (fn [layer-index layer]
                 [:div (composition/plane-properties presented layer layer-index)
                  (layer-view vm pane index layer)])
                (layers/for-pane presented)))
          resizable?
          (conj [:div.visualizer-divider
                 {:role "separator" :tabindex 0 :aria-label "Resize graphical view"
                  :aria-orientation "horizontal" :aria-valuemin 10 :aria-valuemax 90
                  :aria-valuenow graph-size
                  :on {:pointerdown [[:dom/visualizer-resize id [:event/native]]]
                       :keydown [[:dom/visualizer-resize-key id [:event/native]]]}}]))]]]
     [:div.pane-tools
      (extension-view vm pane pane-ui)
      (development-view/view pane pane-ui)
      (layer-panel/view pane (:busy? vm) pane-ui)]]))
