(ns core2.rewrite
  (:require [clojure.string :as str]
            [clojure.zip :as zip]))

(set! *unchecked-math* :warn-on-boxed)

;; Rewrite engine.

(defprotocol Rule
  (--> [_ loc])
  (<-- [_ loc]))

(defn- zip-dispatch [loc direction-fn]
  (let [tree (zip/node loc)]
    (if-let [rule (and (vector? tree)
                       (get-in (meta loc) [::ctx 0 :rules (first tree)]))]
      (direction-fn rule loc)
      loc)))

(defn- ->ctx [loc]
  (first (::ctx (meta loc))))

(defn- init-ctx [loc ctx]
  (vary-meta loc assoc ::ctx [ctx]))

(defn vary-ctx [loc f & args]
  (vary-meta loc update-in [::ctx 0] (fn [ctx]
                                       (apply f ctx args))))

(defn conj-ctx [loc k v]
  (vary-ctx loc update k conj v))

(defn- pop-ctx [loc]
  (vary-meta loc update ::ctx (comp vec rest)))

(defn- push-ctx [loc ctx]
  (vary-meta loc update ::ctx (partial into [ctx])))

(defn text-nodes [loc]
  (filterv string? (flatten (zip/node loc))))

(defn single-child? [loc]
  (= 1 (count (rest (zip/children loc)))))

(defn ->before [before-fn]
  (reify Rule
    (--> [_ loc]
      (before-fn loc (->ctx loc)))

    (<-- [_ loc] loc)))

(defn ->after [after-fn]
  (reify Rule
    (--> [_ loc] loc)

    (<-- [_ loc]
      (after-fn loc (->ctx loc)))))

(defn ->text [kw]
  (->before
   (fn [loc _]
     (vary-ctx loc assoc kw (str/join (text-nodes loc))))))

(defn ->scoped
  ([rule-overrides]
   (->scoped rule-overrides nil (fn [loc _]
                                  loc)))
  ([rule-overrides after-fn]
   (->scoped rule-overrides nil after-fn))
  ([rule-overrides ->ctx-fn after-fn]
   (let [->ctx-fn (or ->ctx-fn ->ctx)]
     (reify Rule
       (--> [_ loc]
         (push-ctx loc (update (->ctx-fn loc) :rules merge rule-overrides)))

       (<-- [_ loc]
         (-> (pop-ctx loc)
             (after-fn (->ctx loc))))))))

(defn rewrite-tree [tree ctx]
  (loop [loc (init-ctx (zip/vector-zip tree) ctx)]
    (if (zip/end? loc)
      (zip/root loc)
      (let [loc (zip-dispatch loc -->)]
        (recur (cond
                 (zip/branch? loc)
                 (zip/down loc)

                 (seq (zip/rights loc))
                 (zip/right (zip-dispatch loc <--))

                 :else
                 (loop [p loc]
                   (let [p (zip-dispatch p <--)]
                     (if-let [parent (zip/up p)]
                       (if (seq (zip/rights parent))
                         (zip/right (zip-dispatch parent <--))
                         (recur parent))
                       [(zip/node p) :end])))))))))

;; Attribute Grammar spike.

;; See:
;; https://inkytonik.github.io/kiama/Attribution
;; https://arxiv.org/pdf/2110.07902.pdf
;; https://haslab.uminho.pt/prmartins/files/phd.pdf
;; https://github.com/christoff-buerger/racr

;; Ideas:

;; Strategies?
;; Replace rest of this rewrite ns entirely?

(defn- attr-children [attr-fn loc]
  (loop [loc (zip/right (zip/down loc))
         acc []]
    (if loc
      (recur (zip/right loc)
             (if (zip/branch? loc)
               (if-some [v (attr-fn loc)]
                 (conj acc v)
                 acc)
               acc))
      acc)))

(defn- zip-next-skip-subtree [loc]
  (or (zip/right loc)
      (loop [p loc]
        (when-let [p (zip/up p)]
          (or (zip/right p)
              (recur p))))))

(defn- zip-match [pattern-loc loc]
  (loop [pattern-loc pattern-loc
         loc loc
         acc {}]
    (cond
      (or (nil? pattern-loc)
          (zip/end? pattern-loc))
      acc

      (or (nil? loc)
          (zip/end? loc))
      nil

      (and (zip/branch? pattern-loc)
           (zip/branch? loc))
      (when (= (count (zip/children pattern-loc))
               (count (zip/children loc)))
        (recur (zip/down pattern-loc) (zip/down loc) acc))

      :else
      (let [pattern-node (zip/node pattern-loc)
            node (zip/node loc)]
        (cond
          (= pattern-node node)
          (recur (zip-next-skip-subtree pattern-loc) (zip-next-skip-subtree loc) acc)

          (and (symbol? pattern-node)
               (= node (get acc pattern-node node)))
          (recur (zip/next pattern-loc)
                 (zip-next-skip-subtree loc)
                 (cond-> acc
                   (not= '_ pattern-node) (assoc pattern-node
                                                 (if (:z (meta pattern-node))
                                                   loc
                                                   node))))

          :else
          nil)))))

