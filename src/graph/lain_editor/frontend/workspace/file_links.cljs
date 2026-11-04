(ns graph.lain-editor.frontend.workspace.file-links
  "File-backed editor views and conflict preservation; no runtime execution."
  (:require [clojure.string :as str]
            [graph.lain-editor.frontend.workspace.state :as s]
            [graph.lain-editor.frontend.workspace.model :as model]
            [graph.lain-editor.frontend.ui.reactive :as rx]))

(def storage-key "lain-editor-file-links-v1")

(defn persist! []
  (try (.setItem js/localStorage storage-key (js/JSON.stringify (clj->js (:file-links @s/ui))))
       (catch :default error (reset! s/message (str "File link recovery: " (.-message error))))))

(defn clear! []
  (swap! s/ui dissoc :file-links)
  (persist!))

(defn register! [id snapshot]
  (when (model/pane @s/workspace id)
    (swap! s/ui assoc-in [:file-links id] (select-keys snapshot [:path :source :version :exists]))
    (persist!)))

(defn reconcile! [id captured snapshot]
  (when-let [pane (model/pane @s/workspace id)]
    (let [link (get-in @s/ui [:file-links id])]
      (when (= (select-keys link [:path :version]) (select-keys captured [:path :version]))
        (cond
          (:error snapshot)
          (do (swap! s/ui assoc-in [:file-links id :error] (:error snapshot)) (persist!))

          (= (:version link) (:version snapshot))
          (when (or (:external link) (:error link))
            (register! id snapshot))

          (and (:exists snapshot) (= (:source pane) (:source link)))
          (s/transact! #(do (s/change-pane! id assoc :source (:source snapshot))
                            (register! id snapshot)
                            (reset! s/message "File updated externally · editor refreshed; Run to execute")))

          :else
          (do
            (swap! s/ui assoc-in [:file-links id :external] snapshot)
            (persist!)
            (reset! s/message "File changed externally · both versions preserved; resolve before Run")))))))

(defn- preserve-copy! [source title]
  (when (>= (count (:panes @s/workspace)) 64)
    (throw (js/Error. "Close a block to make room for the preserved version")))
  (let [copy (assoc (model/new-pane) :source source :title title :filename "")]
    (swap! s/workspace model/spawn-pane copy)))

(defn resolve! [id choice]
  (when-let [pane (model/pane @s/workspace id)]
    (let [{:keys [external path]} (get-in @s/ui [:file-links id])]
      (when external
        (s/transact!
         (fn []
           (case choice
             :editor (preserve-copy! (or (:source external) "") (str "File snapshot: " path))
             :file (do
                     (preserve-copy! (:source pane) (str "Preserved editor draft: " path))
                     (s/change-pane! id assoc :source (or (:source external) ""))))
           (register! id external)
           (s/select-pane! id)
           (reset! s/message "Both versions preserved in separate blocks · runtime unchanged")))))))

(defn- watched-views []
  (into {}
        (keep (fn [pane]
                (let [id (:id pane) link (get-in @s/ui [:file-links id]) path (:filename pane)]
                  (cond
                    link [id link]
                    (and (string? path) (not (str/blank? path))
                         (not (str/starts-with? path "/"))
                         (not (str/includes? path "\\"))
                         (not (str/includes? path ":"))
                         (not (some #{".."} (str/split path #"/")))
                         (re-find #"(?i)\.(lain|clj|txt)$" path))
                    [id {:path path :discover? true}]))))
        (:panes @s/workspace)))

(defn- apply-snapshot! [id captured snapshot]
  (if (:discover? captured)
    (when-let [pane (model/pane @s/workspace id)]
      (when (and (:exists snapshot) (not (get-in @s/ui [:file-links id]))
                 (= (:filename pane) (:path captured)))
        (register! id snapshot)
        (when-not (= (:source pane) (:source snapshot))
          (swap! s/ui assoc-in [:file-links id :external] snapshot)
          (persist!)
          (reset! s/message "Saved editor and file differ · both versions preserved"))))
    (reconcile! id captured snapshot)))

(defn attach! [scope api! lifecycle-token current-lifecycle?]
  (try
    (when-let [saved (.getItem js/localStorage storage-key)]
      (swap! s/ui assoc :file-links (js->clj (js/JSON.parse saved) :keywordize-keys false))
      (swap! s/ui update :file-links
             (fn [links] (into {} (map (fn [[id value]] [id (js->clj (clj->js value) :keywordize-keys true)])) links))))
    (catch :default _ nil))
  (let [alive? (atom true) pending? (atom false)
        poll! (fn []
                (when (and @alive? (not @pending?) (not @s/busy?)
                           (= :connected (:status @s/connection)))
                  (let [captured (watched-views)
                        paths (vec (distinct (map :path (vals captured))))
                        epoch (lifecycle-token)]
                    (when (seq paths)
                      (reset! pending? true)
                      (-> (api! {:op "files/poll" :paths paths})
                          (.then (fn [result]
                                   (when (and @alive? (current-lifecycle? epoch) (not @s/busy?))
                                     (let [by-path (into {} (map (juxt :path identity)) (:files result))]
                                       (doseq [[id link] captured]
                                         (when-let [snapshot (get by-path (:path link))]
                                           (apply-snapshot! id link snapshot)))))))
                          (.catch (fn [error]
                                    (when (and @alive? (current-lifecycle? epoch))
                                      (reset! s/message (str "File watcher: " (.-message error))))))
                          (.finally #(reset! pending? false)))))))
        timer (js/setInterval poll! 1000)]
    (poll!)
    (rx/own! scope #(do (reset! alive? false) (js/clearInterval timer)))))
