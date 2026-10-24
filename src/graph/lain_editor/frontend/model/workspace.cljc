(ns graph.lain-editor.frontend.model.workspace
  "Workspace file compatibility boundary. There is no mutable document mirror."
  (:require [graph.lain-editor.frontend.domain.model :as domain]
            [graph.lain-editor.workspace.model :as workspace]))

(defn decode [input]
  (let [document (workspace/upgrade input)]
    {:domain (domain/model (:panes document)
                           (mapv #(domain/archive (:id %) (:order %) (:pane %)) (:closedBlocks document))
                           (:nextClosedBlockOrder document))
     :workspace-ui
     {:document (dissoc document :panes :closedBlocks :nextClosedBlockOrder)
      :panes (into {} (map (fn [pane]
                            [(:id pane) (apply dissoc pane domain/source-fields)]))
                   (:panes document))
      :closed (into {} (map (fn [entry]
                             [(:id entry)
                              {:entry (dissoc entry :pane :id :order)
                               :pane (apply dissoc (:pane entry) domain/source-fields)}]))
                    (:closedBlocks document))}}))

(defn encode [domain workspace-ui]
  (-> (:document workspace-ui)
      (assoc :panes (mapv (fn [block]
                            (merge (get-in workspace-ui [:panes (domain/block-id block)])
                                   (domain/block-view block)))
                          (domain/blocks domain)))
      (assoc :closedBlocks
             (mapv (fn [entry]
                     (let [id (domain/archive-id entry)]
                       (merge (get-in workspace-ui [:closed id :entry])
                              {:id id :order (domain/archive-order entry)
                               :pane (merge (get-in workspace-ui [:closed id :pane])
                                            (domain/block-view (domain/archive-block entry)))})))
                   (domain/closed-blocks domain)))
      (assoc :nextClosedBlockOrder (domain/next-close-order domain))))

(defn document [app]
  (encode (:domain app) (get-in app [:ui :workspace])))

(defn replace-document [app input]
  (let [{:keys [domain workspace-ui]} (decode input)
        ids (set (map domain/block-id (domain/blocks domain)))]
    (-> app
        (assoc :domain domain)
        (assoc-in [:ui :workspace] workspace-ui)
        (update-in [:ui :panes-ui] #(select-keys % ids)))))

(defn update-document [app f & args]
  (replace-document app (apply f (document app) args)))
