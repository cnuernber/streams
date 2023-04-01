(ns streams.protocols)


(defprotocol Limited
  (has-limit? [this]))


(extend-protocol Limited
  Object
  (has-limit? [this] true)
  nil
  (has-limit? [this] true)
  Number
  (has-limit? [this] false))
