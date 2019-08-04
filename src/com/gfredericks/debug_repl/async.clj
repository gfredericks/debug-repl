(ns com.gfredericks.debug-repl.async
  (:require
    [com.gfredericks.debug-repl :as debug-repl]
    [com.gfredericks.debug-repl.util :as util])
  (:import (java.util.concurrent
            ArrayBlockingQueue
            TimeUnit)))

(def the-executor nil) ;; global mutable state

(def ^:private msg-var
  (if (util/require? 'nrepl.server)
    (resolve 'nrepl.middleware.interruptible-eval/*msg*)
    (resolve 'clojure.tools.nrepl.middleware.interruptible-eval/*msg*)))

(defn can-break?
  "Implementation detail. Subject to change."
  []
  ;; this just checks if we're in a repl
  (boolean (var-get msg-var)))

(defmacro break!
  "Equivalent to com.gfredericks.debug-repl/break! except that if not run from
  an nREPL session (e.g. from a ring request) then will attempt to connect to
  the connection where wait-for-breaks is being called."
  [& args]
  `(if (can-break?)
     (debug-repl/break! ~@args)
     (if-let [e# the-executor]
       (e# #(debug-repl/break! ~@args))
       :noop)))

(defn wait-for-breaks
  "Wait for a call to break! outside of the nREPL.  Takes an optional timeout
  in seconds to wait which is by default 10 seconds."
  ([] (wait-for-breaks 10 true))
  ([timeout] (wait-for-breaks timeout true))
  ([timeout-seconds wait?]
   (let [queue (java.util.concurrent.SynchronousQueue.)

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

             (if (or (and wait? (do (.put queue f) true))
                     (.offer queue f))
               (let [[x err] @p]
                 (if err (throw err) x))
               :noop)))]

     (with-redefs [the-executor (or the-executor executor)]
       (when (= the-executor executor)
         (loop []
           (if-let [func (.poll queue timeout-seconds TimeUnit/SECONDS)]
             (do
               (func)
               (recur))
             :no-reqs-before-timeout)))))))
