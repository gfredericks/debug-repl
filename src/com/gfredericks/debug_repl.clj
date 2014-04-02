(ns com.gfredericks.debug-repl
  (:require [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.middleware.interruptible-eval :refer [*msg*]]
            [clojure.tools.nrepl.middleware.session]
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
  {:state             :idle
   :active-session-id session-id})

(defmulti transition (fn [user-session-data action & args]
                       [(:state user-session-data) action]))

(defmethod transition [:idle :eval-start]
  [user-session-data _action eval-id]
  (assoc user-session-data
    :state :normal-eval
    :active-eval-id eval-id))

(defmethod transition [:normal-eval :eval-done]
  [user-session-data _action eval-id]
  (assert (= eval-id (:active-eval-id user-session-data)))
  (-> user-session-data
      (assoc :state :idle)
      (dissoc :active-eval-id)))

(defmethod transition [:normal-eval :break]
  [user-session-data _action new-session-id eval-fn unbreak-fn]
  (-> user-session-data
      (assoc :state :debug-idle)
      (update-in [:paused-evaluations] conj
                 {:session-id (:active-session-id user-session-data)
                  :eval-id    (:current-eval-id user-session-data)
                  :eval-fn    eval-fn
                  :unbreak-fn unbreak-fn})
      (dissoc :active-eval-id :active-session-id)))

(defmethod transition [:debug-idle :eval-start]
  [user-session-data _action eval-id]
  (assoc user-session-data
    :state :debug-eval
    :active-eval-id eval-id))

(defmethod transition [:normal-eval :unbreak]
  [_ _]
  (throw (Exception. "No debug-repl to unbreak from!")))

(defmethod transition [:debug-eval :unbreak]
  [user-session-data _action]
  (let [{:keys [paused-evaluations]} user-session-data
        {:keys [unbreak-fn session-id eval-id]} (peek paused-evaluations)]
    ;; how do I make sure this future doesn't run until after the
    ;; agent has updated??
    (future (unbreak-fn))
    (-> user-session-data
        (assoc :state :unbreaking)
        (update-in [:paused-evaluations] pop)
        (update-in [:unbreaking-stack] conj
                   {:session-id session-id
                    :eval-id eval-id}))))

(defmethod transition [:unbreaking :eval-done]
  [user-session-data _action]
  (assoc user-session-data
    :state :post-unbreak))

#_(defmethod transition [:post-unbreak :eval-done]
  [user-session-data _action]
  )

(defmethod transition :default
  [{:keys [state]} action & args]
  (if (= :unbreak action)

    (throw (Exception. (format "Bad debug-repl state transition: %s -> %s"
                               (name state)
                               (name action))))))

(defonce
  ^{:doc
    "A map from user-visible nrepl session IDs to a session-data map
    as handled by the above functions."}
  session-datas
  (doto (agent {} :error-handler (fn [e]
                                   (.println System/err "WAT ON EARTH")
                                   (.printStackTrace e)))
    (add-watch ::logger
               (fn [_ _ old new]
                 (let [[session-id :as ids] (->> (keys new)
                                                 (remove #(= (get old %)
                                                             (get new %))))]
                   (assert (= 1 (count ids)))
                   (.println System/out
                             (format "State transition (%s): %s -> %s"
                                     session-id
                                     (get-in old [session-id :state])
                                     (get-in new [session-id :state]))))))))

(defn current-state
  [user-session-id]
  (get-in @session-datas [user-session-id :state]))

(defn active-eval-id
  [user-session-id]
  (get-in @session-datas [user-session-id :active-eval-id]))

(defn transition!
  [user-session-id action & args]
  (.println System/out (pr-str (list 'transition! user-session-id action '...)))
  (let [error-p (promise)]
    (send session-datas
          (fn [m]
            (try (apply update-in m [user-session-id] transition
                                   action
                                   args)
                 (catch Throwable t
                   (deliver error-p t)
                   m))))
    (await session-datas)
    (when (realized? error-p)
      (throw @error-p))))

(defn ensure-registered
  [user-session-id]
  (when-not (contains? @session-datas user-session-id)
    (send session-datas util/assoc-or
          user-session-id
          (init-session-data user-session-id))))

(defn transition-alerter
  "Returns a promise that will be delivered when the user session
  next transitions out of the given state."
  [user-session-id out-state]
  (let [p (promise)
        k (gensym)]
    (add-watch session-datas k
               (fn [_ _ old new]
                 (when (= out-state (:state old))
                   (remove-watch session-datas k)
                   (deliver p nil))))
    p))

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
  (let [{user-session-id ::user-session-id} *msg*
        p (transition-alerter user-session-id :unbreaking)]
    (transition! (::user-session-id *msg*) :unbreak)
    @p
    nil))

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
              (pr-str
               `((-> @session-datas
                     (get ~user-session-id)
                     (:paused-evaluations)
                     (peek)
                     (:eval-fn))
                 ~code))))))

(defn ^:private transform-outgoing
  [msg user-session-id]
  ;; TODO: change the :id for eval results.
  ;; TODO: how do we know when to drop messages?
  (when (and (contains? (:status msg) :done)
             (= (:id msg) (active-eval-id user-session-id)))
    (transition! user-session-id :eval-done (:id msg)))
  (assoc msg :session user-session-id))

(defn ^:private handle-debug
  [handler {:keys [transport op code session] :as msg}]
  {:pre [((some-fn nil? string?) session)]}
  (println "MSG" msg)
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
