(ns com.vetd.app.buyers
  (:require [com.vetd.app.db :as db]
            [com.vetd.app.journal :as journal]
            [com.vetd.app.hasura :as ha]
            [com.vetd.app.auth :as auth]
            [com.vetd.app.rounds :as rounds]
            [com.vetd.app.common :as com]
            [com.vetd.app.util :as ut]
            [com.vetd.app.docs :as docs]
            [taoensso.timbre :as log]
            [clj-time.coerce :as tc]
            [clojure.string :as s]))

(defn product-id->name
  [product-id]
  (-> [[:products {:id product-id}
        [:pname]]]
      ha/sync-query
      vals
      ffirst
      :pname))

(defn search-prods-vendors->ids
  "Get product ID's based on search query and filter (and union with related categories)."
  [q cat-ids {:keys [features groups discounts-available-to-groups] :as filter-map} buyer-id]
  (let [term (s/trim q)]
    (if (or (not-empty term)
            (some seq (vals filter-map)))
      (let [ids (db/hs-query
                 {:select (concat
                           [[:p.id :pid]
                            [(honeysql.core/raw "coalesce(p.score, 1.0)") :nscore]
                            [(honeysql.core/raw "coalesce(p.profile_score, 0.0)") :pscore]]
                           (when (features "preposal")
                             [[:d.created :dcreated]]))
                  :modifiers [:distinct]
                  :from [[:products :p]]
                  :join (concat [[:orgs :o] [:= :o.id :p.vendor_id]]
                                (when (features "free-trial")
                                  [[:docs_to_fields :d2f] [:and
                                                           [:= :d2f.doc_subject :p.id]
                                                           [:= :d2f.doc_dtype "product-profile"]
                                                           [:= :d2f.prompt_term "product/free-trial?"]
                                                           [:= :d2f.resp_field_sval "yes"]]])
                                (when (features "preposal")
                                  [[:docs :d] [:and
                                               [:= :d.subject :p.id]
                                               [:= :d.dtype "preposal"]
                                               [:= :d.to_org_id buyer-id]
                                               [:= :d.deleted nil]]])
                                (when (not-empty groups)
                                  [[:group_org_memberships :gom] [:in :gom.group_id groups]
                                   [:stack_items :si] [:and
                                                       [:= :si.deleted nil]
                                                       [:= :si.buyer_id :gom.org_id]
                                                       [:= :si.product_id :p.id]]])
                                (when (not-empty discounts-available-to-groups)
                                  [[:group_discounts :gd] [:and
                                                           [:= :gd.deleted nil]
                                                           [:in :gd.group_id discounts-available-to-groups]
                                                           [:= :gd.product_id :p.id]]]))
                  :left-join (when (not-empty cat-ids)
                               [[:product_categories :pc] [:= :p.id :pc.prod_id]])
                  :where [:and
                          [:= :p.deleted nil]
                          [:= :o.deleted nil]
                          [:or
                           [(keyword "~*") :p.pname (str ".*?\\m" term ".*")]
                           [(keyword "~*") :o.oname (str ".*?\\m" term ".*")]
                           (when (not-empty cat-ids)	
                             [:in :pc.cat_id cat-ids])]
                          (when (features "product-profile-completed")
                            [:>= :p.profile_score 0.9])]
                  :order-by (if (features "preposal")
                              [[:dcreated :desc]]
                              [[:pscore :desc] [:nscore :desc]])
                  ;; this will be paginated on the frontend
                  :limit 200})
            pids (map :pid ids)]
        {:product-ids pids})
      {:product-ids []})))

