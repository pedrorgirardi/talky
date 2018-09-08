(ns talky.document
  (:require
   [cljs.reader :as reader])
  (:refer-clojure :exclude [ns-name]))


(defn ns-name [^js document]
  (-> (reader/read-string (.getText document)) second name))
