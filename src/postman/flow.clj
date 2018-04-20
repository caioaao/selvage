(ns postman.flow
  (:require [postman.visibility :as vis]
            [postman.formatting :as formatting]
            [midje.emission.api :as emission.api]
            [midje.emission.state :as emission.state]
            [midje.repl :refer [last-fact-checked]]
            [midje.sweet :refer [fact facts tabular truthy]]
            [schema.core :as s]
            [taoensso.timbre :as timbre])
  (:import [clojure.lang ISeq Symbol]
           [java.io ByteArrayOutputStream PrintStream StringWriter]))

(def ^:dynamic *probe-timeout* 300)
(def ^:dynamic *probe-sleep-period* 10)
(def ^:dynamic *verbose* false)
(def ^:dynamic *world* {})
(def ^:dynamic *flow* {})

(def worlds-atom (atom {}))

(defn stdout-emit [& strings]
  (when (emission.api/config-above? :print-nothing)
    (apply print strings)
    (flush)))

(defn emit-ln [message log-map]
  (timbre/info :log-map log-map)
  (stdout-emit (format "%-70s\t\t\t[CID: %s]\n" message (vis/current-cid))))

(defn emit-debug-ln [message log-map]
  (when *verbose*
    (emit-ln message log-map)))

(defn save-world-debug! [name world]
  (swap! worlds-atom assoc name world)
  world)

(defn worlds [] (deref worlds-atom))

