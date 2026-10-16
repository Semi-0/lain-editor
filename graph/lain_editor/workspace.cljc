(ns graph.lain-editor.workspace
  "Portable editor documents contain source and layout, never JVM objects."
  (:require [clojure.string :as str]
            [graph.lain-editor.layers :as layers]))

(defn ensure! [condition message]
  (when-not condition (throw (ex-info message {}))))

(defn validate-pane [pane]
  (ensure! (map? pane) "Pane must be an object")
  (ensure! (and (string? (:id pane)) (not (str/blank? (:id pane)))) "Pane needs an id")
  (doseq [field [:title :source :extensionDraft :filename]]
    (ensure! (string? (get pane field)) (str "Pane needs string " (name field))))
  (ensure! (or (nil? (:executedSource pane)) (string? (:executedSource pane)))
           "executedSource must be source text or null")
  (ensure! (and (vector? (:extensions pane)) (every? string? (:extensions pane)))
           "extensions must be an array of source strings")
  (when (contains? pane :extensionEdits)
    (ensure! (and (vector? (:extensionEdits pane))
                  (every? string? (:extensionEdits pane))
                  (= (count (:extensions pane)) (count (:extensionEdits pane))))
             "Extension drafts must match the saved extension count"))
  (ensure! (or (some? (:executedSource pane)) (empty? (:extensions pane)))
           "Extensions require an executed source")
  (let [{:keys [cell mode direction]} (:display pane)]
    (ensure! (string? cell) "Display cell must be a binding name")
    (ensure! (contains? #{"value" "cell" "graph"} mode) "Unknown display mode")
    (ensure! (contains? #{"upstream" "downstream" "both"} direction) "Unknown trace direction"))
  (ensure! (and (number? (:outputRatio pane)) (<= 0.15 (:outputRatio pane) 0.85))
           "Output ratio must be between 0.15 and 0.85")
  (when (contains? pane :layers) (layers/validate! (:layers pane)))
  (when (contains? pane :layerMode)
    (ensure! (contains? #{"overlay" "stack"} (:layerMode pane)) "Unknown layer composition"))
  pane)

(defn layout-ids [layout depth]
  (ensure! (< depth 20) "Split tree is too deep")
  (case (:type layout)
    "pane" (do (ensure! (string? (:id layout)) "Leaf needs a pane id") [(:id layout)])
    "split" (do
              (ensure! (contains? #{"row" "column"} (:axis layout)) "Unknown split axis")
              (ensure! (and (number? (:ratio layout)) (<= 0.1 (:ratio layout) 0.9))
                       "Split ratio must be between 0.1 and 0.9")
              (concat (layout-ids (:first layout) (inc depth))
                      (layout-ids (:second layout) (inc depth))))
    (throw (ex-info "Unknown layout node" {}))))

(defn desktop-layouts [document]
  (case (:version document)
    1 [{:id "desktop-1" :name "1" :layout (:layout document)}]
    2 (:desktops document)
    (throw (ex-info "Unsupported workspace version" {}))))

(defn validate [document]
  (ensure! (= "lain-tiled-editor" (:format document)) "Not a Lain editor workspace")
  (ensure! (contains? #{1 2} (:version document)) "Unsupported workspace version")
  (ensure! (and (vector? (:panes document)) (<= 1 (count (:panes document)) 64))
           "Workspace needs between 1 and 64 panes")
  (doseq [pane (:panes document)] (validate-pane pane))
  (let [desktops (desktop-layouts document)
        desktop-ids (mapv :id desktops)
        ids (mapv :id (:panes document))
        leaves (vec (mapcat #(layout-ids (:layout %) 0) desktops))]
    (ensure! (and (vector? desktops) (<= 1 (count desktops) 64)) "Workspace needs desktops")
    (doseq [desktop desktops]
      (ensure! (and (string? (:id desktop)) (not (str/blank? (:id desktop)))) "Desktop needs an ID")
      (ensure! (and (string? (:name desktop)) (not (str/blank? (:name desktop)))) "Desktop needs a name")
      (when-let [selected (:selectedPane desktop)]
        (ensure! (some #{selected} (layout-ids (:layout desktop) 0)) "Selection belongs to another desktop")))
    (ensure! (= (count desktop-ids) (count (set desktop-ids))) "Duplicate desktop ID")
    (when (= 2 (:version document))
      (ensure! (some #{(:activeDesktop document)} desktop-ids) "Unknown active desktop"))
    (ensure! (= (count ids) (count (set ids))) "Duplicate pane id")
    (ensure! (= (count leaves) (count (set leaves))) "Duplicate layout leaf")
    (ensure! (= (set ids) (set leaves)) "Desktops must contain each pane exactly once"))
  document)

(defn upgrade [document]
  (validate document)
  (let [document (case (:version document)
                   1 (-> document (dissoc :layout) (assoc :version 2 :activeDesktop "desktop-1"
                                                        :desktops (desktop-layouts document)))
                   2 document
                   (throw (ex-info "Unsupported workspace version" {})))]
    (update document :panes #(mapv (fn [pane]
                                    (assoc pane :layers (layers/for-pane pane)
                                           :layerMode (get pane :layerMode "overlay"))) %))))
