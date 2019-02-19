(ns vetd-app.pages.vendors.v-home
  (:require [vetd-app.util :as ut]
            [vetd-app.flexer :as flx]
            [vetd-app.docs :as docs]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-com.core :as rc]))

(rf/reg-event-fx
 :v/nav-home
 (fn [{:keys [db]} _]
   {:nav {:path "/v/home/"}}))

(rf/reg-event-db
 :v/route-home
 (fn [db [_ query-params]]
   (assoc db
          :page :v/home
          :query-params query-params)))

(defn walk-deref-ratoms
  [frm]
  (clojure.walk/postwalk
   (fn [f]
     (if (instance? reagent.ratom/RAtom f)
       @f
       f))
   frm))

(rf/reg-event-fx
 :save-form-doc
 (fn [{:keys [db]} [_ form-doc]]
   (def fd1 (walk-deref-ratoms form-doc))
#_   (cljs.pprint/pprint fd1)
   {:ws-send {:payload {:cmd :save-form-doc
                        :return nil
                        :form-doc (walk-deref-ratoms form-doc)}}}))

  ;; TODO support multiple response fields (for where list? = true)
(defn mk-form-doc-prompt-field-state
  [fields {:keys [id] :as prompt-field}]
  (let [{:keys [sval nval dval] :as resp-field} (some-> id fields first)
        resp-field' (merge resp-field
                           {:state (r/atom (str (or dval nval sval
                                                    "")))})]
    (assoc prompt-field
           :response
           [resp-field'])))

(defn mk-form-doc-prompt-state
  [responses {:keys [id] :as prompt}]
  (let [{:keys [fields notes] :as response} (responses id)
        response' (merge response
                         {:notes-state (r/atom (or notes ""))})
        fields' (group-by :pf-id
                          fields)]
    (-> prompt
        (assoc :response response')
        (update :fields
                (partial mapv
                         (partial mk-form-doc-prompt-field-state
                                  fields'))))))

(defn mk-form-doc-state
  [{:keys [responses] :as form-doc}]
  (let [responses' (->> responses
                        (group-by :prompt-id)
                        (ut/fmap first))]
    (update form-doc
            :prompts
            (partial mapv
                     (partial mk-form-doc-prompt-state
                              responses')))))

(defn c-prompt-field
  [{:keys [fname ftype fsubtype list? response] :as prompt-field}]
  ;; TODO support multiple response fields (for where list? = true)
  (def pf1 prompt-field)
  (let [value& (some-> response first :state)]
    [:div
     fname
     [rc/input-text
      :model value&
      :on-change #(reset! value& %)]]))

(defn c-prompt
  [{:keys [prompt descr fields]}]
  [flx/col #{:preposal}
   [:div prompt]
   [:div descr]   
   (for [f fields]
     ^{:key (str "field" (:id f))}
     [c-prompt-field f])])

(defn prep-preposal-form-doc
  [{:keys [id title product to-org to-user from-org from-user doc-id doc-title prompts] :as form-doc}]
  (assoc form-doc
         :doc-title (str "Preposal for "
                         (:pname product)
                         " [ "
                         (:oname from-org)
                         " => "
                         (:oname to-org)
                         " ]")
         :doc-notes ""
         :doc-descr ""
         :doc-dtype "preposal"
         :doc-dsubtype "preposal1"))



(defn c-form-maybe-doc
  [{:keys [id title product from-org from-user doc-id doc-title prompts] :as form-doc}]
  [flx/col #{:preposal}
   [:div (or doc-title title)]
   [flx/row
    [:div (:pname product)]
    [:div (:oname from-org)]
    [:div (:uname from-user)]]
   (for [p prompts]
     ^{:key (str "prompt" (:id p))}
     [c-prompt p])
   [rc/button
    :label "Submit"
    :on-click #(rf/dispatch [:save-form-doc
                             (prep-preposal-form-doc form-doc)])]])

(defn c-page []
  (let [org-id& (rf/subscribe [:org-id])
        prep-reqs& (rf/subscribe [:gql/sub
                                  {:queries
                                   [[:form-docs {:ftype "preposal"
                                                 :to-org-id @org-id&}
                                     [:id :title
                                      :doc-id :doc-title
                                      [:product [:id :pname]]
                                      [:from-org [:id :oname]]
                                      [:from-user [:id :uname]]
                                      [:to-org [:id :oname]]
                                      [:to-user [:id :uname]]
                                      [:prompts
                                       [:id :idstr :prompt :descr
                                        [:fields
                                         [:id :idstr :fname :ftype :fsubtype :list?]]]]
                                      [:responses
                                       [:id :prompt-id :notes
                                        [:fields [:id :pf-id :idx :sval :nval :dval]]]]]]]}])]
    (fn []
      (def preq1 @prep-reqs&)
      [:div
       (for [preq (:form-docs @prep-reqs&)]
         ^{:key (str "form" (:id preq))}
         [docs/c-form-maybe-doc (mk-form-doc-state preq)])])))

#_ (cljs.pprint/pprint preq1)

#_
(cljs.pprint/pprint @(rf/subscribe [:gql/q
                                    {:queries
                                     [[:form-docs {:ftype "preposal"
                                                   :to-org-id @(rf/subscribe [:org-id])}
                                       [:id :title
                                        :doc-id :doc-title
                                        [:product [:id :pname]]
                                        [:from-org [:id :oname]]
                                        [:from-user [:id :uname]]
                                        [:to-org [:id :oname]]
                                        [:to-user [:id :uname]]
                                        [:prompts
                                         [:id :prompt :descr
                                          [:fields
                                           [:id :fname :ftype :fsubtype :list?]]]]
                                        [:responses
                                         [:id :prompt-id :notes
                                          [:fields [:id :idx :sval :nval :dval]]]]]]]}]))
