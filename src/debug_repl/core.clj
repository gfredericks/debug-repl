(ns debug-repl.core
  (:require [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.middleware.interruptible-eval :refer [*msg*]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.transport :as transport]))

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
  (let [{:keys [transport], session-id ::session-id} *msg*
        p (promise)
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
    (assert (not (contains? @info session-id)))
    (swap! info assoc session-id
           {:promise p
            :eval    (fn [code]
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
      (when-not (realized? p)
        (if-let [[code result-p] (pop-eval-request)]
          (let [code' (format "(fn [{:syms [%s]}]\n%s\n)"
                              (clojure.string/join " " (keys locals))
                              code)]
            (deliver result-p
                     (catchingly
                      ((binding [*ns* ns] (eval (read-string code'))) locals))))
          (Thread/sleep 50))
        (recur)))
    nil))

(defn unbreak!
  []
  (let [{session-id ::session-id} *msg*
        p (get-in @info [session-id :promise])]
    (assert p)
    (deliver p nil)
    (swap! info dissoc session-id)
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
      (.println System/out (str "SENT: " (pr-str msg)))
      (transport/send t msg))))

(defn ^:private wrap-eval
  [{:keys [op code session] :as msg}]
  (cond-> msg
          (and (= "eval" op) (contains? @info session))
          (assoc :code
            (pr-str
             `((get-in @info [~session :eval])
               ~code)))))

(defn handle-debug
  [handler {:keys [transport op code session] :as msg}]
  (.println System/out (str "RECEIVED: " (pr-str msg)))
  (-> msg
      (update-in [:transport] wrap-transport)
      (assoc ::session-id session)
      #_(wrap-eval)
      (handler)))

(defn wrap-debug
  [handler]
  (fn [msg] (handle-debug handler msg)))


(comment
  (take 8 (reverse @sent))
  (last @msgs)
  )