(defn search-category-ids
  [q]
  (if (not-empty q)
    (let [initials (when (#{3 4} (count q))
                     [(keyword "~*") :cname
                      (apply str (for [c q]
                                   (str "\\m" c ".*?")))])
          wh [(keyword "~*") :cname (str ".*?\\m" q ".*")]
          wh' (if initials
                [:or wh initials]
                wh)]
      (mapv :id
            (db/hs-query {:select [:id]
                          :from [:categories]
                          :where [:and
                                  [:= :deleted nil]
                                  wh']
                          :limit 5})))))

(defn select-rounds-by-ids
  [b-id v-ids]
  (db/hs-query {:select [:*]
                :from [:rounds]
                :where [:and
                        [:= :buyer-id b-id]
                        [:in :vendor-id v-ids]]}))

#_(select-prep-reqs-by-ids 3 [1 2 3 4])

(defn invert-vendor-data
  [m]
  (let [m1 (ut/fmap #(group-by (some-fn :vendor-id :id) %)
                    m)
        paths (for [[k v] m1
                    [k2 v2] v]
                [[k2 k] (first v2)])]
    (reduce (fn [agg [ks v]]
              (assoc-in agg ks v))
            {}
            paths)))

;; prefill can be used to prefill the initiation form
;; currently only :prompts key is supported
(defn insert-round
  [buyer-id title & [prefill]]
  (let [[id idstr] (ut/mk-id&str)]
    (-> (db/insert! :rounds
                    {:id id
                     :idstr idstr
                     :buyer_id buyer-id
                     :status "initiation"
                     :title title
                     :initiation_form_prefill prefill
                     :created (ut/now-ts)
                     :updated (ut/now-ts)})
        first)))

(defn insert-round-category
  [round-id category-id]
  (let [[id idstr] (ut/mk-id&str)]
    (-> (db/insert! :round_category
                    {:id id
                     :idstr idstr
                     :round_id round-id
                     :category_id category-id
                     :created (ut/now-ts)
                     :updated (ut/now-ts)})
        first)))

;; Adding a topic (requirement) to a round after the round already is
;; 'in-progress' is done by prompt term.
;; However, it's treated differently when the round initiation form
;; is first submitted: topics are added by prompt id.
;; This function is used for in-progress rounds.
(defn add-requirement-to-round
  "Add requirement to round by Round ID or by the form template ID of requirements form template."
  [requirement-term & [{:keys [round-id form-template-id]}]]
  (let [req-form-template-id (or form-template-id
                                 (-> [[:rounds {:id round-id}
                                       [:req-form-template-id]]]
                                     ha/sync-query
                                     vals
                                     ffirst
                                     :req-form-template-id))
        new-req? (s/starts-with? requirement-term "new-topic/")
        {:keys [id]} (if new-req?
                       ;; In frontend, new topics are given a fake term
                       ;; like so: "new-topic/Topic Text That User Entered"
                       (-> requirement-term 
                           (s/replace #"new-topic/" "")
                           docs/create-round-req-prompt&fields)
                       (-> requirement-term
                           docs/get-prompts-by-term
                           first))
        existing-prompts (docs/select-form-template-prompts-by-parent-id req-form-template-id)]
    (when-not (some #(= id (:prompt-id %)) existing-prompts)
      (docs/insert-form-template-prompt req-form-template-id id)
      (let [form-ids (docs/merge-template-to-forms req-form-template-id)
            doc-ids (->> [[:docs {:form-id form-ids}
                           [:id]]]
                         ha/sync-query
                         :docs
                         (map :id))]
        (doseq [doc-id doc-ids]
          (docs/auto-pop-missing-responses-by-doc-id doc-id))))))

(defn create-round
  [buyer-id title eid etype prompts product-ids]
  (let [{:keys [id] :as r} (insert-round buyer-id title {:prompts prompts})]
    (case etype
      ;; TODO call sync-round-vendor-req-forms too, once we're ready
      :product (rounds/invite-product-to-round eid id) 
      :category (insert-round-category id eid)
      ;; TODO put this in a future?
      ;; hmmm the form template isn't created yet...
      :duplicate (doseq [product-id product-ids]
                   (rounds/invite-product-to-round product-id id))
      nil)
    (try
      (let [{:keys [id buyer products categories] :as round}
            (-> [[:rounds {:id id}
                  [:id :created
                   [:buyer [:id :oname]]
                   [:products [:id :pname]]
                   [:categories [:cname]]]]]
                ha/sync-query
                vals
                ffirst)]
        (journal/push-entry {:jtype :round-started
                             :round-id id
                             :title title
                             :buyer-org-id (:id buyer)
                             :buyer-org-name (:oname buyer)
                             :product-names (mapv :pname products)
                             :product-ids (mapv :id products)
                             :category-names (mapv :cname categories)}))
      (catch Throwable t))
    r))

(defn send-new-prod-cat-req [uid oid req]
  (let [user-name (-> uid auth/select-user-by-id :uname)
        org-name (-> oid auth/select-org-by-id :oname)]
    (com/sns-publish :ui-req-new-prod-cat
                     "New Product/Category Request"
                     (format
                      "New Product/Category Request
Request Text '%s'
Org '%s'
User '%s'
"
                      req org-name user-name))))

(defn send-complete-profile-req [etype eid field-key buyer-id]
  (let [ename (if (= :vendor etype)
                (-> eid auth/select-org-by-id :oname)
                (product-id->name eid))
        buyer-name (-> buyer-id auth/select-org-by-id :oname)]
    (do (journal/push-entry
         {:jtype (keyword (str "complete-" (name etype) "-profile-request"))
          :buyer-org-name buyer-name
          :buyer-org-id buyer-id
          :field-name field-key
          (keyword (str (name etype) "-id")) eid
          (keyword (str (name etype) "-name")) ename})
        (com/sns-publish
         :customer-success
         (str "Complete " (name etype) " Profile Request")
         (str "Complete " (name etype) " Profile Request\n"
              "buyer: " buyer-name "\n"
              (name etype) ": " ename " (ID: " eid ")\n"
              "field name: " field-key)
         {:org-id buyer-id}))))

(defn send-buy-req [buyer-id product-id]
  (let [{:keys [pname rounds]} (-> [[:products {:id product-id}
                                     [:pname
                                      [:rounds {:deleted nil}
                                       [:idstr]]]]]
                                   ha/sync-query
                                   :products
                                   first)
        buyer-name (-> buyer-id auth/select-org-by-id :oname)]
    (do (journal/push-entry
         {:jtype :buy-request
          :buyer-org-name buyer-name
          :buyer-org-id buyer-id
          :product-id product-id
          :product-name pname})
        (com/sns-publish
         :customer-success
         "Buy Request"
         (format
          "Buy Request
Buyer (Org): '%s'
Product: '%s'
Round URLs (if any):
%s"
          (-> buyer-id auth/select-org-by-id :oname) ; buyer name
          pname
          (->> (for [{:keys [idstr]} rounds]
                 (str "https://app.vetd.com/b/rounds/" idstr))
               (clojure.string/join "\n")))
         {:org-id buyer-id}))))

(defn send-setup-call-req [buyer-id product-id]
  (let [{:keys [pname rounds]} (-> [[:products {:id product-id}
                                     [:pname
                                      [:rounds {:deleted nil}
                                       [:idstr]]]]]
                                   ha/sync-query
                                   :products
                                   first)]
    (com/sns-publish
     :customer-success
     "Setup Call Request"
     (format
      "Setup Call Request
Buyer (Org): '%s'
Product: '%s'
Round URLs (if any):
%s"
      (-> buyer-id auth/select-org-by-id :oname) ; buyer name
      pname
      (->> (for [{:keys [idstr]} rounds]
             (str "https://app.vetd.com/b/rounds/" idstr))
           (clojure.string/join "\n")))
     {:org-id buyer-id})))

(defn send-ask-question-req [product-id message round-id requirement-text buyer-id]
  (com/sns-publish
   :customer-success
   "Ask a Question Request"
   (str "Ask a Question Request"
        "\nBuyer (Org): " (-> buyer-id auth/select-org-by-id :oname) ; buyer name
        "\nProduct: " (product-id->name product-id) ; product name
        (when round-id (str  "\nRound ID: " round-id
                             "\nRound Link: https://app.vetd.com/b/rounds/" (ut/base31->str round-id)))
        (when requirement-text (str  "\nRequirement: " requirement-text))
        "\nMessage:\n" message)
   {:org-id buyer-id}))

(defn send-prep-req
  [{:keys [to-org-id to-user-id from-org-id from-user-id prod-id] :as prep-req}]
  (let [buyer-org-name (-> from-org-id auth/select-org-by-id :oname)
        buyer-user-name (-> from-user-id auth/select-user-by-id :uname)
        product-id prod-id
        product-name (product-id->name prod-id)]
    (do (journal/push-entry
         {:jtype :preposal-request
          :buyer-org-id from-org-id
          :buyer-org-name buyer-org-name
          :vendor-org-id to-org-id
          :user-id from-user-id
          :user-name buyer-user-name
          :product-id product-id
          :product-name product-name})
        (com/sns-publish
         :customer-success
         "PrePosal Request"
         (format
          "PrePosal Request
Buyer (Org): '%s'
Buyer User: '%s'
Product: '%s'"
          (-> from-org-id auth/select-org-by-id :oname) ; buyer org name
          (-> from-user-id auth/select-user-by-id :uname) ; buyer user name
          (product-id->name prod-id))
         {:org-id from-org-id}))))

(defn set-preposal-result [id result reason]
  "Set the result of a preposal (0 - rejected, nil - live)."
  (db/update-any! {:id id
                   :result result
                   :reason reason}
                  :docs))

(defn set-round-product-result [round-id product-id result reason]
  "Set the result of a product in a round (0 - disqualified, 1 - winner)."
  (do (when (= 1 result)
        (try (let [{:keys [idstr buyer products]} (-> [[:rounds {:id round-id}
                                                        [:idstr
                                                         [:buyer [:id :oname]]
                                                         [:products {:id product-id}
                                                          [:pname]]]]]
                                                      ha/sync-query
                                                      vals
                                                      ffirst)
                   buyer-org-name (:oname buyer)
                   buyer-org-id (:id buyer)
                   product-name (-> products first :pname)]
               (do (journal/push-entry {:jtype :round-winner-declared
                                        :buyer-org-name buyer-org-name
                                        :buyer-org-id buyer-org-id
                                        :round-id round-id
                                        :product-id product-id
                                        :product-name product-name})
                   (com/sns-publish :customer-success
                                    "Round Winner Declared"
                                    (format
                                     "Round Winner Declared
Buyer: '%s'
Product: '%s'
Round URL: https://app.vetd.com/b/rounds/%s"
                                     buyer-org-name
                                     product-name
                                     idstr)
                                    {:org-id (:id buyer)})))
             (catch Exception e
               (com/log-error e))))
      (when-let [id (->> [[:round-product {:round-id round-id
                                           :product-id product-id
                                           :deleted nil}
                           [:id]]]
                         ha/sync-query
                         :round-product
                         first
                         :id)]
        (db/update-any! {:id id
                         :result result
                         :reason reason}
                        :round_product))
      (when (= 1 result)    ; additional effects of declaring a winner
        (let [rps (->> [[:round-product {:round-id round-id
                                         :result nil
                                         :deleted nil}
                         [:id]]]
                       ha/sync-query
                       :round-product)]
          ;; disqualify any live products in the round
          (doseq [{:keys [id]} rps]
            (db/update-any! {:id id
                             :result 0
                             :reason "A different product was declared winner."}
                            :round_product))
          ;; update round status
          (db/update-any! {:id round-id
                           :status "complete"}
                          :rounds)))))

;; TODO there could be multiple preposals/rounds per buyer-vendor pair

;; TODO use session-id to verify permissions!!!!!!!!!!!!!
(defmethod com/handle-ws-inbound :b/search
  [{:keys [buyer-id query filter-map]} ws-id sub-fn]
  (let [cat-ids (search-category-ids query)]
    (ut/$- -> query
           (search-prods-vendors->ids $ cat-ids filter-map buyer-id)
           (assoc :category-ids cat-ids))))

;; Start a round for either a Product or a Category
;; TODO record which user started round
(defmethod com/handle-ws-inbound :b/start-round
  [{:keys [buyer-id title etype eid prompts product-ids]} ws-id sub-fn]
  (create-round buyer-id title eid etype prompts product-ids))

;; Request Preposal    TODO this can be renamed to something like :b/preposals.request
(defmethod com/handle-ws-inbound :b/create-preposal-req
  [{:keys [prep-req]} ws-id sub-fn]
  (send-prep-req prep-req)
  (docs/create-preposal-req-form prep-req))

;; [Reject]/[Undo Reject] a Preposal
(defmethod com/handle-ws-inbound :b/preposals.set-result
  [{:keys [id result reason buyer-id] :as req} ws-id sub-fn]
  (set-preposal-result id result reason)
  {})

;; Request an addition to our Products / Categories
(defmethod com/handle-ws-inbound :b/req-new-prod-cat
  [{:keys [user-id org-id req]} ws-id sub-fn]
  (send-new-prod-cat-req user-id org-id req))

;; Request that a vendor complete their Company/Product Profile
(defmethod com/handle-ws-inbound :b/request-complete-profile
  [{:keys [etype eid field-key buyer-id]} ws-id sub-fn]
  (send-complete-profile-req etype eid field-key buyer-id))

(defmethod com/handle-ws-inbound :b/buy
  [{:keys [buyer-id product-id]} ws-id sub-fn]
  (send-buy-req buyer-id product-id))

;; Have Vetd set up a phone call for the buyer with the vendor
(defmethod com/handle-ws-inbound :b/setup-call
  [{:keys [buyer-id product-id]} ws-id sub-fn]
  (send-setup-call-req buyer-id product-id))

;; Ask a question about a specific product
(defmethod com/handle-ws-inbound :b/ask-a-question
  [{:keys [product-id message round-id requirement-text buyer-id]} ws-id sub-fn]
  (send-ask-question-req product-id message round-id requirement-text buyer-id))

(defmethod com/handle-ws-inbound :b/round.add-requirements
  [{:keys [round-id requirements]} ws-id sub-fn]
  (let [{:keys [idstr buyer req-form-template-id]} (-> [[:rounds {:id round-id}
                                                         [:idstr :req-form-template-id
                                                          [:buyer [:id :oname]]]]]
                                                       ha/sync-query
                                                       vals
                                                       ffirst)]
    (doseq [requirement requirements]
      (add-requirement-to-round requirement {:form-template-id req-form-template-id}))
    (future
      (journal/push-entry {:jtype :new-topics-added-to-round
                           :buyer-org-name (:oname buyer)
                           :buyer-org-id (:id buyer)
                           :round-id round-id
                           :topics requirements})
      (com/sns-publish :customer-success
                       "New Topics Added to Round"
                       (format
                        "New Topics Added to Round
Buyer: '%s'
Topics: '%s'
Round Link: https://app.vetd.com/b/rounds/%s"
                        (:oname buyer)
                        (let [existing-prompt-terms (remove
                                                     #(s/starts-with? (str %) "new-topic/")
                                                     requirements)
                              new-prompts (map #(s/replace (str %) #"new-topic/" "")
                                               (remove (set existing-prompt-terms) requirements))]
                          (->> [[:prompts {:term existing-prompt-terms}
                                 [:prompt]]]
                               ha/sync-query
                               :prompts
                               (map :prompt)
                               (concat new-prompts)
                               (map #(str "\"" % "\""))
                               (s/join ", ")))
                        idstr)
                       {:org-id (:id buyer)}))
    {}))

(defmethod com/handle-ws-inbound :b/round.set-topic-order
  [{:keys [round-id prompt-ids]} ws-id sub-fn]
  (let [{:keys [req-form-template-id]} (-> [[:rounds {:id round-id}
                                             [:req-form-template-id]]]
                                           ha/sync-query
                                           vals
                                           ffirst)]
    (docs/set-form-template-prompts-order req-form-template-id prompt-ids)
    {}))

(defmethod com/handle-ws-inbound :save-doc
  [{:keys [data ftype update-doc-id from-org-id] :as req} ws-id sub-fn]
  (if (nil? update-doc-id)
    (docs/create-doc req)
    (docs/update-doc req)))

;; result - 0 (disqualify), 1 (winner), nil (undisqualify, etc...)
(defmethod com/handle-ws-inbound :b/round.declare-result
  [{:keys [round-id product-id buyer-id result reason] :as req} ws-id sub-fn]
  (set-round-product-result round-id product-id result reason)
  {})

(defmethod com/handle-ws-inbound :save-response
  [{:keys [subject subject-type term user-id round-id org-id fields] :as req} ws-id sub-fn]
  (when-not (= term :round.response/rating)
    (throw (Exception. (format "NOT IMPLEMENTED: term = %s"
                               term))))
  (let [rating-prompt-id 1093760230399 ;; HACK -- hard-coded id
        rating-prompt-field-id 1093790890400]
    (db/update-deleted-where :responses
                             [:and
                              [:= :prompt_id rating-prompt-id]
                              [:= :subject subject]])
    (let [{:keys [value]} fields
          {:keys [id]} (-> req
                           (assoc :prompt-id rating-prompt-id)
                           docs/insert-response)]
      (docs/insert-response-field id
                                  {:prompt-field-id rating-prompt-field-id
                                   :idx 0
                                   :sval nil
                                   :nval value
                                   :dval nil
                                   :jval nil}))))

;; this is called from within a (future)
(defn notify-round-init-form-completed
  [doc-id]
  (let [round (-> [[:docs {:id doc-id}
                    [[:rounds
                      [:id :idstr :title :created
                       [:buyer [:id :oname]]
                       [:products [:id :pname]]
                       [:categories [:id :cname]]
                       [:init-doc
                        [:id
                         [:response-prompts {:ref-deleted nil}
                          [:id :prompt-id :prompt-prompt :prompt-term
                           [:response-prompt-fields
                            [:id :prompt-field-fname :idx
                             :sval :nval :dval]]]]]]]]]]]
                  ha/sync-query
                  vals
                  ffirst
                  :rounds)]
    (do (journal/push-entry
         {:jtype :round-init-form-completed
          :round-id (->> round :id)
          :title (->> round :title)
          :buyer-org-id (-> round :buyer :id)
          :buyer-org-name (-> round :buyer :oname)
          :product-names (->> round :products (map :pname))
          :product-ids (->> round :products (map :id))})
        (com/sns-publish
         :customer-success
         "Round Initiated"
         (str "Round Initiated\n\n"
              ;; add round link
              (str "Round ID: " (-> round :id)
                   "\nRound Link: https://app.vetd.com/b/rounds/" (-> round :idstr)
                   "\nBuyer (Org): " (-> round :buyer :oname)
                   "\nProducts: " (->> round :products (map :pname) (s/join ", "))
                   "\nCategories: " (->> round :categories (map :cname) (s/join ", "))
                   (apply str
                          (for [rp (-> round :init-doc :response-prompts)]
                            (str "\n" (:prompt-prompt rp) ": "
                                 (let [svals (->> rp :response-prompt-fields (map :sval))]
                                   (if (= (:prompt-term rp) "rounds/requirements")
                                     (let [existing-prompt-ids (remove
                                                                #(s/starts-with? (str %) "new-topic/")
                                                                svals)
                                           new-prompts (map #(s/replace (str %) #"new-topic/" "")
                                                            (remove (set existing-prompt-ids) svals))]
                                       (->> [[:prompts {:id existing-prompt-ids}
                                              [:prompt]]]
                                            ha/sync-query
                                            :prompts
                                            (map :prompt)
                                            (concat new-prompts)
                                            (map #(str "\"" % "\""))
                                            (s/join ", ")))
                                     (s/join ", " svals))))))))
         {:org-id (-> round :buyer :id)}))))

(defn set-round-products-order [round-id product-ids]
  (doall
   (map-indexed
    (fn [idx product-id]
      (db/update-where :round_product
                       {:sort idx}
                       [:and
                        [:= :round_id round-id]
                        [:= :product_id product-id]]))
    product-ids)))

;; additional side effects upon creating a round-initiation doc
(defmethod docs/handle-doc-creation :round-initiation
  [{:keys [id]} {:keys [round-id]}]
  (let [{form-template-id :id} (try (docs/create-form-template-from-round-doc round-id id)
                                    (catch Throwable t
                                      (com/log-error t)))]
    (try
      (db/update-any! {:id round-id
                       :doc_id id
                       :req_form_template_id form-template-id
                       :status "in-progress"}
                      :rounds)
      (catch Throwable t
        (com/log-error t)))
    (future
      (try
        (rounds/sync-round-vendor-req-forms&docs round-id)
        (catch Throwable t))
      (try
        (notify-round-init-form-completed id)
        (catch Throwable t
          (com/log-error t))))))

(defmethod com/handle-ws-inbound :b/round.add-products
  [{:keys [round-id product-ids product-names buyer-id]} ws-id sub-fn]
  (future
    (com/sns-publish
     :customer-success
     "Product(s) Added to Round"
     (str "Product(s) Added to Round\n\n"
          "Round ID: " round-id
          "\nRound Link: https://app.vetd.com/b/rounds/" (ut/base31->str round-id)
          "\nBuyer (Org): " (-> buyer-id auth/select-org-by-id :oname) ; buyer name
          ;; adding existing products
          (when-not (empty? product-ids)
            (let [products (-> [[:products {:id product-ids}
                                 [:pname]]]
                               ha/sync-query
                               :products)]
              (str "\nProduct(s) Added: " (s/join ", " (map :pname products)))))
          ;; adding products that don't exist in our system yet
          (when-not (empty? product-names)
            "\nNonexistent Product(s) Requested: ") (s/join ", " product-names))
     {:org-id buyer-id}))
  (when-not (empty? product-ids)
    (doseq [product-id product-ids]
      (rounds/invite-product-to-round product-id round-id))
    (try
      (rounds/sync-round-vendor-req-forms&docs round-id)
      (catch Throwable t)))
  {})

(defmethod com/handle-ws-inbound :b/set-round-products-order
  [{:keys [product-ids user-id org-id round-id]} ws-id sub-fn]
  (set-round-products-order round-id product-ids))

(defmethod com/handle-ws-inbound :b/round.share
  [{:keys [round-id round-title email-addresses buyer-id]} ws-id sub-fn]
  (let [buyer-org-name (-> buyer-id auth/select-org-by-id :oname)]
    (do (journal/push-entry
         {:jtype :share-round
          :round-id round-id
          :email-addresses email-addresses
          :buyer-org-id buyer-id
          :buyer-org-name buyer-org-name})
        (com/sns-publish
         :customer-success
         "Share VetdRound"
         (str "Share VetdRound"
              "\n\nBuyer Name: " buyer-org-name
              "\nRound ID: " round-id
              "\nRound Link: https://app.vetd.com/b/rounds/" (ut/base31->str round-id)
              "\nRound Title: " round-title
              "\nEmail Addresses: " (s/join ", " email-addresses))
         {:org-id buyer-id})))
  {})

(defn insert-stack-item
  [{:keys [product-id buyer-id status price-amount price-period
           renewal-date renewal-day-of-month renewal-reminder rating]}]
  (let [[id idstr] (ut/mk-id&str)]
    (db/insert! :stack_items
                {:id id
                 :idstr idstr
                 :created (ut/now-ts)
                 :updated (ut/now-ts)
                 :product_id product-id
                 :buyer_id buyer-id
                 :status status
                 :price_amount price-amount
                 :price_period price-period
                 :renewal_date renewal-date
                 :renewal_day_of_month renewal-day-of-month
                 :renewal_reminder renewal-reminder
                 :rating rating})
    id))

(defmethod com/handle-ws-inbound :create-stack-item
  [{:keys [buyer-id] :as req} ws-id sub-fn]
  (journal/push-entry (assoc req
                             :jtype :create-stack-item
                             :buyer-org-id buyer-id))
  (insert-stack-item req))

(defmethod com/handle-ws-inbound :b/stack.add-items
  [{:keys [buyer-id product-ids]} ws-id sub-fn]
  (if-not (empty? product-ids)
    (do (let [buyer-name (-> [:orgs {:id buyer-id}
                              [:oname]]
                             ha/ez-sync-query
                             :oname)]
          (doseq [pid product-ids]
            (let [product-name (-> [:products {:id pid}
                                    [:pname]]
                                   ha/ez-sync-query
                                   :pname)]
              (journal/push-entry {:jtype :stack-add-items
                                   :buyer-org-id buyer-id
                                   :buyer-org-name buyer-name
                                   :product-name product-name
                                   :product-id pid
                                   :status "current"}))))
        {:stack-item-ids (mapv #(insert-stack-item {:buyer-id buyer-id
                                                    :product-id %
                                                    :status "current"})
                               product-ids)})
    {}))

(defmethod com/handle-ws-inbound :b/stack.delete-item
  [{:keys [stack-item-id]} ws-id sub-fn]
  (db/update-deleted :stack_items stack-item-id))


(defn journal-stack-update-rating [stack-item-id rating]
  (when (some-> rating pos?)
    (let [{:keys [product buyer]} (-> [[:stack-items {:id stack-item-id}
                                        [[:product [:id :pname]]
                                         [:buyer [:id :oname]]]]]
                                      ha/sync-query
                                      vals
                                      ffirst)]
      (journal/push-entry {:jtype :stack-update-rating
                           :rating rating
                           :stack-item-id stack-item-id
                           :product-id (:id product)
                           :product-name (:pname product)
                           :buyer-org-id (:id buyer)
                           :buyer-org-name (:oname buyer)}))))

(defmethod com/handle-ws-inbound :b/stack.update-item
  [{:keys [stack-item-id status
           price-amount price-period
           renewal-date renewal-day-of-month
           renewal-reminder rating]
    :as req}
   ws-id sub-fn]
  (journal-stack-update-rating stack-item-id rating)
  (db/update-any! (merge {:id stack-item-id}
                         (when-not (nil? status)
                           {:status status})
                         (when-not (nil? price-amount)
                           {:price_amount (-> price-amount
                                              str
                                              (.replaceAll "[^0-9.]" "")
                                              ut/->double)})
                         (when-not (nil? price-period)
                           {:price_period price-period})
                         (when-not (nil? renewal-date)
                           {:renewal_date (if (s/blank? renewal-date) ; blank string is used to unset renewal-date
                                            nil
                                            (-> renewal-date
                                                tc/to-long
                                                java.sql.Timestamp.))})
                         (when-not (nil? renewal-day-of-month)
                           {:renewal_day_of_month
                            (if (s/blank? renewal-day-of-month) ; blank string is used to unset renewal-day-of-month
                              nil
                              (-> renewal-day-of-month
                                  ut/->int))})
                         (when-not (nil? renewal-reminder)
                           {:renewal_reminder renewal-reminder})
                         (when-not (nil? rating)
                           {:rating (if (= rating 0) ; rating 0 is used to unset the rating
                                      nil
                                      rating)}))
                  :stack_items)
  {})

(defmethod com/handle-ws-inbound :b/stack.upload-csv
  [{:keys [buyer-id file-contents]} ws-id sub-fn]
  (com/s3-put "vetd-stack-csv-uploads"
              (str (-> buyer-id auth/select-org-by-id :oname) " " (ut/now-ts) ".csv")
              file-contents))

