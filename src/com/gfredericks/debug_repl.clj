(ns com.gfredericks.debug-repl
  (:require [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.middleware.interruptible-eval :refer [*msg*]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.transport :as transport]
            [com.gfredericks.debug-repl.util :as util])
  (:import (java.util.concurrent ArrayBlockingQueue)))

;; TODO:
;;   - Close nrepl sessions after unbreak!
;;   - Report the correct ns so the repl switches back & forth?
;;   - Avoid reporting :done multiple times
;;   - Suppress the return value from (unbreak!)? this would avoid
;;     the command returning two results...
;;   - Detect when (break!) is called but the middleware is missing?
;;     And give a helpful error message.
;;   - Better reporting on how many nested repls there are, etc


;;
;; State Machine Code
;;

(defn init-session-data
  [session-id]
  {:state             :normal
   :active-session-id session-id})

(defmulti transition (fn [user-session-data action & args]
                       [(:state user-session-data) action]))

(defmethod transition [:normal :break]
  [user-session-data _action new-session-id eval-fn unbreak-fn]
  (assoc user-session-data
    ;; do we need an in-between state while we're sending the :done?
    :state :debug-repl
    :sub-sessions (list {:id         new-session-id
                         :eval-fn    eval-fn
                         :unbreak-fn unbreak-fn})))

(defmethod transition [:debug-repl :unbreak]
  [user-session-data _action]
  (let [{:keys [sub-sessions]} user-session-data
        {:keys [unbreak-fn]} (peek sub-sessions)]
    (unbreak-fn)
    (assoc user-session-data
      :state :unbreaking
      ;; do we want to pop now or on the next transition?
      :sub-sessions (pop sub-sessions))))

(defmethod transition :default
  [{:keys [state]} action]
  (if (= :unbreak action)
    (throw (Exception. "No debug-repl to unbreak from!"))
    (throw (Exception. (format "Bad debug-repl state transition: %s -> %s"
                               (name state)
                               (name action))))))

(defonce
  ^{:doc
    "A map from user-visible nrepl session IDs to a session-data map
    as handled by the above functions."}
  session-datas
  (agent {}))

(defn transition!
  [user-session-id action & args]
  (let [error-p (promise)]
    (send session-datas
          (fn [m]
            (try (apply update-in m [user-session-id] transition
                        action
                        args)
                 (catch Throwable t
                   (deliver error-p t)))))
    (await session-datas)
    (when (realized? error-p)
      (throw @error-p))))

(defn ensure-registered
  [user-session-id]
  (when-not (contains? @session-datas user-session-id)
    (send session-datas util/assoc-or
          user-session-id
          (init-session-data user-session-id))))

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
    (transition! user-session-id :break
                 (nest-session-fn)
                 (fn [code]
                   (let [result-p (promise)]
                     (.put eval-requests [code result-p])
                     (util/uncatch @result-p)))
                 (fn [] (deliver unbreak-p nil)))
    (transport/send transport
                    (response-for *msg*
                                  {:out (str "Hijacking repl for breakpoint: "
                                             breakpoint-name)}))
    (transport/send transport
                    (response-for *msg*
                                  {:status #{:done}}))
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
  (transition! (::user-session-id *msg*) :unbreak))

(defn ^:private transform-incoming
  [msg user-session-id]
  (let [{:keys [state sub-sessions]} (@session-datas user-session-id)
        {:keys [id]} (peek sub-sessions)
        {:keys [op code]} msg]
    (cond-> msg

            id
            (assoc :session id)

            (and (= state :debug-repl) (= op "eval"))
            (assoc :code
              (pr-str
               `((-> @session-datas
                     (get ~user-session-id)
                     (:sub-sessions)
                     (peek)
                     (:eval-fn))
                 ~code))))))

(defn ^:private transform-outgoing
  [msg user-session-id]
  ;; TODO: change the :id for eval results.
  (assoc msg :session user-session-id))

(defn ^:private handle-debug
  [handler {:keys [transport op code session] :as msg}]
  (ensure-registered session)
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
                            (let [msg' (transform-outgoing msg session)]
                              (transport/send transport msg')))))
      (transform-incoming session)
      (handler)))

(defn wrap-debug-repl
  [handler]
  ;; having handle-debug as a separate function makes it easier to do
  ;; interactive development on this middleware
  (fn [msg] (handle-debug handler msg)))

(set-descriptor! #'wrap-debug-repl
                 {:expects #{"clone" "eval" #'clojure.tools.nrepl.middleware.session/session}})