(defmacro zmatch {:style/indent 1} [loc & [pattern expr & clauses]]
  (when pattern
    (if expr
      (let [vars (->> (flatten pattern)
                      (filter symbol?)
                      (remove '#{_}))]
        `(let [loc# ~loc
               loc# (if (:zip/make-node (meta loc#))
                      loc#
                      (zip/vector-zip loc#))]
           (if-let [{:syms [~@vars] :as acc#} (zip-match (zip/vector-zip '~pattern) loc#)]
             ~expr
             (zmatch loc# ~@clauses))))
      pattern)))

(declare repmin globmin locmin)

(defn repmin [loc]
  (zmatch loc
    [:fork _ _] (->> (attr-children repmin loc)
                     (into [:fork]))
    [:leaf _] [:leaf (globmin loc)]))

(defn locmin [loc]
  (zmatch loc
    [:fork _ _] (->> (attr-children locmin loc)
                     (reduce min))
    [:leaf n] n))

(defn globmin [loc]
  (if-let [p (zip/up loc)]
    (globmin p)
    (locmin loc)))

(defn annotate-tree [tree attr-vars]
  (let [attrs (zipmap attr-vars (map (comp memoize deref) attr-vars))]
    (with-redefs-fn attrs
      #(loop [loc (zip/vector-zip tree)]
         (if (zip/end? loc)
           (zip/node loc)
           (recur (zip/next (if (zip/branch? loc)
                              (if-let [acc (some->> (for [[k attr-fn] attrs
                                                          :let [v (attr-fn loc)]
                                                          :when (some? v)]
                                                      [(:name (meta k)) v])
                                                    (not-empty)
                                                    (into {}))]
                                (zip/edit loc vary-meta merge acc)
                                loc)
                              loc))))))))

;; Strategic Zippers based on Ztrategic
;; https://arxiv.org/pdf/2110.07902.pdf
;; https://www.di.uminho.pt/~joost/publications/SBLP2004LectureNotes.pdf

;; Type Preserving

(defn seq-tp [& xs]
  (fn [z]
    (reduce
     (fn [acc x]
       (if-some [acc (x acc)]
         acc
         (reduced nil)))
     z
     xs)))

(defn choice-tp [& xs]
  (fn [z]
    (reduce
     (fn [_ x]
       (when-some [z (x z)]
         (reduced z)))
     nil
     xs)))

(declare all-tp one-tp)

(defn full-td-tp [f]
  (fn self [z]
    ((seq-tp f (all-tp self)) z)))

(defn full-bu-tp [f]
  (fn self [z]
    ((seq-tp (all-tp self) f) z)))

(defn once-td-tp [f]
  (fn self [z]
    ((choice-tp f (one-tp self)) z)))

(defn once-bu-tp [f]
  (fn self [z]
    ((choice-tp (one-tp self) f) z)))

(defn stop-td-tp [f]
  (fn self [z]
    ((choice-tp f (all-tp self)) z)))

(defn stop-bu-tp [f]
  (fn self [z]
    ((choice-tp (all-tp self) f) z)))

(declare z-try-apply-m z-try-apply-mz)

(defn- maybe-keep [x y]
  (fn [z]
   (if-some [r (y z)]
     (if-some [k (x r)]
       k
       r)
     (x z))))

(defn adhoc-tp [f g]
  (maybe-keep f (z-try-apply-m g)))

(defn adhoc-tpz [f g]
  (maybe-keep f (z-try-apply-mz g)))

(defn id-tp [x] x)

(defn fail-tp [_])

(defn- all-tp-right [f]
  (fn [z]
    (if-some [r (zip/right z)]
      (zip/left (f r))
      z)))

(defn- all-tp-down [f]
  (fn [z]
    (if-some [d (zip/down z)]
      (zip/up (f d))
      z)))

(defn all-tp [f]
  (seq-tp (all-tp-down f) (all-tp-right f)))

(defn one-tp-right [f]
  (fn [z]
    (some->> (zip/right z)
             (f)
             (zip/left))))

(defn one-tp-down [f]
  (fn [z]
    (some->> (zip/down z)
             (f)
             (zip/up))))

(defn one-tp [f]
  (choice-tp (one-tp-down f) (one-tp-right f)))

(def mono-tp (partial adhoc-tp fail-tp))

(def mono-tpz (partial adhoc-tpz fail-tp))

(defn try-tp [f]
  (choice-tp f id-tp))

(defn repeat-tp [f]
  (fn [z]
    (if-some [z (f z)]
      (recur z)
      z)))

(defn innermost [f]
  (repeat-tp (once-bu-tp f)))

(defn outermost [f]
  (repeat-tp (once-td-tp f)))

;; Type Unifying

(defn- mempty [z]
  ((get (meta z) :zip/mempty vector)))

(defn- mappend [z]
  (get (meta z) :zip/mappend into))

(defn seq-tu [& xs]
  (fn [z]
    (transduce (map (fn [x] (x z)))
               (mappend z)
               (mempty z)
               (reverse xs))))

(def choice-tu choice-tp)

(declare all-tu one-tu)

(defn full-td-tu [f]
  (fn self [z]
    ((seq-tu (all-tu self) f) z)))

(defn full-bu-tu [f]
  (fn self [z]
    ((seq-tu f (all-tu self)) z)))

(defn once-td-tu [f]
  (fn self [z]
    ((choice-tu f (one-tu self)) z)))

(defn once-bu-tu [f]
  (fn self [z]
    ((choice-tu (one-tu self) f) z)))

(defn stop-td-tu [f]
  (fn self [z]
    ((choice-tu f (all-tu self)) z)))

(defn stop-bu-tu [f]
  (fn self [z]
    ((choice-tu (all-tu self) f) z)))

(defn- all-tu-down [f]
  (fn [z]
    (when-some [d (zip/down z)]
      (f d))))

(defn- all-tu-right [f]
  (fn [z]
    (when-some [r (zip/right z)]
      (f r))))

(defn all-tu [f]
  (fn [z]
    ((seq-tu (all-tu-down f) (all-tu-right f)) z)))

(defn one-tu [f]
  (fn [z]
    ((choice-tu (all-tu-down f) (all-tu-right f)) z)))

(declare z-try-reduce-m z-try-reduce-mz)

(defn adhoc-tu [f g]
  (choice-tu (z-try-reduce-m g) f))

(defn adhoc-tuz [f g]
  (choice-tu (z-try-reduce-mz g) f))

(defn fail-tu [_])

(defn const-tu [x]
  (constantly x))

(def mono-tu (partial adhoc-tu fail-tu))

(def mono-tuz (partial adhoc-tuz fail-tu))

(defn z-try-reduce-mz [f]
  (fn [z]
    (some-> (zip/node z) (f z))))

(defn z-try-reduce-m [f]
  (fn [z]
    (some-> (zip/node z) (f))))

(defn z-try-apply-mz [f]
  (fn [z]
    (some->> (f (zip/node z) z)
             (zip/replace z))))

(defn z-try-apply-m [f]
  (fn [z]
    (some->> (f (zip/node z))
             (zip/replace z))))

(defn with-tu-monoid [z mempty mappend]
  (vary-meta z assoc :zip/mempty mempty :zip/mappend mappend))

(comment
  (let [tree [:fork [:fork [:leaf 1] [:leaf 2]] [:fork [:leaf 3] [:leaf 4]]]
        tree (annotate-tree tree [#'repmin #'locmin #'globmin])]
    (keep meta (tree-seq vector? seq tree)))

  (zip-match (zip/vector-zip '[:leaf n])
             (zip/vector-zip [:leaf 2]))

  ((full-td-tp (adhoc-tp id-tp (fn [x] (prn x) (when (number? x) (str x)))))
   (zip/vector-zip [:fork [:fork [:leaf 1] [:leaf 2]] [:fork [:leaf 3] [:leaf 4]]]))

  ((full-bu-tu (mono-tu (fn [x] (prn x) (when (number? x) [x]))))
   (zip/vector-zip [:fork [:fork [:leaf 1] [:leaf 2]] [:fork [:leaf 3] [:leaf 4]]]))

  (= ["a" "c" "b" "c"]
     ((full-td-tu (mono-tu
                   #(zmatch %
                      [:assign s _ _] [s]
                      [:nested-let s _ _] [s]
                      _ [])))
      (zip/vector-zip
       [:let
        [:assign "a"
         [:add [:var "b"] [:const 0]]
         [:assign "c" [:const 2]
          [:nested-let "b" [:let [:assign "c" [:const 3] [:empty-list]]
                            [:add [:var "c"] [:var "c"]]]
           [:empty-list]]]]
        [:sub [:add [:var "a"] [:const 7]] [:var "c"]]]))))