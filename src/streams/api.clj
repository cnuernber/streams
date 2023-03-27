(ns streams.api
  (:require [ham-fisted.api :as hamf])
  (:import [ham_fisted Transformables ITypedReduce Casts]
           [java.util.function DoubleSupplier DoublePredicate]
           [java.util Random]
           [clojure.lang IDeref IFn$DD IFn$DDD IFn$DDDD IFn$D ISeq])
  (:refer-clojure :exclude [filter map + - * / take]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* true)


(defmacro stream
  [code]
  `(reify
     ITypedReduce
     (reduce [this rfn# acc#]
       (let [rfn# (Transformables/toDoubleReductionFn rfn#)]
         (loop [acc# acc#]
           (if (not (reduced? acc#))
             (recur (rfn# acc# (Casts/doubleCast ~code)))
             (deref acc#)))))
     DoubleSupplier
     (getAsDouble [this#] ~code)
     IDeref
     (deref [this#] ~code)))


(defn flat-stream
  ([^Random r]
   (stream (.nextDouble r)))
  ([]
   (flat-stream (Random.))))


(defn gaussian-stream
  ([^Random r]
   (stream (.nextGaussian r)))
  ([]
   (gaussian-stream (Random.))))


(definterface LimitedConsumer
  (^boolean accept [^double v]))


(deftype DoubleSampler ^:private [^{:unsynchronized-mutable true
                                    :tag long} n
                                  ^long dlen
                                  ^doubles data]
  LimitedConsumer
  (accept [this v]
    (aset-double data n v)
    (let [nn (unchecked-inc n)]
      (set! n nn)
      (== nn dlen)))

  ham_fisted.IFnDef$ODO
  (invokePrim [this acc v]
    (if (.accept ^LimitedConsumer acc v)
      (reduced acc)
      acc))

  (invoke [this] (DoubleSampler. 0 dlen (double-array dlen)))
  (invoke [this acc] (.-data ^DoubleSampler acc)))


(defn sample
  (^doubles [^long n s]
   (if (number? s)
     (let [d (double-array n)]
       (java.util.Arrays/fill d (double s)))
     (hamf/reduce-reducer (DoubleSampler. 0 n nil) s)))
  (^doubles [s]
   (sample 100 s)))

(defn- to-double-supplier
  ^DoubleSupplier [f]
  (cond
    (instance? DoubleSupplier f)
    f
    (number? f)
    (let [f (double f)]
      (reify DoubleSupplier
        (getAsDouble [this] f)))
    (instance? IFn$D f)
    (reify DoubleSupplier
      (getAsDouble [this]
        (.invokePrim ^IFn$D f)))
    :else
    (reify DoubleSupplier
      (getAsDouble [this]
        (Casts/doubleCast ^IFn$D f)))))


(defn argtype
  [s]
  (if (number? s) :scalar :stream))


(defn take
  [^long n s]
  (let [s (to-double-supplier s)]
    (reify
      ITypedReduce
      (reduce [this rfn acc]
        (let [rfn (Transformables/toDoubleReductionFn rfn)]
          (loop [idx 0
                 acc acc]
            (if (and (< idx n)
                     (not (reduced? acc)))
              (recur (unchecked-inc idx)
                     (.invokePrim rfn acc (.getAsDouble s)))
              (if (reduced? acc)
                (deref acc)
                acc)))))
      DoubleSupplier
      (getAsDouble [this]
        (.getAsDouble s)))))


(defn filter
  [pred s]
  (let [^DoublePredicate pred (if (instance? DoublePredicate pred)
                                pred
                                (reify DoublePredicate
                                  (test [this v] (boolean (pred v)))))]
    (reify
      DoubleSupplier
      (getAsDouble [this]
        (let [s (to-double-supplier s)]
          (loop []
            (let [v (.getAsDouble s)]
              (if (.test pred v)
                v
                (recur))))))
      IDeref
      (deref [this] (.getAsDouble this))
      ITypedReduce
      (reduce [this rfn acc]
        (let [rfn (Transformables/toDoubleReductionFn rfn)]
          (reduce
           (reify ham_fisted.IFnDef$ODO
             (invokePrim [this acc v]
               (if (.test pred v)
                 (.invokePrim rfn acc v)
                 acc)))
           acc
           s))))))


(defn- to-double-fn-1
  ^IFn$DD [mapfn]
  (if (instance? IFn$DD mapfn)
    mapfn
    (reify ham_fisted.IFnDef$DD
      (invokePrim [this v] (Casts/doubleCast (mapfn v))))))


(defn- to-double-fn-2
  ^IFn$DDD [mapfn]
  (if (instance? IFn$DDD mapfn)
    mapfn
    (reify ham_fisted.IFnDef$DDD
      (invokePrim [this a b] (Casts/doubleCast (mapfn a b))))))


(defn- to-double-fn-3
  ^IFn$DDDD [mapfn]
  (if (instance? IFn$DDDD mapfn)
    mapfn
    (fn ^double [^double a ^double b ^double c]
      (Casts/doubleCast (mapfn a b c)))))


(defn- supplier-value-seq
  ^ISeq [supplier-vec]
  (let [^ISeq sv (seq supplier-vec)]
    (reify ISeq
      (first [this] (.getAsDouble ^DoubleSupplier (.first sv)))
      (next [this] (when-let [nn (.next sv)]
                     (supplier-value-seq nn)))
      (more [this] (when-let [nn (.more sv)]
                     (supplier-value-seq nn)))
      (seq [this] this)
      (cons [this o]
        (supplier-value-seq (cons o supplier-vec))))))


(defn- map-n
  [map-fn a b c args]
  (let [sargs (-> (into [] (comp cat (clojure.core/map to-double-supplier))
                        [[a b c] args])
                  (supplier-value-seq))]
    (stream (.applyTo ^clojure.lang.IFn map-fn sargs))))


(defn map
  ([mapfn s]
   (if (number? s)
     (mapfn s)
     (let [mapfn (to-double-fn-1 mapfn)
           s (to-double-supplier s)]
       (stream (.invokePrim mapfn (.getAsDouble s))))))
  ([mapfn a b]
   (if (or (number? a) (number? b))
     (cond
       (and (number? a) (number? b))
       (mapfn a b)
       (number? a)
       (map (let [mapfn (to-double-fn-2 mapfn)
                  a (double a)]
              (reify ham_fisted.IFnDef$DD
                (invokePrim [this bb]
                  (.invokePrim mapfn a bb))))
            b)
       :else ;;b is a number
       (map (let [mapfn (to-double-fn-2 mapfn)
                  b (double b)]
              (reify ham_fisted.IFnDef$DD
                (invokePrim [this aa]
                  (.invokePrim mapfn aa b))))
            a))
     (let [^IFn$DDD mapfn (to-double-fn-2 mapfn)
           a (to-double-supplier a)
           b (to-double-supplier b)]
       (stream (.invokePrim mapfn (.getAsDouble a) (.getAsDouble b))))))
  ([mapfn a b c]
   (let [^IFn$DDDD mapfn (to-double-fn-3 mapfn)
         a (to-double-supplier a)
         b (to-double-supplier b)
         c (to-double-supplier c)]
     (stream (.invokePrim mapfn (.getAsDouble a) (.getAsDouble b) (.getAsDouble c)))))
  ([mapfn a b c & args]
   (map-n mapfn a b c args)))



(defmacro def-double-op
  [op-sym]
  (let [core-sym (symbol (str "clojure.core/" (name op-sym)))]
    ;;typehinting these to produce the ideal functions signatures
    `(let [un-arg# (fn ^double [^double v#] (~core-sym v#))
           bi-arg# (fn ^double [^double a# ^double b#] (~core-sym a# b#))
           tri-arg# (fn ^double [^double a# ^double b# ^double c#] (~core-sym a# b# c#))]
       (defn ~op-sym
         ([~'a] (map un-arg# ~'a))
         ([~'a ~'b] (map bi-arg# ~'a ~'b))
         ([~'a ~'b ~'c] (map tri-arg# ~'a ~'b ~'c))
         ([~'a ~'b ~'c & ~'args] (map-n ~core-sym ~'a ~'b ~'c ~'args))))))


(def-double-op +)
(def-double-op *)
(def-double-op /)
(def-double-op -)