(def Expression s/Any)
(def Step [(s/one (s/enum :transition :retry) 'kind)
           (s/one Expression 'expression)
           (s/one s/Str 'description)])

(defn- fact-desc [fact-form]
  (if (string? (second fact-form))
    (second fact-form)
    (str fact-form)))

(defn retriable-step? [[kind _f _desc]]
  (-> kind #{:check :query} boolean))

(defn resetting-midje-counters [f]
  (let [output-counters-before (emission.state/output-counters)]
    (fn [& args]
      (emission.state/set-output-counters! output-counters-before)
      (apply f args))))

(defn timed-apply [run-function & args]
  (let [start   (System/nanoTime)
        ret     (apply run-function args)
        elapsed (/ (double (- (System/nanoTime) start)) 1000000.0)]
    [elapsed ret]))

(defn run-step [[world _] [step-type f desc]]
  (vis/with-split-cid
    (do
      (emit-debug-ln (str "Running " (format "%-10s" (name step-type)) " " desc)
                     {:log       :flow/run-step
                      :step-type step-type
                      :step-desc desc})
      (let [[next-world result-desc] (f world)]
        (save-world-debug! desc next-world)
        (if next-world
          [next-world result-desc]
          (reduced [next-world result-desc]))))))

(defn run-step-sequence [s0 steps]
  (reduce run-step s0 steps))

(defn run-steps [steps]
  (reset! worlds-atom {})
  (run-step-sequence [{} ""] steps))

(defn steps-to-step [steps]
  `[:sequence (fn [w#] (run-step-sequence [w# ""] (list ~@steps))) "running multiple steps"])

(defn retry [f]
  (letfn [(retry? [elapsed-millis] (<= elapsed-millis *probe-timeout*))
          (retry-f [elapsed-so-far f w]
            (let [[time [success? desc :as res]] (timed-apply f w)
                  elapsed                        (+ elapsed-so-far time)]
              (if success?
                res
                (if (retry? elapsed)
                  (do
                    (Thread/sleep *probe-sleep-period*)
                    ;; time accounting might be improved
                    (retry-f (+ elapsed *probe-sleep-period*) f w))
                  [false desc]))))]
    (partial retry-f 0 (resetting-midje-counters f))))

(defn retry-expr [[_kind f-expr desc]]
  `[:retry (fn [w#] ((retry ~f-expr) w#)) ~desc])

(defn- partition-group-by [pred coll]
  (->> coll (partition-by pred) (map #(vector (pred (first %)) %))))

(defn retry-sequences [steps]
  (->> steps
       (partition-group-by retriable-step?)
       (mapcat (fn [[retriable-seq? steps]]
                 (if retriable-seq?
                   [(retry-expr (steps-to-step steps))]
                   steps)))))

(defn check->fn-expr [check-expr]
  `(fn [world#]
     (let [writer# (new StringWriter)]
       (binding [*world*                 world#
                 clojure.test/*test-out* writer#]
         (let [result#  ~check-expr
               success# (when result#
                          world#)]
           [success# (str writer#)])))))

(defn future->fn-expr [form]
  `(fn [world#]
     ~form ; This shows 'WORK TO DO...' message on output
     [world# nil]))

(defn print-exception-string [exception]
  (let [output-baos (ByteArrayOutputStream.)]
    (.printStackTrace exception (PrintStream. output-baos))
    (String. (.toByteArray output-baos) "UTF-8")))

(defn fail [expr-str details & failure-messages]
  (emission.state/output-counters:inc:midje-failures!)
  [false (apply str "\033[0;33m  Step " expr-str " " details " \033[0m " failure-messages)])

(defn valid-world-result [world expr-str]
  (if (map? world)
    [world ""]
    (fail expr-str "did not result in a map (i.e. a valid world):\n" world)))

(defn- format-expr [expr]
  (let [line-info (some-> (:line (meta expr)) (#(str " (at line: " % ")")))]
    (str "'" expr "'" line-info)))

(defn transition->fn-expr [transition-expr]
  `(fn [world#]
     (try
       (valid-world-result (~transition-expr world#) ~(str transition-expr))
       (catch Throwable throwable#
         (timbre/error throwable# :log :transition-exception)
         (fail ~(format-expr transition-expr) "threw exception:\n"
                (formatting/format-exception throwable#))))))

(defmulti form->var class)

(defmethod form->var Symbol [s]
  (resolve s))

(defmethod form->var ISeq [l]
  (let [[fst snd] l]
    (cond
      (#{'partial 'comp} fst) (form->var snd)
      :else                   (form->var fst))))

(defmethod form->var :default [_]
  nil)

(defn- is-check? [form]
  (and (coll? form)
       (-> form first name #{"fact" "facts"})))
(defn- is-future? [form]
  (and (coll? form)
       (-> form first name #{"future-fact" "future-facts"})))
(defn- is-query? [form]
  (-> form form->var meta ::query))

(defn- classify [form]
  (cond (is-check? form)  [:check (check->fn-expr form) (fact-desc form)]
        (is-future? form) [:check (future->fn-expr form) (fact-desc form)]
        (is-query? form)  [:query (transition->fn-expr form) (str form)]
        :else             [:transition (transition->fn-expr form) (str form)]))

(s/defn forms->steps :- [Step] [forms :- [Expression]]
  (->> forms (map classify) retry-sequences seq))

(defn announce-flow [flow-description]
  (emit-debug-ln (str "Running flow: " flow-description)
                 {:flow-description flow-description
                  :log              :flow/start}))

(defn announce-results [flow-description [success? desc]]
  (when-not success?
    (stdout-emit desc))
  (emit-debug-ln (str "Flow " flow-description " finished"
                   (if success?
                     " successfully"
                     " with failures") "\n") {:flow-description flow-description
                                              :log              :flow/finish
                                              :success?         (boolean success?)})
  (boolean success?))

(defn wrap-with-metadata [flow-name flow-expr]
  `(s/with-fn-validation
     (facts :postman ~flow-name
       ~flow-expr)))

(defn update-metadata-w-cid! []
  (-> (last-fact-checked)
      (vary-meta assoc :flow/cid (vis/current-cid))
      ;; HACK: re-record fact so the meta with CID is saved
      (midje.data.compendium/record-fact-check!)))

(defmacro with-cid [& body]
  `(vis/with-split-cid "FLOW"
     (let [result# (do ~@body)]
       (update-metadata-w-cid!)
       result#)))

(defn get-flow-information
  [forms metadata]
  (let [flow-ns               (ns-name *ns*)
        flow-name             (str flow-ns ":" (:line metadata))
        [flow-title in-forms] (if (string? (first forms))
                                [(first forms) (rest forms)]
                                [nil forms])
        flow-description      (if flow-title (str flow-name " " flow-title) flow-name)]
    {:flow-description flow-description
     :flow-ns          flow-ns
     :flow-name        flow-name
     :flow-title       flow-title
     :in-forms         in-forms}))

(defmacro flow [& forms]
  (let [{:keys [flow-name
                flow-title
                in-forms
                flow-description]} (get-flow-information forms (meta &form))]
    (wrap-with-metadata flow-description
                        `(binding [*flow* {:name  ~flow-name
                                           :title ~flow-title}]
                           (with-cid
                             (announce-flow ~flow-description)
                             (->> (list ~@(forms->steps in-forms))
                                  run-steps
                                  (announce-results ~flow-description)))))))

(defmacro ^::query fnq [& forms]
  `(fn ~@forms))

(defmacro defnq [name & forms]
  `(def ~(with-meta name {::query true}) (fn ~@forms)))

(defmacro tabular-flow [flow & table]
  `(tabular
     (fact ~flow => truthy)
     ~@table))