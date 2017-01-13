(ns com.gfredericks.debug-repl.http-intercept
  (:import (java.util.concurrent
            ArrayBlockingQueue
            TimeUnit)))

(def the-executor nil) ;; global mutable state

(defn wrap-http-intercept
  [handler]
  (fn [req]
    (if-let [executor the-executor]
      (executor #(handler req))
      (handler req))))

(defn can-break?
  []
  ;; this just checks if we're in a repl
  (boolean clojure.tools.nrepl.middleware.interruptible-eval/*msg*))

(defn intercept-http!
  []
  ;; if the repl is busy executing a request, should concurrent
  ;; requests block or skip the repl execution altogether?
  ;;
  ;; going to try blocking first
  (let [queue (java.util.concurrent.ArrayBlockingQueue. 16)

        executor
        (fn [func]
          (let [p (promise)
                f (bound-fn
                    []
                    (deliver p
                             (try
                               [(func)]
                               (catch Throwable t
                                 [nil t]))))]
            (.put queue f)
            (let [[x err] @p] (or x (throw err)))))]

    (alter-var-root #'the-executor #(or % executor))
    (when (= the-executor executor)
      (try
        (while true
          (when-let [func (.poll queue 1 TimeUnit/SECONDS)]
            (func)))
        (finally
          (alter-var-root #'the-executor (constantly nil)))))))
