(ns graph.lain-editor.frontend.workspace.source-save
  "Save-dialog controller. The callback stays in an effect registry; UI state is data."
  (:require [graph.lain-editor.frontend.workspace.state :as s]))

(defonce operations (atom {}))
(defonce cancellations (atom {}))

(defn- current? [id] (= id (get-in @s/ui [:save-dialog :id])))

(defn close!
  ([] (close! true))
  ([cancel?]
   (when-let [id (get-in @s/ui [:save-dialog :id])]
     (let [cancel! (get @cancellations id)]
       (swap! operations dissoc id)
       (swap! cancellations dissoc id)
       (swap! s/ui dissoc :save-dialog)
       (when (and cancel? cancel!) (cancel!))))))

(defn open!
  ([path save!] (open! path save! nil {}))
  ([path save! document] (open! path save! document {}))
  ([path save! _document options]
   (close!)
   (let [id (str (random-uuid))]
     (swap! operations assoc id save!)
     (when-let [cancel! (:cancel! options)] (swap! cancellations assoc id cancel!))
     (swap! s/ui assoc :save-dialog
            (merge {:id id :path path :replace? false :saving? false :error nil
                    :aria-label "Save file" :title "Save file"
                    :description "Save inside the server workspace. Saves the main block source; extension files stay separate."}
                   (dissoc options :cancel!)))
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
                (.then #(when (current? id) (close! false)))
                (.catch (fn [error]
                          (when (current? id)
                            (swap! s/ui update :save-dialog assoc :saving? false :error (.-message error)))))))
          (throw (ex-info "Save operation is no longer available" {:id id})))))
    (throw (ex-info "Unknown save dialog event" {:event event}))))
