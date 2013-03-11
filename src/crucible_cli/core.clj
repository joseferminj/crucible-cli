(ns crucible-cli.core
  (:require [clojure.java.io :as io]
            [clojure.data.zip :as dz]
            [clojure.data.zip.xml :as dzx]
            [clojure.data.xml :as dxml]
            [clj-http.client :as client]))


;;; The configuration file is stored in ~/crucible-cli
;;; The format of the configuration file is edn (clojure code)

(defn- load-config-file
  "Loads a file using the clojure reader"
  [filename]
  (with-open [r (io/reader filename)]
    (read (java.io.PushbackReader. r))))

(defn get-pwd-from-keychain
  [basic-auth]
  (let [keychain (com.mcdermottroe.apple.OSXKeychain/getInstance)]
    (try
      (conj basic-auth (.findGenericPassword keychain "crucible-cli" (first basic-auth)))
      (catch com.mcdermottroe.apple.OSXKeychainException e1 (throw (IllegalArgumentException. (str "The item 'crucible-cli' could not be found in the keychain for user " (first basic-auth) " .Open Keychain Access and create a new item")))))))

(defn load-config
  "Loads the configuration file in ~/.crucible-cli"
  []
  (let [filename (str (System/getProperty "user.home") "/" ".crucible-cli")
        config (load-config-file filename)
        auth (:basic-auth config)
        basic-auth (if (= 2 (count auth))
                     auth
                     (get-pwd-from-keychain auth))]
    (assoc config :basic-auth basic-auth)))

(def options (load-config))


;;; Crucible has a REST api. This are the functions for building the
;;; urls for accessing the resources in the REST Api
;;; http://docs.atlassian.com/fisheye-crucible/latest/wadl/crucible.html

(defn- get-url
  [base & coll]
  (let [base-url (str (:host options) "/" base)]
    (clojure.string/join "/" (cons base-url coll))))

(defn- crucible-url
  [& coll]
  (apply get-url (cons "rest-service" coll)))

(defn- fisheye-url
  [& coll]
  (apply get-url (cons "rest-service-fe" coll)))

;;; Utility functions for parsing and extracting information from the
;;; response

(defn xml-parse-str
  "Parses the input xml string and returns a xml-seq"
  [s]
  (xml-seq (clojure.xml/parse (new org.xml.sax.InputSource
                                   (new java.io.StringReader s)))))

(defn- extract-tag
  "Given a xml-seq and a tag, it returns the content of that tag"
  [xml-seq tag]
  (for [x xml-seq
        :when (= tag (:tag x))]
    (first (:content x))))

;;; Several functions that wraps several calls to the API
;;; using clj-http.client

(defn draft-review
  "Given request ins sexp format that describes describing the review, it makes a review
and let it in the draft state. Returns the review id"
  [request]
  (let [request-xml (dxml/emit-str (dxml/sexp-as-element request))

        response (client/post (crucible-url "reviews-v1")
                                (merge options
                                       {:body request-xml
                                        :content-type :xml}))
        response-seq (-> response :body xml-parse-str)
        id (first (extract-tag response-seq :id))]
    id))

(defn add-reviewers
  "Given the review id and a list of comma separated reviewers, it adds the reviewers to the
review"
  [id reviewers]
  (client/post (crucible-url "reviews-v1" id "reviewers")
               (merge options
                      {:body reviewers
                       :content-type :xml})))

(defn- action-review
  "Given action and a review id, it applies the action to the review. A action will do that the review changes its state."
  [action id]
  (client/post (crucible-url "reviews-v1" id "transition")
               (merge options
                      {:query-params {"action" action}
                       :content-type :xml})))

(def ^{:doc "Submits a review in draft state for approval "}
  submit-review
  (partial action-review "action:submitReview"))

(def
  ^{:doc "Given the review id, it approves a review"}
  approve-review
  (partial action-review "action:approveReview"))

(def
  ^{:doc "Given the review id, it abandons a review"}
  abandon-review
  (partial action-review "action:abandonReview"))

