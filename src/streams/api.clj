(ns streams.api
  "Simple api for creating streams based on random sampling from distributions
  along with minimal arithmetic and manipulation pathways.  Arithmetic ops can be
  used on scalars and streams.

  A stream is an object that when called as a function with no arguments returns
  the next value in the stream but that also efficiently implements clojure.lang.IReduceInit
  and clojure.lang.IReduce.  These are lazy noncaching versions of clojure's sequences.

  Streams are strictly serial entities when they are being iterated.  There are no provisions
  made to protect against threading issues.

  Only arithmetic ops are specialized to doubles for performance reasons; streams can be
  streams of arbitrary objects or really anything that implements IReduceInit.

```clojure
user> (require '[streams.api :as streams])
nil
user> (streams/sample 20 (streams/+ (streams/uniform-stream)
                                    (streams/* 2.0 (streams/uniform-stream))))
[1.5501202319376306, 0.7635588117246281, 2.3532562778994093, 2.209371262799305,
 1.3152501796238574, 1.0452647068536018, 0.7894558426559145, 2.198800934691462,
 0.26506472311487705, 2.538111046716471, 2.9001166286861992, 1.3705779064113792,
 2.1755184584145306, 1.3351040137971486, 1.6120692556203424, 1.6107428912151116,
 2.2510286054117365, 0.8765206662618311, 1.213693353303307, 1.2334256767045018]
```"
  (:require [ham-fisted.api :as hamf]
            [ham-fisted.protocols :as hamf-p]
            [fastmath.random :as fast-r]
            [fastmath.protocols :as fast-p])
  (:import [ham_fisted Transformables ITypedReduce Casts IFnDef IFnDef$O]
           [java.util.function Supplier Predicate]
           [java.util Random Iterator NoSuchElementException Map]
           [clojure.lang IDeref IFn ISeq ArraySeq])
  (:refer-clojure :exclude [take filter map + - / *]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(deftype ^:private CountingIter [^{:unsynchronized-mutable true
                                   :tag long} n
                                 data-fn]
  Iterator
  (hasNext [this] (> n 0))
  (next [this]
    (when (<= n 0)
      (throw (NoSuchElementException. "Iteration out of range")))
    (let [v (data-fn)
          nn (unchecked-dec n)]
      (set! n nn)
      v)))


(defn- iter
  ^Iterator [s]
  (cond
    (number? s)
    (reify Iterator (hasNext [t] true) (next [t] s))
    (nil? s)
    (reify Iterator (hasNext [t] false) (next [t] (throw (NoSuchElementException.))))
    (instance? Map s)
    (.iterator (.entrySet ^Map s))
    (instance? Iterable s)
    (.iterator ^Iterable s)
    :else
    (.iterator ^Iterable (hamf-p/->iterable s))))


(defmacro stream
  ([code]
   `(stream nil ~code))
  ([l code]
   `(let [l# ~l]
      (if l#
        (let [l# (long l#)]
          (reify
            ITypedReduce
            (reduce [this# rfn# acc#]
              (loop [idx# 0
                     acc# acc#]
                (if (and (< idx# l#)
                         (not (reduced? acc#)))
                  (recur (unchecked-inc idx#) (rfn# acc# ~code))
                  (if (reduced? acc#)
                    (deref acc#)
                    acc#))))
            Iterable
            (iterator [this#] (CountingIter. l# this#))
            IFnDef$O
            (invoke [this#] ~code)
            IDeref
            (deref [this#] (.invoke this#))))
        (reify
          ITypedReduce
          (reduce [this rfn# acc#]
            (loop [acc# acc#]
              (if (not (reduced? acc#))
                (recur (rfn# acc# ~code))
                (deref acc#))))
          Iterable
          (iterator [this#]
            (reify Iterator
              (hasNext [i] true)
              (next [i] (.invoke this#))))
          IFnDef$O
          (invoke [this#] ~code)
          IDeref
          (deref [this#] (.invoke this#)))))))


(defn uniform-stream
  "Create a uniform stream with values [0-1]"
  ([n ^Random r]
   (stream n (.nextDouble r)))
  ([n]
   (uniform-stream n (Random.)))
  ([]
   (uniform-stream nil (Random.))))


(defn gaussian-stream
  "Create a gaussian stream with mean 0 variance 1"
  ([n ^Random r]
   (stream n (.nextGaussian r)))
  ([n]
   (gaussian-stream n (Random.)))
  ([]
   (gaussian-stream nil (Random.))))


(defn fastmath-stream
  "Create a stream based on a
  [fastmath distribution](https://generateme.github.io/fastmath/fastmath.random.html#var-distribution)."
  ([n key opts] (let [dist (fast-r/distribution key opts)]
              (stream n (fast-p/sample dist))))
  ([key opts] (fastmath-stream nil key opts))
  ([key] (fastmath-stream nil key nil)))


(defn- to-supplier
  [s]
  (if (number? s)
    (stream s)
    s))


(deftype ^:private TakeNReducer [^{:unsynchronized-mutable true
                         :tag long} n
                       rfn]
  IFnDef
  (invoke [this acc v]
    (let [acc (rfn acc v)
          nn (unchecked-dec n)]
      (set! n nn)
      (if (> nn 0)
        acc
        (reduced acc)))))


(deftype ^:private CountingIterIter [^{:unsynchronized-mutable true
                                       :tag long} n
                                     ^Iterator data-iter
                                     ^:unsynchronized-mutable has-elem
                                     ^:unsynchronized-mutable next-obj]
  Iterator
  (hasNext [this] has-elem)
  (next [this]
    (when (not has-elem)
      (throw (NoSuchElementException. "Iteration out of range")))
    (let [v next-obj
          nn (unchecked-dec n)
          he (and (>= nn 0) (.hasNext data-iter))]
      (set! has-elem he)
      (set! next-obj (if he (.next data-iter) nil))
      (set! n nn)
      v)))


(defn take
  "Take at most N elements from this stream.  Returns a new stream."
  [^long n s]
  (cond
    (number? s)
    (stream n s)
    (nil? (seq s))
    '()
    :else
    (reify
      ITypedReduce
      (reduce [this rfn acc]
        (reduce (TakeNReducer. n rfn) acc s))
      Iterable
      (iterator [this]
        (doto (CountingIterIter. n (iter s) true nil)
          (.next)))
      IFnDef$O
      (invoke [this]
        (if (<= n 0)
          (throw (NoSuchElementException. "Iteration out of range"))
          (.next (iter s)))))))


(defn sample
  "Sample stream into a double array.  If n is not provided, stream must either
  already have a limit or an oom is imminent."
  (^doubles [s]
   (hamf/double-array s))
  (^doubles [n s]
   (hamf/double-array (take n s))))


(deftype ^:private FilterIter [^Iterator data-iter
                               ^Predicate pred
                               ^:unsynchronized-mutable next-obj
                               ^:unsynchronized-mutable has-item]
  Iterator
  (hasNext [this] has-item)
  (next [this]
    (when-not has-item
      (throw (NoSuchElementException. "Iteration past range")))
    (let [v next-obj]
      (loop [he (.hasNext data-iter)]
        (if he
          (let [vv (.next data-iter)]
            (if (.test pred vv)
              (do
                (set! next-obj vv)
                (set! has-item true))
              (recur (.hasNext data-iter))))
          (do
            (set! next-obj nil)
            (set! has-item false))))
      v)))


(defn filter
  "Filter a stream based on a predicate.  Returns a new stream."
  [pred s]
  (cond
    (number? s)
    (if (pred s)
      s
      '())
    (or (nil? (seq s)))
    '()
    :else
    (let [^Predicate pred (if (instance? Predicate pred)
                            pred
                            (reify Predicate
                              (test [this v] (boolean (pred v)))))]
      (reify
        ITypedReduce
        (reduce [this rfn acc]
          (reduce (fn [acc v]
                    (if (.test pred v)
                      (rfn acc v)
                      acc))
                  acc s))
        Iterable
        (iterator [this]
          (doto (FilterIter. (iter s) pred nil true)
            (.next)))
        IFnDef$O
        (invoke [this]
          (.next (iter this)))))))


(defn- map-args->iter-create
  [argseq]
  (let [argseq (into [] (comp cat (clojure.core/map iter))
                     argseq)
        next-data (object-array (count argseq))]
    (fn []
      (when
          (reduce (hamf/indexed-accum
                   acc idx v
                   (let [^Iterator v v]
                     (if (and acc (.hasNext v))
                       (do (aset next-data idx (.next v))
                           true)
                       false)))
                  true
                  argseq)
        next-data))))

(defn- map-n
  [mapfn argseq]
  (let [update-create #(map-args->iter-create argseq)]
    (reify
      ITypedReduce
      (reduce [this rfn acc]
        (let [updater (update-create)]
          (loop [acc acc
                 next-args (updater)]
            (if (and next-args (not (reduced? acc)))
              (recur (rfn acc (mapfn next-args))
                     (updater))
              (if (reduced? acc)
                (deref acc)
                acc)))))
      Iterable
      (iterator [this]
        (let [updater (update-create)
              next-args* (volatile! (updater))]
          (reify Iterator
            (hasNext [i] (boolean @next-args*))
            (next [i]
              (let [v (mapfn @next-args*)]
                (vreset! next-args* (updater))
                v)))))
      IFnDef$O
      (invoke [this]
        (when-let [fn-args ((update-create))]
          (mapfn fn-args)))
      IDeref
      (deref [this] (.invoke this)))))

(defn map
  "Map a function onto one or more streams.  Returns a new stream whose limit is the least
  of any of the streams."
  ([mapfn s]
   (cond
     (number? s)
     (mapfn s)
     (nil? (seq s)) '()
     :else
     (reify
       ITypedReduce
       (reduce [this rfn acc]
         (reduce (fn [acc v]
                   (rfn acc (mapfn v)))
                 acc s))
       Iterable
       (iterator [this]
         (let [src-iter (iter s)]
           (reify Iterator
             (hasNext [this] (.hasNext src-iter))
             (next [this] (mapfn (.next src-iter))))))
       IFnDef$O
       (invoke [this]
         (mapfn (.next (iter s)))))))
  ([mapfn a b]
   (cond (or (number? a) (number? b))
     (cond
       (and (number? a) (number? b))
       (mapfn a b)
       (number? a)
       (map (fn [bb] (mapfn a bb)) b)
       :else
       (map (fn [aa] (mapfn aa b)) a))
     (and (nil? (seq a)) (nil? (seq b)))
     '()
     :else
     (map-n #(mapfn (aget ^objects % 0) (aget ^objects % 1)) [[a b]])))
  ([mapfn a b c]
   (if (and (number? a) (number? b) (number? c))
     (mapfn a b c)
     (map-n #(mapfn (aget ^objects % 0) (aget ^objects % 1) (aget ^objects % 2))
            [[a b c]])))
  ([mapfn a b c & args]
   (map-n #(.applyTo ^IFn mapfn (ArraySeq/create ^objects %))
          [[a b c] args])))


(defmacro def-double-op
  "Define a unary and binary double from clojure.core or another library such as +.
  Operation must have 1,2,+ arities."
  [op-sym]
  (let [core-sym (with-meta (if (namespace op-sym)
                              op-sym
                              (symbol (str "clojure.core/" (name op-sym))))
                   {:tag 'clojure.lang.IFn})
        op-sym (symbol (name op-sym))]
    ;;typehinting these to produce the ideal functions signatures
    `(let [un-arg# (fn ^double [^double v#] (~core-sym v#))
           bi-arg# (fn ^double [^double a# ^double b#] (~core-sym a# b#))
           tri-arg# (fn ^double [^double a# ^double b# ^double c#] (~core-sym a# b# c#))]
       (defn ~op-sym
         ~(format "Binary or unary operation %s.  Operates in the space of doubles. Arguments
may be streams or double scalars." (name op-sym))
         ([~'a] (map un-arg# ~'a))
         ([~'a ~'b] (map bi-arg# ~'a ~'b))
         ([~'a ~'b ~'c] (map tri-arg# ~'a ~'b ~'c))
         ([~'a ~'b ~'c & ~'args] (map-n #(.applyTo ~core-sym (ArraySeq/create %))
                                        [[~'a ~'b ~'c] ~'args]))))))


(def-double-op +)
(def-double-op *)
(def-double-op /)
(def-double-op -)


(defmacro def-double-binary-op
  "Define a unary and binary double from clojure.core or another library such as +.
  Operation need only have single arity of 2."
  ([op-sym docstr]
   (let [core-sym (if (namespace op-sym)
                    op-sym
                    (symbol (str "clojure.core/" (name op-sym))))
         op-sym (symbol (name op-sym))]
     ;;typehinting these to produce the ideal functions signatures
     `(let [bi-arg# (fn ^double [^double a# ^double b#] (~core-sym a# b#))]
        (defn ~op-sym
          ~docstr
          ([~'a ~'b] (map bi-arg# ~'a ~'b))))))
  ([op-sym]
   `(def-double-binary-op ~op-sym ~(format "Binary operation %s.  Operates in the space of doubles. Arguments
may be streams or double scalars." (name op-sym)))))


(def-double-binary-op fastmath.core/fpow "Fast pow where right-hand-side is interpreted as integer values.")


(defmacro def-double-unary-op
  "Define a unary and binary double from clojure.core or another library such as +.
  Operation need only have single arity of 2."
  ([op-sym docstr]
   (let [core-sym (if (namespace op-sym)
                    op-sym
                    (symbol (str "clojure.core/" (name op-sym))))
         op-sym (symbol (name op-sym))]
     ;;typehinting these to produce the ideal functions signatures
     `(let [un-arg# (fn ^double [^double a#] (~core-sym a#))]
        (defn ~op-sym
          ~docstr
          ([~'a] (map un-arg# ~'a))))))
  ([op-sym]
   `(def-double-unary-op ~op-sym ~(format "Unary operation %s.  Operates in the space of doubles. Argument may be a streams or a double." (name op-sym)))))


(def-double-unary-op fastmath.core/log1p)
