(ns graph.lain-editor.frontend.value-folding
  "Pure view-state projection of public value rows. Never changes source values.")

(defn- annotate [rows prefix]
  (:rows
   (reduce
    (fn [{:keys [parents counts rows]} row]
      (let [depth (or (:depth row) 0)
            parent (get parents (dec depth) prefix)
            label (or (:key row) "")
            count-key [parent label]
            ordinal (get counts count-key 0)
            path (conj parent [label ordinal])
            annotated (assoc row ::path path)
            annotated
            (if-let [linked (:linkedList row)]
              (assoc annotated :linkedList
                     (cond-> (assoc linked :items
                                    (mapv (fn [index items]
                                            (annotate items (conj path [:item index])))
                                          (range) (:items linked)))
                       (get-in linked [:tail :rows])
                       (update-in [:tail :rows] annotate (conj path [:tail]))))
              annotated)]
        {:parents (assoc (into {} (filter #(< (key %) depth)) parents) depth path)
         :counts (assoc counts count-key (inc ordinal))
         :rows (conj rows annotated)}))
    {:parents {} :counts {} :rows []} rows)))

(defn- row-tree [rows]
  (mapcat (fn [row]
            (cons row
                  (concat (mapcat row-tree (get-in row [:linkedList :items]))
                          (row-tree (get-in row [:linkedList :tail :rows]))))) rows))

(defn options
  "Stable field paths, independent of leaf values and recording frame indexes."
  [rows]
  (mapv (fn [row] {:path (::path row) :depth (or (:depth row) 0)})
        (row-tree (annotate rows []))))

(defn field-key [context cell section path] [context cell section path])

(defn set-unfolded [folded field unfolded?]
  (if unfolded? (disj folded field) (conj folded field)))

(defn unfold-all [folded context]
  (into #{} (remove #(= context (first %))) folded))

(defn- prefix? [parent path]
  (and (< (count parent) (count path))
       (= parent (subvec path 0 (count parent)))))

(defn project-rows
  ([rows folded context cell section]
   (project-rows rows folded context cell section false))
  ([rows folded context cell section controls?]
  (let [annotated (annotate rows [])
        branch-paths (into #{} (mapcat #(butlast (reductions conj [] (::path %))))
                           (row-tree annotated))]
   (letfn [(project [rows]
            (:rows
             (reduce
              (fn [{:keys [hidden rows] :as result} row]
                (let [path (::path row)
                      field (field-key context cell section path)
                      expanded? (not (contains? folded field))
                      branch? (or (:linkedList row) (contains? branch-paths path))
                      row (cond-> row
                            (and controls? (or branch? (not expanded?)))
                            (assoc :foldControl {:key field :expanded expanded?
                                                 :label (or (not-empty (:key row)) "value")}))]
                  (if (some #(prefix? % path) hidden)
                    result
                    (if (contains? folded (field-key context cell section path))
                      {:hidden (conj hidden path)
                       :rows (conj rows (-> row (dissoc ::path :linkedList :sourceToken :sourceReference)
                                            (assoc :value "…")))}
                      {:hidden hidden
                       :rows (conj rows
                                   (cond-> (dissoc row ::path)
                                     (:linkedList row)
                                     (update :linkedList
                                             (fn [linked]
                                               (cond-> (update linked :items #(mapv project %))
                                                 (get-in linked [:tail :rows])
                                                 (update-in [:tail :rows] project))))))}))))
              {:hidden [] :rows []} rows)))]
    (project annotated)))))

(defn project-node
  ([node folded context] (project-node node folded context false))
  ([node folded context controls?]
  (reduce (fn [node section]
            (if (contains? node section)
              (update node section project-rows folded context (:id node) section controls?)
              node)) node [:valueGrid :contentGrid])))

(defn project-graph
  ([graph folded context] (project-graph graph folded context false))
  ([graph folded context controls?]
   (update graph :nodes #(mapv (fn [node] (project-node node folded context controls?)) %))))
