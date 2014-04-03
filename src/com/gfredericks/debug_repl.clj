(ns com.gfredericks.debug-repl
  (:require [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.middleware.interruptible-eval :refer [*msg*]]
            [clojure.tools.nrepl.middleware.session]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.transport :as transport]
            [com.gfredericks.debug-repl.util :as util])
  (:import (java.util.concurrent ArrayBlockingQueue
                                 Executors
                                 ExecutorService)))

;; TODO:
;;   - Close nrepl sessions after unbreak!
;;   - Report the correct ns so the repl switches back & forth?
;;   - Avoid reporting :done multiple times
;;   - Suppress the return value from (unbreak!)? this would avoid
;;     the command returning two results...
;;   - Detect when (break!) is called but the middleware is missing?
;;     And give a helpful error message.
;;   - Better reporting on how many nested repls there are, etc


(defonce ^ExecutorService pool (Executors/newCachedThreadPool))

(def ^:dynamic *break-handler* nil)

(defn run-job
  [fn]
  (let [f (.submit pool ^Callable
                   (fn []
                     (binding [*break-handler* [(Thread/currentThread) (promise)]])))]
    ()))

(defn register-orphaned-break
  [eval-fn unbreak-fn]
  (throw (Error. "Can't do that yet.")))

(defn register-break
  [eval-fn unbreak-fn]
  (if-let [[t p] *break-handler*]
    (if (= t (Thread/currentThread))
      (deliver p {:eval-fn eval-fn :unbreak-fn unbreak-fn})
      (register-orphaned-break eval-fn unbreak-fn))
    (register-orphaned-break eval-fn unbreak-fn)))

;;
;; Normal Code
;;

(defmacro current-locals
  "Returns a map from symbols of locals in the lexical scope to their
  values."
  []
  (into {}
        (for [name (keys &env)]
          [(list 'quote name) name])))


(defn break
  [locals breakpoint-name ns]
  (let [{:keys [transport],
         user-session-id ::user-session-id
         nest-session-fn ::nest-session-fn}
        *msg*

        unbreak-p (promise)
        ;; probably never need more than 1 here
        eval-requests (ArrayBlockingQueue. 2)]
    (register-break (fn [code]
                      (let [result-p (promise)]
                        (.put eval-requests [code result-p])
                        (util/uncatch @result-p)))
                    (fn [] (deliver unbreak-p nil)))
    (loop []
      (when-not (realized? unbreak-p)
        (if-let [[code result-p] (.poll eval-requests)]
          (let [code' (format "(fn [{:syms [%s]}]\n%s\n)"
                              (clojure.string/join " " (keys locals))
                              code)]
            (deliver result-p
                     (util/catchingly
                      ((binding [*ns* ns] (eval (read-string code'))) locals))))
          (Thread/sleep 50))
        (recur)))
    nil))

(defmacro break!
  "Use only with the com.gfredericks.debug-repl/wrap-debug-repl middleware.

  Causes execution to stop and the repl switches to evaluating code in the
  context of the breakpoint. Resume exeution by calling (unbreak!). REPL
  code can result in a nested call to break! which will work in a reasonable
  way. Nested breaks require multiple calls to (unbreak!) to undo."
  ([]
     `(break! "unnamed"))
  ([breakpoint-name]
     `(break (current-locals)
             ~breakpoint-name
             ~*ns*)))

(defn unbreak!
  "Causes the latest breakpoint to resume execution; the repl returns to the
  state it was in prior to the breakpoint."
  []
  (let [{user-session-id ::user-session-id} *msg*
        p (promise)]
    (transition! (::user-session-id *msg*) :unbreak #(deliver p nil))
    @p))

(defn ^:private transform-incoming
  [msg user-session-id]
  (let [{:keys [state sub-sessions]} (@session-datas user-session-id)
        {nested-session-id :id} (peek sub-sessions)
        {:keys [op id code]} msg]
    (when (= op "eval")
      (transition! user-session-id :eval-start id))
    (cond-> msg

            nested-session-id
            (assoc :session nested-session-id)

            (and (= state :debug-repl) (= op "eval"))
            (assoc :code
              (format "(com.gfredericks.debug-repl/run-job (bound-fn []\n%s\n))"
                      code)))))

(defn ^:private transform-outgoing
  [msg user-session-id]
  ;; TODO: change the :id for eval results.
  ;; TODO: how do we know when to drop messages?
  ;; TODO: I think :session is a map of vars at this point!??
  (let [msg (assoc msg :session user-session-id)]
    (cond
     (and (contains? (:status msg) :done)
          (= (:id msg) (active-eval-id user-session-id)))
     (let [{:keys [state]}
           (transition! user-session-id :eval-done)]
       (when (#{:normal-idle :debug-idle} state)
         msg))

     (contains? msg :value)
     (assoc msg :id (active-eval-id user-session-id))

     :else
     msg)))

(defn ^:private handle-debug
  [handler {:keys [transport op code session] :as msg}]
  {:pre [((some-fn nil? string?) session)]}
  (when session (ensure-registered session))
  (-> msg
      (assoc ::user-session-id session
             ::nest-session-fn
             (fn []
               {:post [%]}
               (let [p (promise)]
                 (handler {:session session
                           :op "clone"
                           :transport (reify transport/Transport
                                        (send [_ msg]
                                          (deliver p msg)))})
                 (:new-session @p))))
      (assoc :transport (reify transport/Transport
                          (send [_ msg]
                            (when-let [msg' (transform-outgoing msg session)]
                              #_(println "OUT" (keys msg'))
                              (transport/send transport msg')))))
      (transform-incoming session)
      (some-> (handler))))

(defn wrap-debug-repl
  [handler]
  ;; having handle-debug as a separate function makes it easier to do
  ;; interactive development on this middleware
  (fn [msg] (handle-debug handler msg)))

(set-descriptor! #'wrap-debug-repl
                 {:expects #{"clone" "eval" #'clojure.tools.nrepl.middleware.session/session}})
