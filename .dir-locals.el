;;; Directory Local Variables
;;; For more information see (info "(emacs) Directory Variables")

((clojure-mode
  (cider-preferred-build-tool . clojure-cli)
  (cider-clojure-cli-aliases . ":dev:db")
  (cider-ns-refresh-before-fn . "integrant.repl/halt")
  (cider-ns-refresh-after-fn . "integrant.repl/go")
  (cider-default-cljs-repl . nil))
 (clojurescript-mode
  (cider-preferred-build-tool . clojure-cli)))