(def summarize-review
  ^{:doc "Given the review id, it summarizes a review
 that has been complete by all the reviewers"}
  (partial action-review "action:summarizeReview"))

(def close-review
  ^{:doc "Given the review id, it closes a review in summarize state
 that has been complete by all the reviewers "}
  (partial action-review "action:closeReview"))
                                       
(defn get-changeset
  "Given the changeset id, it returns the details of this changeset from Fisheye"
  [id]
  (let [response (client/get (fisheye-url "revisionData-v1" "changeset" (:repository options) id) options)
        response-seq (xml-parse-str (:body  response))
        comment (first (extract-tag response-seq :comment))
        name (first (clojure.string/split comment #"\n"))]      ; First line in the comment
    {:name name :description comment}))

(defn- make-review-request
  "Creates a request that will be used in the draft operation for creating a new review"
  ([user-id changeset-id] (make-review-request user-id changeset-id {}))
  ([user-id changeset-id {:keys [name description]}]
      [:createReview {}
      [:reviewData {}
       [:allowReviewersToJoin {} "true"]
       [:author {}
        [:userName {} user-id]]
       (when (not (nil? name))
         [:name {} name])
       (when (not (nil? description))
         [:description {} description])
       [:projectKey {} "CR"]
       [:type {} "REVIEW"]]
      [:changesets {}
       [:changesetData {}
        [:id {} changeset-id]]
       [:repository {} (:repository options)]]]))

(defn- match-with-alias
  "Try to match a vector with key with the alias in the configuration (options map). It returns the alias values joined by ','"
  [reviewers]
  (if (vector? reviewers)
    (->> reviewers (map #(% (options :alias))) (clojure.string/join ","))
    reviewers))

(defn create-review
  "Create a review in Crucible. The review will be in STARTED state and the reviewers included"  
  ([changeset-id reviewers] (create-review changeset-id (match-with-alias reviewers) options))
  ([changeset-id reviewers options] (let [user-id (-> options :basic-auth (get 0))]
                                      (create-review user-id changeset-id reviewers options)))
  ([user-id changeset-id reviewers options]
     (let [changeset (get-changeset changeset-id)
           request (make-review-request user-id changeset-id (merge options changeset))
           id (draft-review request)]
       (add-reviewers id reviewers)
       (submit-review id)
       (approve-review id)
       id)))


;;; Visualize the status of the reviews for the given user

(defn get-all-reviews
  "Given a filter, thats indicate the status of the review, it returns all the reviewes in the given status"
  [filter]
  (let [response (client/get (crucible-url "reviews-v1" "filter" filter) options)
        response-seq (-> response :body xml-parse-str)
        ids (extract-tag response-seq :id)
        names (extract-tag response-seq :name)]
    (map #(zipmap [:id :name] [%1 %2]) ids names)))

(defn get-comments-by-review
  "Returns all comments in the review"
  [id]
  (let [response (client/get (crucible-url "reviews-v1" id "comments?render=true") options)
        response-seq (-> response :body xml-parse-str)
        status (extract-tag response-seq :readStatus)
        messages (extract-tag response-seq :message)
        usernames (extract-tag response-seq :userName)]
    (map #(zipmap [:readStatus :message :username] [%1 %2 %3]) status messages usernames)))

(defn- print-reviews
  "Print a review in the output. For each review, the id and the name is shown"
  [coll]
  (doseq [{:keys [id name]} coll]
    (let [comments (get-comments-by-review id)
          unread (filter #(= (% :readStatus) "READ") comments)]
      (println (str "\t(" (count unread) "/" (count comments) ") " id "\t" name)))))

(defn show-status
  "Shows the status of the reviews for the given user"
  []
   (do
   (print "\nTo Review:\n")
   (print-reviews (get-all-reviews "toReview"))

   ;;TODO: Show the number of unread comments
   (print "\nTo Summarize:\n") 
   (print-reviews (get-all-reviews "toSummarize"))

   (print "\nOut For Review:\n")
   (print-reviews (get-all-reviews "outForReview"))))


(defn close-all-summarized 
"Close all the reviews in summarized state"
[]
(doseq [{:keys [id]} (get-all-reviews "toSummarize")]
  (summarize-review id)
  (close-review id)))

(defn open-review
"Open a review in the browser"
[id]
(clojure.java.browse/browse-url  (get-url "cru" id)))
