(ns streams.graphs
  "Simple graphing utility.  Relies on darkstar, loaded dynamically, to produce svg graphs.


  Recommended Dependencies-
```clojure
                applied-science/darkstar
                {:git/url \"https://github.com/appliedsciencestudio/darkstar/\"
                 :sha \"abd480cc382b7ae143f7902ee9d300cdc1a705cc\"
                 :exclusions [org.graalvm.js/js org.graalvm.js/js-scriptengine]}
                org.graalvm.js/js {:mvn/version \"20.3.2\"}
                org.graalvm.js/js-scriptengine {:mvn/version \"20.3.2\"}}
```

  Example Usage:

```clojure
user> (-> (streams/interleave (streams/gaussian-stream)
                              (streams/+ (streams/gaussian-stream) 10))
          (graphs/stream-area-chart {:title :dual-lobe-gaussian
                                     :width 400 :height 100
                                     :n-bins 100})
          (graphs/spit-chart-svg-to-file \"docs/dual-lobe-gaussian.svg\"))
nil
```
  "
  (:require [kixi.stats.core :as kixi]
            [streams.api :as streams]
            [ham-fisted.api :as hamf]
            [charred.api :as charred]))



(defn stream-area-chart
  "Create an area chart of the histogram of the stream."
  [s {:keys [title width height y-axis-name x-axis-name]
      :or {title "" width 800 height 600 y-axis-name :sample-count
           x-axis-name :value}
      :as opts}]
  (let [data (streams/sample 100000 s)
        {:keys [min max] :as info} (hamf/reduce-reducer
                                    (hamf/compose-reducers
                                     {:min kixi/min
                                      :max kixi/max})
                                    data)
        min (double min)
        max (double max)
        n-bins (or (:n-bins opts)
                   (long (clojure.core/min 100 (clojure.core/max 10 (/ (alength data) 100)))))
        binsize (/ (+ 1.0 (- max min)) n-bins)
        freqs (hamf/frequencies (streams/map (fn ^long [^double d]
                                               (long (/ (- d min) binsize)))
                                             data))
        chart-data (->> freqs
                        ;;type-hinting the sort-by method allows us to use faster indirect
                        ;;sorting provided by fastutil
                        (hamf/sort-by (fn ^long [kv] (long (key kv))))
                        (mapv (fn [kv]
                                {x-axis-name (+ min (* binsize (long (key kv))))
                                 y-axis-name (val kv)})))]
    {:$schema "https://vega.github.io/schema/vega-lite/v5.1.0.json"
     :mark {"type" "area" "line" true}
     :title title
     :width width
     :height height
     :data {:values chart-data}
     :encoding
     {:y {:field y-axis-name :type :quantitative :axis {:grid false}}
      :x {:field x-axis-name :type :quantitative :axis {:grid false}}}}))


(defn spit-chart-svg-to-file
  ([chart fname]
   (let [chart->svg (requiring-resolve 'applied-science.darkstar/vega-lite-spec->svg)]
     (spit fname (-> chart
               (charred/write-json-str)
               (chart->svg)))))
  ([chart]
   (spit-chart-svg-to-file chart (str (or (name (:title chart)) "chart") ".svg"))))


(comment
  ;;dual hump distribution
  (-> (streams/interleave (streams/gaussian-stream)
                          (streams/+ (streams/gaussian-stream) 10))
      (stream-area-chart {:title :dual-lobe-gaussian
                          :width 400 :height 100
                          :n-bins 100})
      (spit-chart-svg-to-file "docs/dual-lobe-gaussian.svg"))


  (-> (streams/fastmath-stream :exponential)
      (stream-area-chart {:title :exponential
                                 :width 400 :height 100
                                 :n-bins 100})
      (spit-chart-svg-to-file "docs/exponential.svg"))
nil
  )
