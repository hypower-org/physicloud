(ns physicloud-tests.codeparser
  (:require [physicloud-tests.sfn :as s]))

(declare parse-item)

(defn gen-binding-map
  [syms]
  (reduce (fn [val x] (assoc val x x)) {} syms))

(defn gen-let-bindings
  [bindings ctx env]
  
  (loop [binding bindings 
         
         end-bindings [] 
         
         env env]
    
    (let [single-binding (take 2 binding)]
      
      (if (empty? binding)
        
        [end-bindings (merge env (reduce merge (map (fn [x] {(symbol x) x}) (take-nth 2 bindings))))]
        
        (let [fb (first single-binding) 
              
              parsed (parse-item (second single-binding) ctx env)]
          
          (recur (nthrest binding 2) 
                 
                 (conj end-bindings fb parsed) 
                 
                 (assoc env fb parsed)))))))

(defmulti parse-item (fn [form ctx env]
                       (cond
                         (seq? form) :seq
                         (vector? form) :vec
                         (integer? form) :int
                         (= (type form) java.lang.Long) :int
                         (symbol? form) :symbol                        
                         (nil? form) :nil)))

(defmulti parse-expr (fn [[sym & rest] ctx env]
                       sym))

(defmethod parse-expr 'fn*
  [[_ [args & body]] ctx env]
  (let [new-env (merge env (gen-binding-map args))]
   (cons _ (cons (parse-item args ctx new-env) (doall (map (fn [x] (parse-item x ctx new-env)) body))))))

(defmethod parse-expr 'let*
  [[_ bindings & body] ctx env]
  (let [new-bindings (gen-let-bindings bindings ctx env)]
    (cons _ (cons (first new-bindings) (doall (map (fn [x] (parse-item x ctx (second new-bindings))) body))))))

(defmethod parse-expr 'if
  [[_ test then else] ctx env]
  (list _ (parse-item test ctx env) (parse-item then ctx env) (parse-item else ctx env)))

(defmethod parse-expr :default
  [[f & body] ctx env]
  (cons (parse-item f ctx env) (doall (map (fn [x] (parse-item x ctx env)) body))))

(defmethod parse-item :seq
  [form ctx env]
  (let [form (macroexpand form)]
    (parse-expr form ctx env)))

(defmethod parse-item :vec
  [form ctx env]
  (vec (doall (map (fn [x] (parse-item x ctx env)) form))))
  

(defmethod parse-item :int
  [form ctx env]
  form)

(defmethod parse-item :default
  [form ctx env]
  form)

(defmethod parse-item :symbol
  [form ctx env]
  
  (println env)
  
  (let [pos (get env form)]
    
    (if pos
      
      (cond     
        
        (= (type pos) :physicloud-tests.sfn/serializable-fn)
        
        (do
          (parse-item (seq (read-string (pr-str pos))) ctx env))         
        
        (= (type pos) :physicloud-tests.sfn/serializable-atom)
                 
        (parse-item (seq (read-string (pr-str pos))) ctx env)
          
        :default
        pos)
      
      (let [rs (resolve form)]

        (if rs
          (let [v (var-get rs)]
            
            (cond 
              
              (= (type v) :physicloud-tests.sfn/serializable-fn)
              
              (parse-item (seq (read-string (pr-str v))) ctx env)
              
              (= (type v) :physicloud-tests.sfn/serializable-atom)
                 
              (parse-item (seq (read-string (pr-str v))) ctx env)
              
              (or (fn? v) :physicloudtest.agentcore/cyber-physical-unit)
              
              form
              
              :default
              (parse-item v ctx env)))
          
          form)))))

(defmethod parse-item :nil
  [form ctx env]
  nil)

(defmacro expand-all [form]
  `(pr-str (parse-item '~form nil (if-not (empty? '~(keys &env)) (zipmap (into [] '~(keys &env)) (conj [] ~@(keys &env)))))))

(defmacro defn+
  [sym & code]
  `(def ~sym (s/fn ~@code)))

(defn+ b [] (println 2 3))
(defn+ a [] (println 1 3))
;(expand-all (println a (println (b))))

;(read-string (let [b (s/fn [] (println 2 3)) a (s/fn [] (println 1 b)) c (s/atom 3)]
;               (expand-all (println b a (println (b) c)))))

(def a [1 1 1])
(def b [2 2 2])
;(def c [3 3 3])

(defn+ dot-product
  [x y]
  (let [a [(rest a) a] c (rest a)]
    (reduce + (* a c)))
  (if (= 1 2)
    a
    b))

(let [a 1]
  (expand-all dot-product))

(defn+ cloud-agent
  [this]
  (dot-product (:lf this) (:gx this)))

;(def cloud-agent-four (kernel/task :type "time" :update-time 500 :name "cloud-agen-four" :function cloud-agent 
;                             :produces "agent-four-data" :consumes #{}
;                             :init {:x [0 0 0 0 0] :y [0 0 0 0 0] :control [3 3 3 3] :number 3}))


 ;(if-not (empty? '~(keys &env)) (zipmap (into [] '~(keys &env)) (conj [] ~@(keys &env))))





