(ns com.gfredericks.debug-repl.util)

(defmacro catchingly
  "Returns either [:returned x] or [:threw t]."
  [& body]
  `(try [:returned (do ~@body)]
        (catch Throwable t#
          [:threw t#])))

(defn uncatch
  [[type x]]
  (case type :returned x :threw (throw x)))

(defn assoc-or
  [m k v]
  (cond-> m (not (contains? m k)) (assoc k v)))
