(ns logseq.graph-parser.extract
  ;; Disable clj linters since we don't support clj
  #?(:clj {:clj-kondo/config {:linters {:unresolved-namespace {:level :off}
                                        :unresolved-symbol {:level :off}}}})
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [datascript.core :as d]
            [logseq.graph-parser.text :as text]
            [logseq.graph-parser.util :as gp-util]
            [logseq.graph-parser.mldoc :as gp-mldoc]
            [logseq.graph-parser.block :as gp-block]
            [logseq.graph-parser.property :as gp-property]
            [logseq.graph-parser.config :as gp-config]
            #?(:org.babashka/nbb [logseq.graph-parser.log :as log]
               :default [lambdaisland.glogi :as log])))

(defn- filepath->page-name
  [filepath]
  (when-let [file-name (last (string/split filepath #"/"))]
    (let [result (first (gp-util/split-last "." file-name))
          ext (string/lower-case (gp-util/get-file-ext filepath))]
      (if (or (gp-config/mldoc-support? ext) (= "edn" ext))
        (js/decodeURIComponent (string/replace result "." "/"))
        result))))

(defn- get-page-name
  [file ast page-name-order]
  ;; headline
  (let [ast (map first ast)]
    (if (string/includes? file "pages/contents.")
      "Contents"
      (let [first-block (last (first (filter gp-block/heading-block? ast)))
            property-name (when (and (contains? #{"Properties" "Property_Drawer"} (ffirst ast))
                                     (not (string/blank? (:title (last (first ast))))))
                            (:title (last (first ast))))
            first-block-name (let [title (last (first (:title first-block)))]
                               (and first-block
                                    (string? title)
                                    title))
            file-name (filepath->page-name file)]
        (or property-name
            (if (= page-name-order "heading")
              (or first-block-name file-name)
              (or file-name first-block-name)))))))

(defn- build-page-entity
  [properties file page-name page ref-tags {:keys [date-formatter db]}]
  (let [alias (:alias properties)
        alias' (if (string? alias) [alias] alias)
        aliases (and alias'
                     (seq (remove #(or (= page-name (gp-util/page-name-sanity-lc %))
                                       (string/blank? %)) ;; disable blank alias
                                  alias')))
        aliases' (->>
                 (map
                  (fn [alias]
                    (let [page-name (gp-util/page-name-sanity-lc alias)
                          aliases (distinct
                                   (conj
                                    (remove #{alias} aliases)
                                    page))
                          aliases (when (seq aliases)
                                    (map
                                     (fn [alias]
                                       {:block/name (gp-util/page-name-sanity-lc alias)})
                                     aliases))]
                      (if (seq aliases)
                        {:block/name page-name
                         :block/alias aliases}
                        {:block/name page-name})))
                  aliases)
                 (remove nil?))
        [*valid-properties *invalid-properties]
        ((juxt filter remove)
         (fn [[k _v]] (gp-property/valid-property-name? (str k))) properties)
        valid-properties (into {} *valid-properties)
        invalid-properties (set (map (comp name first) *invalid-properties))]
    (cond->
     (gp-util/remove-nils
      (assoc
       (gp-block/page-name->map page false db true date-formatter)
       :block/file {:file/path (gp-util/path-normalize file)}))

     (seq valid-properties)
     (assoc :block/properties valid-properties)

     (seq invalid-properties)
     (assoc :block/invalid-properties invalid-properties)

     (seq aliases')
     (assoc :block/alias aliases')

      (:tags properties)
      (assoc :block/tags (let [tags (:tags properties)
                               tags (if (string? tags) [tags] tags)
                               tags (remove string/blank? tags)]
                           (swap! ref-tags set/union (set tags))
                           (map (fn [tag] {:block/name (gp-util/page-name-sanity-lc tag)
                                           :block/original-name tag})
                                tags))))))

;; TODO: performance improvement
(defn- extract-pages-and-blocks
  [format ast properties file content {:keys [date-formatter page-name-order db] :as options}]
  (try
    (let [page (get-page-name file ast page-name-order)
          [_original-page-name page-name _journal-day] (gp-block/convert-page-if-journal page date-formatter)
          blocks (->> (gp-block/extract-blocks ast content false format (dissoc options :page-name-order))
                      (gp-block/with-parent-and-left {:block/name page-name}))
          ref-pages (atom #{})
          ref-tags (atom #{})
          blocks (map (fn [block]
                        (if (contains? #{"macro"} (:block/type block))
                          block
                          (let [block-ref-pages (seq (:block/refs block))
                                page-lookup-ref [:block/name page-name]
                                block-path-ref-pages (->> (cons page-lookup-ref (seq (:block/path-refs block)))
                                                          (remove nil?))]
                            (when block-ref-pages
                              (swap! ref-pages set/union (set block-ref-pages)))
                            (-> block
                                (dissoc :ref-pages)
                                (assoc :block/format format
                                       :block/page [:block/name page-name]
                                       :block/refs block-ref-pages
                                       :block/path-refs block-path-ref-pages)))))
                      blocks)
          page-entity (build-page-entity properties file page-name page ref-tags options)
          namespace-pages (let [page (:block/original-name page-entity)]
                            (when (text/namespace-page? page)
                              (->> (gp-util/split-namespace-pages page)
                                   (map (fn [page]
                                          (-> (gp-block/page-name->map page true db true date-formatter)
                                              (assoc :block/format format)))))))
          pages (->> (concat
                      [page-entity]
                      @ref-pages
                      (map
                       (fn [page]
                         {:block/original-name page
                          :block/name (gp-util/page-name-sanity-lc page)})
                       @ref-tags)
                      namespace-pages)
                     ;; remove block references
                     (remove vector?)
                     (remove nil?))
          pages (gp-util/distinct-by :block/name pages)
          pages (remove nil? pages)
          pages (map (fn [page] (assoc page :block/uuid (d/squuid))) pages)
          blocks (->> (remove nil? blocks)
                      (map (fn [b] (dissoc b :block/title :block/body :block/level :block/children :block/meta :block/anchor))))]
      [pages blocks])
    (catch :default e
      (log/error :exception e))))

(defn extract
  "Extracts pages, blocks and ast from given file"
  [file content {:keys [user-config verbose] :or {verbose true} :as options}]
  (if (string/blank? content)
    []
    (let [format (gp-util/get-format file)
          _ (when verbose (println "Parsing start: " file))
          ast (gp-mldoc/->edn content (gp-mldoc/default-config format
                                        ;; {:parse_outline_only? true}
                                                               )
                              user-config)]
      (when verbose (println "Parsing finished: " file))
      (let [first-block (ffirst ast)
            properties (let [properties (and (gp-property/properties-ast? first-block)
                                             (->> (last first-block)
                                                  (map (fn [[x y]]
                                                         [x (if (and (string? y)
                                                                     (not (and (= (keyword x) :file-path)
                                                                               (string/starts-with? y "file:"))))
                                                              (text/parse-property format x y user-config)
                                                              y)]))
                                                  (into {})
                                                  (walk/keywordize-keys)))]
                         (when (and properties (seq properties))
                           (if (:filters properties)
                             (update properties :filters
                                     (fn [v]
                                       (string/replace (or v "") "\\" "")))
                             properties)))
            [pages blocks] (extract-pages-and-blocks format ast properties file content options)]
        {:pages pages
         :blocks blocks
         :ast ast}))))

(defn get-shape-refs [shape]
  (when (= "logseq-portal" (:type shape))
    [(if (= (:blockType shape) "P")
       {:block/name (gp-util/page-name-sanity-lc (:pageId shape))}
       {:block/uuid (uuid (:pageId shape))})]))

(defn- with-whiteboard-block-refs
  [shape]
  (let [refs (or (get-shape-refs shape) [])]
    (merge {:block/refs refs})))

(defn- with-whiteboard-content
  [shape]
  {:block/content (case (:type shape)
                    "text" (:text shape)
                    "logseq-portal" (if (= (:blockType shape) "P")
                                      (str "[[" (:pageId shape) "]]")
                                      (str "((" (:pageId shape) "))"))
                    "line" (str "whiteboard arrow" (when-let [label (:label shape)] (str ": " label)))
                    (str "whiteboard " (:type shape)))})

(defn with-whiteboard-block-props
  [block page-name]
  (let [shape (:block/properties block)
        shape? (= :whiteboard-shape (:ls-type shape))
        default-page-ref {:block/name (gp-util/page-name-sanity-lc page-name)}]
    (merge (when shape?
             (merge
              {:block/uuid (uuid (:id shape))}
              (with-whiteboard-block-refs shape)
              (with-whiteboard-content shape)))
           (when (nil? (:block/parent block)) {:block/parent default-page-ref})
           (when (nil? (:block/format block)) {:block/format :markdown}) ;; TODO: read from config
           {:block/page default-page-ref})))

(defn extract-whiteboard-edn
  "Extracts whiteboard page from given edn file
   Whiteboard page edn is a subset of page schema
   - it will only contain a single page (for now). The page properties contains 'bindings' etc
   - blocks will be adapted to tldraw shapes. All blocks's parent is the given page."
  [file content {:keys [verbose] :or {verbose true} :as options}]
  (let [_ (when verbose (println "Parsing start: " file))
        {:keys [pages blocks]} (gp-util/safe-read-string content)
        page-block (first pages)
        page-name (filepath->page-name file)
        page-original-name (-> (:block/original-name page-block)
                               (#(cond (nil? %) page-name
                                       (= (gp-util/page-name-sanity-lc %)
                                          (gp-util/page-name-sanity-lc page-name)) page-name
                                       :else %)))
        page-name (gp-util/page-name-sanity-lc page-name)
        page-entity (build-page-entity (:block/properties page-block) file page-name page-original-name nil options)
        page-block (merge page-block page-entity (when-not (:block/uuid page-block) {:block/uuid (d/squuid)}))
        blocks (->> blocks
                    (map #(merge % {:block/level 1 ;; fixme
                                    :block/uuid (or (:block/uuid %)
                                                    (gp-block/get-custom-id-or-new-id (:block/properties %)))}
                                 (with-whiteboard-block-props % page-name)))
                    (gp-block/with-parent-and-left {:block/name page-name}))
        _ (when verbose (println "Parsing finished: " file))]
    {:pages (list page-block)
     :blocks blocks}))

(defn- with-block-uuid
  [pages]
  (->> (gp-util/distinct-by :block/name pages)
       (map (fn [page]
              (if (:block/uuid page)
                page
                (assoc page :block/uuid (d/squuid)))))))

(defn with-ref-pages
  [pages blocks]
  (let [ref-pages (->> (mapcat :block/refs blocks)
                       (filter :block/name))]
    (->> (concat pages ref-pages)
         (group-by :block/name)
         vals
         (map (partial apply merge))
         (with-block-uuid))))

;; TODO: Properly fix this circular dependency:
;; mldoc/->edn > text/parse-property > mldoc/link? ->mldoc/inline->edn + mldoc/default-config
(set! gp-mldoc/parse-property text/parse-property)
