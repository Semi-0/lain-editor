(ns graph.lain-editor.frontend.desktops
  "Pure desktop ownership and selection. Runtime panes stay global and unique."
  (:require [graph.lain-editor.workspace :as workspace]))

(defn layout-path [document]
  (case (:version document)
    1 [:layout]
    2 (let [index (first (keep-indexed #(when (= (:activeDesktop document) (:id %2)) %1)
                                      (:desktops document)))]
        (workspace/ensure! (some? index) "Unknown active desktop")
        [:desktops index :layout])
    (throw (ex-info "Unsupported workspace version" {}))))

(defn pane-ids [document]
  (vec (workspace/layout-ids (get-in document (layout-path document)) 0)))

(defn selected-id [document]
  (let [path (pop (layout-path document)) ids (pane-ids document)
        selected (get-in document (conj path :selectedPane))]
    (if (some #{selected} ids) selected (first ids))))

(defn select-pane [document id]
  (workspace/ensure! (some #{id} (pane-ids document)) "Pane belongs to another desktop")
  (case (:version document)
    1 document
    2 (assoc-in document (conj (pop (layout-path document)) :selectedPane) id)
    (throw (ex-info "Unsupported workspace version" {}))))

(defn switch [document id]
  (let [document (workspace/upgrade document)]
    (workspace/ensure! (some #(= id (:id %)) (:desktops document)) "Unknown desktop")
    (assoc document :activeDesktop id)))

(defn add-desktop [document id fresh]
  (let [document (workspace/upgrade document)]
    (workspace/ensure! (< (count (:panes document)) 64) "Workspace has 64 panes")
    (workspace/ensure! (not-any? #(= id (:id %)) (:desktops document)) "Duplicate desktop ID")
    (workspace/ensure! (not-any? #(= (:id fresh) (:id %)) (:panes document)) "Duplicate pane ID")
    (-> document
        (update :panes conj fresh)
        (update :desktops conj {:id id :name (str (inc (count (:desktops document))))
                               :selectedPane (:id fresh) :layout {:type "pane" :id (:id fresh)}})
        (assoc :activeDesktop id))))
