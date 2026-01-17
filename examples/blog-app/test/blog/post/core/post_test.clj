(ns blog.post.core.post-test
  "Unit tests for post business logic.
   
   These tests demonstrate the FC/IS pattern benefit:
   - Pure functions are easy to test
   - No mocking required
   - Fast execution"
  (:require [clojure.test :refer [deftest testing is]]
            [blog.post.core.post :as post])
  (:import [java.time Instant]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def test-instant (Instant/parse "2026-01-17T12:00:00Z"))

(def sample-input
  {:title "Hello World!"
   :content "This is my first blog post. It contains multiple paragraphs.\n\nThis is the second paragraph."
   :excerpt "A brief intro"})

;; =============================================================================
;; Slug Generation Tests
;; =============================================================================

(deftest generate-slug-test
  (testing "converts title to lowercase"
    (is (= "hello-world" (post/generate-slug "Hello World"))))
  
  (testing "replaces spaces with hyphens"
    (is (= "hello-world" (post/generate-slug "hello world"))))
  
  (testing "removes special characters"
    (is (= "hello-world" (post/generate-slug "Hello World!")))
    (is (= "special-characters" (post/generate-slug "Special @#$ Characters!"))))
  
  (testing "collapses multiple spaces/hyphens"
    (is (= "multiple-spaces" (post/generate-slug "Multiple   Spaces")))
    (is (= "multiple-hyphens" (post/generate-slug "Multiple---Hyphens"))))
  
  (testing "trims leading/trailing spaces"
    (is (= "trimmed" (post/generate-slug "  Trimmed  ")))))

(deftest ensure-unique-slug-test
  (testing "returns base slug when unique"
    (is (= "hello-world" (post/ensure-unique-slug "hello-world" #{}))))
  
  (testing "appends number when slug exists"
    (is (= "hello-world-2" (post/ensure-unique-slug "hello-world" #{"hello-world"}))))
  
  (testing "increments number until unique"
    (is (= "hello-world-4" (post/ensure-unique-slug "hello-world" 
                                                     #{"hello-world" "hello-world-2" "hello-world-3"})))))

;; =============================================================================
;; Post Creation Tests
;; =============================================================================

(deftest create-post-test
  (testing "creates post with required fields"
    (let [author-id (random-uuid)
          post (post/create-post sample-input author-id test-instant)]
      (is (uuid? (:id post)))
      (is (= author-id (:author-id post)))
      (is (= "Hello World!" (:title post)))
      (is (= "hello-world" (:slug post)))
      (is (= (:content sample-input) (:content post)))
      (is (= "A brief intro" (:excerpt post)))))
  
  (testing "defaults to unpublished"
    (let [post (post/create-post sample-input nil test-instant)]
      (is (false? (:published post)))
      (is (nil? (:published-at post)))))
  
  (testing "sets published-at when published is true"
    (let [input (assoc sample-input :published true)
          post (post/create-post input nil test-instant)]
      (is (true? (:published post)))
      (is (= test-instant (:published-at post)))))
  
  (testing "auto-generates excerpt from long content"
    (let [long-content (apply str (repeat 300 "x"))
          input {:title "Test" :content long-content}
          post (post/create-post input nil test-instant)]
      (is (some? (:excerpt post)))
      (is (<= (count (:excerpt post)) 203))))) ; 200 + "..."

;; =============================================================================
;; Post Update Tests
;; =============================================================================

(deftest update-post-test
  (let [original {:id (random-uuid)
                  :title "Original"
                  :slug "original"
                  :content "Original content"
                  :excerpt nil
                  :published false
                  :published-at nil
                  :created-at test-instant
                  :updated-at test-instant}
        later (Instant/parse "2026-01-17T14:00:00Z")]
    
    (testing "updates title and regenerates slug"
      (let [updated (post/update-post original {:title "New Title"} later)]
        (is (= "New Title" (:title updated)))
        (is (= "new-title" (:slug updated)))
        (is (= later (:updated-at updated)))))
    
    (testing "updates content"
      (let [updated (post/update-post original {:content "New content"} later)]
        (is (= "New content" (:content updated)))))
    
    (testing "updates excerpt including to nil"
      (let [with-excerpt (post/update-post original {:excerpt "An excerpt"} later)
            cleared (post/update-post with-excerpt {:excerpt nil} later)]
        (is (= "An excerpt" (:excerpt with-excerpt)))
        (is (nil? (:excerpt cleared)))))
    
    (testing "sets published-at when publishing"
      (let [published (post/update-post original {:published true} later)]
        (is (true? (:published published)))
        (is (= later (:published-at published)))))
    
    (testing "clears published-at when unpublishing"
      (let [was-published (assoc original :published true :published-at test-instant)
            unpublished (post/update-post was-published {:published false} later)]
        (is (false? (:published unpublished)))
        (is (nil? (:published-at unpublished)))))))

;; =============================================================================
;; Publishing Logic Tests
;; =============================================================================

(deftest publish-post-test
  (let [draft {:id (random-uuid)
               :published false
               :published-at nil}]
    
    (testing "publishes draft post"
      (let [result (post/publish-post draft test-instant)]
        (is (contains? result :ok))
        (is (true? (get-in result [:ok :published])))
        (is (= test-instant (get-in result [:ok :published-at])))))
    
    (testing "returns error for already published post"
      (let [published (assoc draft :published true :published-at test-instant)
            result (post/publish-post published test-instant)]
        (is (= :already-published (:error result)))))))

(deftest unpublish-post-test
  (let [published {:id (random-uuid)
                   :published true
                   :published-at test-instant}]
    
    (testing "unpublishes post"
      (let [result (post/unpublish-post published test-instant)]
        (is (contains? result :ok))
        (is (false? (get-in result [:ok :published])))
        (is (nil? (get-in result [:ok :published-at])))))))

;; =============================================================================
;; Helper Function Tests
;; =============================================================================

(deftest draft?-test
  (is (true? (post/draft? {:published false})))
  (is (false? (post/draft? {:published true}))))

(deftest published?-test
  (is (true? (post/published? {:published true})))
  (is (false? (post/published? {:published false}))))

(deftest author?-test
  (let [user-id (random-uuid)
        post {:author-id user-id}]
    (is (true? (post/author? post user-id)))
    (is (false? (post/author? post (random-uuid))))))

(deftest word-count-test
  (is (= 5 (post/word-count "one two three four five")))
  (is (= 1 (post/word-count "single")))
  (is (nil? (post/word-count nil))))

(deftest reading-time-test
  (testing "returns 1 minute for short content"
    (is (= 1 (post/reading-time "Short content"))))
  
  (testing "calculates based on 200 wpm"
    (let [words-400 (apply str (interpose " " (repeat 400 "word")))]
      (is (= 2 (post/reading-time words-400))))))

(deftest generate-excerpt-test
  (testing "returns content as-is if short"
    (is (= "Short" (post/generate-excerpt "Short"))))
  
  (testing "truncates long content with ellipsis"
    (let [long-content (apply str (repeat 300 "x"))
          excerpt (post/generate-excerpt long-content)]
      (is (clojure.string/ends-with? excerpt "..."))
      (is (<= (count excerpt) 203)))))
