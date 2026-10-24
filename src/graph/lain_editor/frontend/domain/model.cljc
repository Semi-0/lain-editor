(ns graph.lain-editor.frontend.domain.model
  "Authoritative editable program data. Presentation and runtime objects stay outside.")

(def source-fields
  [:id :title :filename :source :executedSource :extensions :extensionEdits
   :extensionDraft :development :previousExecutedSource])

(defn block [source]
  (select-keys source source-fields))

(defn block-id [block] (:id block))

(defn block-view
  "Deliberately public source-data view for file codecs and other projections."
  [block]
  block)

(defn archive [id order source]
  {:id id :order order :block (block source)})

(defn archive-id [archive] (:id archive))
(defn archive-order [archive] (:order archive))
(defn archive-block [archive] (:block archive))

(defn model [blocks closed-blocks next-close-order]
  {:blocks (mapv block blocks)
   :closed-blocks (vec closed-blocks)
   :next-close-order next-close-order})

(defn blocks [model] (:blocks model))
(defn closed-blocks [model] (:closed-blocks model))
(defn next-close-order [model] (:next-close-order model))
