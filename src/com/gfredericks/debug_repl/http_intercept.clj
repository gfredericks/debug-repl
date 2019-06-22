(ns com.gfredericks.debug-repl.http-intercept
  (:require
    [com.gfredericks.debug-repl :as debug-repl]
    [com.gfredericks.debug-repl.util :as util])
  (:import (java.util.concurrent
            ArrayBlockingQueue
            TimeUnit)))

(def the-executor nil) ;; global mutable state

(defn can-break?
  []
  ;; this just checks if we're in a repl
  (boolean
    (if (util/require? 'nrepl.server)
      @(resolve 'nrepl.middleware.interruptible-eval/*msg*)
      @(resolve 'clojure.tools.nrepl.middleware.interruptible-eval/*msg*))))

(defmacro break!
  [& args]
  `(if (can-break?)
     (debug-repl/break! ~@args)
     (if-let [e# the-executor]
       (e# #(debug-repl/break! ~@args))
       :noop)))

(defn wait-for-breaks
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
            (let [[x err] @p]
              (if err (throw err) x))))]

    (alter-var-root #'the-executor #(or % executor))
    (when (= the-executor executor)
      (try
        (loop []
          (if-let [func (.poll queue 10 TimeUnit/SECONDS)]
            (do
              (func)
              (recur))
            :no-reqs-for-10-seconds))
        (finally
          (alter-var-root #'the-executor (constantly nil)))))))
