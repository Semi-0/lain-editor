(ns graph.lain-editor.frontend.workspace.source-save
  "Save-dialog controller. The callback stays in an effect registry; UI state is data."
  (:require [graph.lain-editor.frontend.workspace.state :as s]))

(defonce operations (atom {}))

(defn- current? [id] (= id (get-in @s/ui [:save-dialog :id])))

(defn close! []
  (when-let [id (get-in @s/ui [:save-dialog :id])]
    (swap! operations dissoc id)
    (swap! s/ui dissoc :save-dialog)))

(defn open!
  ([path save!] (open! path save! nil {}))
  ([path save! document] (open! path save! document {}))
  ([path save! _document options]
   (close!)
   (let [id (str (random-uuid))]
     (swap! operations assoc id save!)
     (swap! s/ui assoc :save-dialog
            (merge {:id id :path path :replace? false :saving? false :error nil
                    :aria-label "Save block to file" :title "Save block to file"
                    :description "Includes current code, edited extensions and the pending extension draft."}
                   options))
     id)))

(defn dispatch! [[action & args :as event]]
  (case action
    :save-dialog/path (swap! s/ui assoc-in [:save-dialog :path] (first args))
    :save-dialog/replace (swap! s/ui assoc-in [:save-dialog :replace?] (first args))
    :save-dialog/cancel (when-not (get-in @s/ui [:save-dialog :saving?]) (close!))
    :save-dialog/submit
    (let [{:keys [id path replace? saving?]} (:save-dialog @s/ui)]
      (when (and id (not saving?))
        (if-let [save! (get @operations id)]
          (do
            (swap! s/ui update :save-dialog assoc :saving? true :error nil)
            (-> (js/Promise.resolve nil)
                (.then #(save! path replace?))
                (.then #(when (current? id) (close!)))
                (.catch (fn [error]
                          (when (current? id)
                            (swap! s/ui update :save-dialog assoc :saving? false :error (.-message error)))))))
          (throw (ex-info "Save operation is no longer available" {:id id})))))
    (throw (ex-info "Unknown save dialog event" {:event event}))))
