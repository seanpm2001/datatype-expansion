(ns datatype-expansion.expanded-form
  #?(:cljs (:require-macros [datatype-expansion.utils-macros :refer [maybe]]))
  (:require #?(:clj [datatype-expansion.utils :refer [error]])
            #?(:clj [datatype-expansion.utils-macros-clj :refer [maybe]])
            [clojure.string]
            [datatype-expansion.utils :refer [clear-node error]]
            [instaparse.core :as insta]
            [clojure.walk :refer [stringify-keys]]))

#?(:cljs (enable-console-print!))

(def raml-grammar "TYPE_EXPRESSION = TYPE_NAME | SCALAR_TYPE | <'('> <BS>  TYPE_EXPRESSION <BS> <')'> | ARRAY_TYPE | UNION_TYPE
                   SCALAR_TYPE = 'string' | 'number' | 'integer' | 'boolean' | 'date-only' | 'time-only' | 'datetime-only' | 'datetime' | 'file' | 'nil'
                   ARRAY_TYPE = TYPE_EXPRESSION <'[]'>
                   TYPE_NAME = #\"(\\w[\\w\\d]+\\.)*\\w[\\w\\d]+\"
                   UNION_TYPE = TYPE_EXPRESSION <BS> (<'|'> <BS> TYPE_EXPRESSION)+
                   BS = #\"\\s*\"
                   ")

(def raml-type-grammar-analyser (insta/parser raml-grammar))

(defn ast->type [ast context]
  (let [type (filterv #(not= % :TYPE_EXPRESSION) ast)]
    (if (and (= 1 (count type))
             (vector? (first type)))
      (recur (first type) context)
      (condp = (first type)
        :UNION_TYPE {:type "union"
                     :anyOf (mapv #(ast->type % context) (rest type))}
        :SCALAR_TYPE {:type (last type)}
        :ARRAY_TYPE {:type "array"
                     :items (ast->type (last type) context)}
        :TYPE_NAME (last type)

        (error (str "Cannot parse type expression AST " (mapv identity type)))))))

(defn parse-type-expression [exp context]
  (try
    (ast->type (raml-type-grammar-analyser exp) context)
    (catch #?(:clj Exception :cljs js/Error) ex
      ;;(println (str "Cannot parse type expression '" exp "': " ex))
      nil)))


(def atomic-types #{"any" "boolean" "datetime" "datetime-only" "date-only" "time-only"
                    "number" "integer" "string" "nil" "file"})

(defn- process-constraints [parsed-type type-node]
  (-> parsed-type
      (assoc :required (:required type-node true))
      (assoc :example (:example type-node))
      (assoc :default (:default type-node))
      (assoc :examples (:examples type-node))
      (assoc :title (:title type-node))
      (assoc :minProperties (:minProperties type-node))
      (assoc :maxProperties (:maxProperties type-node))
      (assoc :discriminator (:discriminator type-node))
      (assoc :discriminatorValue (:discriminatorValue type-node))
      (assoc :minLength (:minLength type-node))
      (assoc :maxLength (:maxLength type-node))
      (assoc :minimum (:minimum type-node))
      (assoc :maximum (:maximum type-node))
      (assoc :format (:format type-node))
      (assoc :multipleOf (:multipleOf type-node))
      (assoc :pattern  (:pattern type-node))
      (assoc :uniqueItems (:uniqueItems type-node))
      (assoc :minItems (:minItems type-node))
      (assoc :maxItems (:maxItems type-node))
      (assoc :enum (let [enum-values (into [] (:enum type-node))]
                           (if (empty? enum-values) nil enum-values)))
      (assoc :additionalProperties (:additionalProperties type-node (if (or (= "object" (:type parsed-type))
                                                                            (some? (:properties parsed-type)))
                                                                      true
                                                                      nil)))))

(defn- xml-type? [type] (and (string? type) (clojure.string/starts-with? type "<?xml")))

(defn- json-type? [type] (and (string? type) (clojure.string/starts-with? type "{")))

(defn check-required [{:keys [properties] :as object}]
  (->> (or properties [])
       (map (fn [[k v]]
              [k (assoc v :required (get v :required true))]))
       (into {})
       (assoc object :properties)))

(defn setup-context [{:keys [path] :as context}]
  (assoc context :path [] :fixpoints (atom {})))

(defn cycle? [type path]
  (-> (filter (fn [type-in-path]
                (= type-in-path type)) path)
      first
      some?))

(declare expanded-form-inner)

(defn process-items [node context]
  (if (some? (:items node))
    (do
      (println "EXPANDING ITEMS " (:items node))
      (assoc node :items (expanded-form-inner (:items node) context)))
    node))

(defn process-properties [node context]
  (if (some? (:properties node))
    (assoc node :properties (->> (:properties node)
                                 (map (fn [[k v]] [k (expanded-form-inner v context)]))
                                 (into {})))
    node))

(defn expanded-form-inner [type-node context]
  (let [type-node (if (and (map? type-node)
                           (some? (:properties type-node)))
                    (assoc type-node :properties (->> (:properties type-node)
                                                      (map (fn [[k v]] [(name k) v]))
                                                      (into {})))
                    type-node)
        type (if (string? type-node)
               type-node
               (or (:type type-node)
                   (:schema type-node)))]
    (cond

      ;; Multiple inheritance
      (and (not (map? type))
           (coll? type))                      (-> (assoc type-node :type (mapv #(expanded-form-inner % context) type))
                                                  (process-properties context)
                                                  (process-items context)
                                                  (process-constraints type-node)
                                                  clear-node)

      (get atomic-types type)                 (-> {:type type}
                                                  (process-constraints type-node)
                                                  clear-node)

      (or
       (and (nil? type)
            (some? (:items type-node)))
       (= type "array"))                      (-> {:type "array"}
                                                  ;;(assoc :items (get type-node :items {:type "string"}))
                                                  (assoc :items (expanded-form-inner (:items type-node {:type "string"}) context))
                                                  (process-constraints type-node)
                                                  clear-node)

      (or
       (and (nil? type)
            (some? (:properties type-node)))
       (= type "object"))                     (-> {:type "object"}
                                                  (process-constraints type-node)
                                                  (assoc :properties (->> (:properties type-node)
                                                                          (mapv (fn [[prop-name type]] [(name prop-name) (expanded-form-inner type context)]))
                                                                          (into {})))
                                                  clear-node)

      (= type "union")                        (-> {:type "union"
                                                   :anyOf   (mapv #(expanded-form-inner % context) (:anyOf type-node))}
                                                  (process-constraints type-node)
                                                  clear-node)

      (and (some? type)
           (or (string? type)
               (keyword? type))
           (or
            (get context (name type))
            (get context (keyword type))))    (let [ref-type (or (get context (name type))
                                                                 (get context (keyword type)))]
                                                (when (nil? ref-type)
                                                  (throw #?(:clj (Exception. (str "Cannot find reference " (name type)))
                                                            :cljs (js/Error. (str "Cannot find reference " (name type))))))
                                                (if (cycle? (name type) (:path context))
                                                  (do (swap! (:fixpoints context) (fn [fps] (assoc fps type true)))
                                                      (-> {:type  :$recur}
                                                          (process-constraints type-node)
                                                          clear-node))
                                                  (let [path (get context :path)
                                                        context (assoc context :path (conj path (name type)))]
                                                    (if (string? type-node)
                                                      (-> (expanded-form-inner ref-type context)
                                                          (assoc :$ref type))
                                                      (-> (if (map? type-node) type-node {})
                                                          (assoc :$ref type)
                                                          (assoc :type (expanded-form-inner ref-type context))
                                                          (process-properties context)
                                                          (process-items context)
                                                          (process-constraints type-node)
                                                          clear-node)))))

      (xml-type? type)                        (-> {:type "xml", :content type})

      (json-type? type)                       (-> {:type "json", :content type})

      (and (nil? type)
           (some? type-node))                 (-> {:type "string"} ;; or any depending if we are in the body or not
                                                  (process-constraints type-node)
                                                  clear-node)

      (and (string? type)
           (re-matches #"^.*\?$" type))       {:type "union"
                                               :anyOf [{:type (clojure.string/replace type "?" "")}
                                                       {:type "nil"}]}

      (map? type)                             ;; simple inheritance
                                              (let [result (expanded-form-inner (assoc type-node :type [type]) context)]
                                                (-> result
                                                    (process-properties context)
                                                    (process-items context)
                                                    (assoc :type (first (:type result)))))

      :else                                   (let [parsed-type (parse-type-expression type context)]
                                                (if (some? parsed-type)
                                                  (expanded-form-inner parsed-type context)
                                                  (error (str "Unknown type " type " in " context)))))))

(defn add-fixpoints [t fixpoints num-fixpoints]
  (cond
    (and (map? t)
         (some? (:$ref t))) (let [ref-type (:$ref t)
                                  t (dissoc t :$ref)
                                  processed (add-fixpoints t fixpoints num-fixpoints)]
                              (if (some? (get fixpoints ref-type))
                                {:type :fixpoint
                                 :value processed}
                                processed))

    (and
     (map? t)
     (= (:type t) :$recur)) (do (swap! num-fixpoints inc)
                                t)

    (map? t)                 (->> t
                                  (map (fn [[k v]]
                                         [k (add-fixpoints v fixpoints num-fixpoints)]))
                                  (into {}))

    (coll? t) (mapv #(add-fixpoints % fixpoints num-fixpoints) t)


    :else     t))

(defn expanded-form [node context]
  (let [context (setup-context context)
        found-context-type (->> context
                                (filter (fn [[k v]] (= v node)))
                                ffirst)
        path (if (some? found-context-type)
               [found-context-type]
               [])
        context (assoc context :path path)
        expanded (expanded-form-inner node context)
        expanded(if (some? found-context-type)
                  (assoc expanded :$ref found-context-type)
                  expanded)
        num-fixpoints (atom 0)
        expanded (add-fixpoints expanded @(:fixpoints context) num-fixpoints)]
    (if (= @num-fixpoints (count @(:fixpoints context)))
      expanded
      {:type :fixpoint
       :value expanded})))
