(ns streams.protocols)


(defprotocol Limited
  (limit [n]))

(extend-protocol Limited
  nil (limit [n] nil)
  Object (limit [n] nil))
