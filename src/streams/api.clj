(ns streams.api
  "Simple api for creating streams based on random sampling from distributions
  along with minimal arithmetic and manipulation pathways.  Arithmetic ops can be
  used on scalars and streams.

  A stream is an object that when called as a function with no arguments returns
  the next value in the stream but that also efficiently implements clojure.lang.IReduceInit
  and clojure.lang.IReduce.

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
            [streams.protocols :as streams-p]
            [fastmath.random :as fast-r]
            [fastmath.protocols :as fast-p])
  (:import [ham_fisted Transformables ITypedReduce Casts IFnDef IFnDef$O]
           [streams.protocols Limited]
           [java.util.function Supplier Predicate]
           [java.util Random]
           [clojure.lang IDeref IFn ISeq])
  (:refer-clojure :exclude [take filter map + - / *]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defmacro stream
  ([code]
   `(reify
      ITypedReduce
      (reduce [this rfn# acc#]
        (loop [acc# acc#]
          (if (not (reduced? acc#))
            (recur (rfn# acc# ~code))
            (deref acc#))))
      IFnDef$O
      (invoke [this#] ~code)
      IDeref
      (deref [this#] ~code)))
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
            Limited
            (limit [this] l#)
            IFnDef$O
            (invoke [this#] ~code)
            IDeref
            (deref [this#] ~code)))
        (stream ~code)))))


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
    (fn [] s)
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


(defn take
  "Take at most N elements from this stream.  Returns a new stream."
  [^long n s]
  (let [n (long (if-let [l (streams-p/limit s)]
                  (min n (long l))
                  n))
        s (to-supplier s)]
    (reify
      ITypedReduce
      (reduce [this rfn acc]
        (reduce (TakeNReducer. n rfn) acc s))
      Limited
      (limit [this] n)
      IFnDef$O
      (invoke [this] (s)))))


(defn sample
  "Sample stream into a double array.  If n is not provided, stream must either
  already have a limit or a default one of 1000 is provided."
  (^doubles [s]
   (if-let [l (streams-p/limit s)]
     (hamf/double-array s)
     (hamf/double-array (take 1000 s))))
  (^doubles [n s]
   (hamf/double-array (take n s))))


(defn- nil-min
  ([] nil)
  ([a] a)
  ([a b]
   (cond
     (nil? a) b
     (nil? b) a
     :else
     (min (long a) (long b)))))


(defn filter
  "Filter a stream based on a predicate.  Returns a new stream without changing its limit."
  [pred s]
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
      Limited
      (limit [this] (streams-p/limit s))
      IFnDef$O
      (invoke [this]
        (loop []
          (let [v (s)]
            (if (.test pred v)
              v
              (recur))))))))


(defn- supplier-value-seq
  ^ISeq [supplier-vec]
  (let [^ISeq sv (seq supplier-vec)]
    (reify ISeq
      (first [this] ((.first sv)))
      (next [this] (when-let [nn (.next sv)]
                     (supplier-value-seq nn)))
      (more [this] (when-let [nn (.more sv)]
                     (supplier-value-seq nn)))
      (seq [this] this)
      (cons [this o]
        (supplier-value-seq (cons o supplier-vec))))))

(defn- map-n
  [mapfn a b c args]
  (let [argseq [[a b c] args]
        args (-> (into [] (comp cat (clojure.core/map to-supplier))
                       argseq)
                 (supplier-value-seq))]
    (stream (transduce (comp cat (clojure.core/map streams-p/limit)) nil-min argseq)
            (.applyTo ^clojure.lang.IFn mapfn args))))

(defn map
  "Map a function onto one or more streams.  Returns a new stream whose limit is the least
  of any of the streams."
  ([mapfn s]
   (if (number? s)
     (mapfn s)
     (reify
       ITypedReduce
       (reduce [this rfn acc]
         (reduce (fn [acc v]
                   (rfn acc (mapfn v)))
                 acc s))
       Limited
       (limit [this] (streams-p/limit s))
       IFnDef$O
       (invoke [this] (mapfn (s))))))
  ([mapfn a b]
   (if (or (number? a) (number? b))
     (cond
       (and (number? a) (number? b))
       (mapfn a b)
       (number? a)
       (map (fn [bb] (mapfn a bb)) b)
       :else
       (map (fn [aa] (mapfn aa b)) a))
     (stream (nil-min (streams-p/limit a) (streams-p/limit b))
             (mapfn (a) (b)))))
  ([mapfn a b c]
   (if (and (number? a) (number? b) (number? c))
     (mapfn a b c)
     (let [a (to-supplier a)
           b (to-supplier b)
           c (to-supplier c)]
       (stream (nil-min (streams-p/limit a)
                        (nil-min (streams-p/limit b)
                                 (streams-p/limit c)))
               (mapfn (a) (b) (c))))))
  ([mapfn a b c & args]
   (map-n mapfn a b c args)))


(defmacro def-double-op
  "Define a unary and binary double from clojure.core or another library such as +.
  Operation must have 1,2,+ arities."
  [op-sym]
  (let [core-sym (if (namespace op-sym)
                   op-sym
                   (symbol (str "clojure.core/" (name op-sym))))
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
         ([~'a ~'b ~'c & ~'args] (map-n ~core-sym ~'a ~'b ~'c ~'args))))))


(def-double-op +)
(def-double-op *)
(def-double-op /)
(def-double-op -)

(defmacro def-binary-op
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
   `(def-binary-op ~op-sym ~(format "Binary operation %s.  Operates in the space of doubles. Arguments
may be streams or double scalars." (name op-sym)))))


(def-binary-op fastmath.core/fpow "Fast pow where right-hand-side is interpreted as integer values.")


(defmacro def-unary-op
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
   `(def-unary-op ~op-sym ~(format "Unary operation %s.  Operates in the space of doubles. Argument may be a streams or a double." (name op-sym)))))


(def-unary-op fastmath.core/log1p)
