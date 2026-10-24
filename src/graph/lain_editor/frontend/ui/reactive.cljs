(ns graph.lain-editor.frontend.ui.reactive
  "Owned Missionary processes and writable views into authoritative state.
  Derived cells expose deref/watch only; they cannot be independently mutated."
  (:require [missionary.core :as m]))

(def ^:dynamic *transaction* nil)
(defn scope
  ([] (scope #(js/console.error "Reactive view process failed" %)))
  ([failed!] (atom [] :meta {:on-error failed!})))
(defn own! [owner dispose] (swap! owner conj dispose) dispose)

(defn current-value [source]
  (if (and *transaction* (identical? source (:source *transaction*)))
    @(:pending *transaction*)
    @source))

(defn- update-source! [source f]
  (if (and *transaction* (identical? source (:source *transaction*)))
    (swap! (:pending *transaction*) f)
    (swap! source f)))

(defn transact! [source operation]
  (if *transaction*
    (if (identical? source (:source *transaction*))
      (operation)
      (throw (ex-info "A transaction must have one authoritative source" {})))
    (let [pending (atom @source)
          result (binding [*transaction* {:source source :pending pending}]
                   (operation))]
      (when (not= @source @pending) (reset! source @pending))
      result)))

(deftype Lens [source getter setter identity]
  IDeref
  (-deref [_] (getter (current-value source)))
  IReset
  (-reset! [_ value] (update-source! source #(setter % value)) value)
  ISwap
  (-swap! [this f] (reset! this (f @this)))
  (-swap! [this f a] (reset! this (f @this a)))
  (-swap! [this f a b] (reset! this (f @this a b)))
  (-swap! [this f a b more] (reset! this (apply f @this a b more)))
  IWatchable
  (-notify-watches [_ _ _] nil)
  (-add-watch [this key effect]
    (add-watch source [identity key]
               (fn [_ _ before after]
                 (let [old (getter before) value (getter after)]
                   (when (not= old value) (effect key this old value)))))
    this)
  (-remove-watch [this key] (remove-watch source [identity key]) this))

(defn lens [source getter setter]
  (Lens. source getter setter (random-uuid)))

(deftype Projection [source getter identity]
  IDeref
  (-deref [_] (getter (current-value source)))
  IWatchable
  (-notify-watches [_ _ _] nil)
  (-add-watch [this key effect]
    (add-watch source [identity key]
               (fn [_ _ before after]
                 (let [old (getter before) value (getter after)]
                   (when (not= old value) (effect key this old value)))))
    this)
  (-remove-watch [this key] (remove-watch source [identity key]) this))

(defn read-only [source getter]
  (Projection. source getter (random-uuid)))

(deftype Derived [value]
  IDeref
  (-deref [_] @value)
  IWatchable
  (-notify-watches [_ _ _] nil)
  (-add-watch [this key effect]
    (add-watch value key (fn [_ _ before after] (effect key this before after)))
    this)
  (-remove-watch [this key] (remove-watch value key) this))

(defn input! [_ value] (atom value))

(defn- consume! [owner flow effect]
  (let [alive? (atom true)
        cancel ((m/reduce (fn [_ value] (effect value) nil) nil flow)
                (fn [_] nil)
                (fn [error]
                  (when @alive?
                    ((:on-error (meta owner)) error))))]
    (own! owner (fn []
                  (when @alive? (reset! alive? false) (cancel))))))

(defn derive! [owner f & inputs]
  (let [value (atom (apply f (map deref inputs)))
        derived (Derived. value)]
    (consume! owner
              (m/eduction (dedupe) (apply m/latest f (map m/watch inputs)))
              #(when (not= @value %) (reset! value %)))
    derived))

(defn watch! [owner cell effect]
  (consume! owner (m/eduction (dedupe) (m/watch cell)) effect))

(defn dispose! [owner]
  (let [disposers @owner]
    (reset! owner [])
    (doseq [dispose (reverse disposers)]
      (try (dispose)
           (catch :default error
             (js/console.error "Reactive disposal failed" error))))))
