(ns debug-repl.core
  (:require [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.middleware.interruptible-eval :refer [*msg*]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.transport :as transport]))

(defn stdout-println
  "For debugging while developing debug-repl."
  [& obs]
  (.println System/out (apply print-str obs)))

(defmacro locals
  []
  (into {}
        (for [name (keys &env)]
          [(list 'quote name) name])))

(def info
  (atom {}))

;; RESPONSE REFERENCE
;; ({:status #{:done},
;;   :session "1f0f9793-2a52-40a0-a3c0-66e1a4b635e6",
;;   :id "47"}
;;  {:ns "debug-repl.core",
;;   :value "nil",
;;   :session "1f0f9793-2a52-40a0-a3c0-66e1a4b635e6",
;;   :id "47"}
;;  {:status #{:done},
;;   :session "1f0f9793-2a52-40a0-a3c0-66e1a4b635e6",
;;   :id "46"}
;;  {:ns "debug-repl.core",
;;   :value "nil",
;;   :session "1f0f9793-2a52-40a0-a3c0-66e1a4b635e6",
;;   :id "46"})

(defmacro ^:private catchingly
  "Returns either [:returned x] or [:threw t]."
  [& body]
  `(try [:returned (do ~@body)]
        (catch Throwable t#
          [:threw t#])))

(defn ^:private uncatch
  [[type x]]
  (case type :returned x :threw (throw x)))

(defn break
  [locals ns]
  (let [{:keys [transport],
         session-id ::orig-session-id
         nest-session-fn ::nest-session}
        *msg*

        unbreak-p (promise)
        eval-requests (atom clojure.lang.PersistentQueue/EMPTY)
        pop-eval-request (fn []
                           (when-not (empty? @eval-requests)
                             (-> eval-requests
                                 (swap! (fn [q]
                                          (if (empty? q)
                                            q
                                            (vary-meta (pop q) assoc :popped (peek q)))))
                                 (meta)
                                 :popped)))]
    ;; TODO: nesting
    (swap! info update-in [session-id] conj
           {:unbreak           unbreak-p
            :nested-session-id (nest-session-fn)
            :eval              (fn [code]
                                 (stdout-println "EVALING" code)
                                 (let [result-p (promise)]
                                   (swap! eval-requests conj [code result-p])
                                   (uncatch @result-p)))})
    (transport/send transport
                    (response-for *msg*
                                  {:out "HIJACKING REPL!"}))
    (transport/send transport
                    (response-for *msg*
                                  {:status #{:done}}))
    (loop []
      (when-not (realized? unbreak-p)
        (if-let [[code result-p] (pop-eval-request)]
          (let [code' (format "(fn [{:syms [%s]}]\n%s\n)"
                              (clojure.string/join " " (keys locals))
                              code)]
            (deliver result-p
                     (catchingly
                      ((binding [*ns* ns] (eval (read-string code'))) locals))))
          (Thread/sleep 50))
        (recur)))
    (stdout-println "UNBROKEN")
    nil))

(defn unbreak!
  []
  (let [{session-id ::orig-session-id} *msg*
        p (-> @info
              (get session-id)
              (peek)
              (:unbreak))]
    (assert p)
    ;; TODO: dissoc as well? (minor memory leak)
    (swap! info update-in [session-id] pop)
    (deliver p nil)
    nil))

(defmacro break!
  []
  `(break (locals) ~*ns*))

(defonce msgs (atom []))
(defonce sent (atom []))

(defn wrap-transport
  [t]
  (reify transport/Transport
    (recv [this] (transport/recv t))
    (recv [this timeout] (transport/recv t timeout))
    (send [this msg]
      (stdout-println "SENT:" (pr-str msg))
      (transport/send t msg))))

(defn wrap-transport-sub-session
  [t from-session to-session]
  (reify transport/Transport
    (recv [this] (transport/recv t))
    (recv [this timeout] (transport/recv t timeout))
    (send [this msg]
      (let [msg' (cond-> msg (= from-session (:session msg)) (assoc :session to-session))]
        (transport/send t msg')))))

(defn ^:private wrap-eval
  [{:keys [op code session] :as msg}]
  (let [nested-session-id (-> @info
                              (get session)
                              (peek)
                              (:nested-session-id))]
    (cond-> msg
            nested-session-id
            (-> (assoc :session nested-session-id)
                (update-in [:transport] wrap-transport-sub-session nested-session-id session))


            (and nested-session-id (= "eval" op))
            (assoc :code
              (pr-str
               `((-> @info
                     (get ~session)
                     (peek)
                     (:eval))
                 ~code))))))

(defn handle-debug
  [handler {:keys [transport op code session] :as msg}]
  (stdout-println "RECEIVED: " (pr-str msg))
  (-> msg
      (update-in [:transport] wrap-transport)
      (assoc ::orig-session-id session
             ::nest-session (fn []
                              {:post [%]}
                              (let [p (promise)]
                                (handler {:session session,
                                          :op "clone",
                                          :transport (reify transport/Transport
                                                       (send [_ msg]
                                                         (deliver p msg)))})
                                (:new-session @p))))
      (wrap-eval)
      (handler)))

(defn wrap-debug
  [handler]
  ;; having handle-debug as a separate function makes it easier to do
  ;; interactive development on this middleware
  (fn [msg] (handle-debug handler msg)))


(comment
  (take 8 (reverse @sent))
  (last @msgs)
  )
