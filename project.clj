(defproject open-company-digest "0.1.0-SNAPSHOT"
  :description "OpenCompany Digest Service"
  :url "https://github.com/open-company/open-company-digest"
  :license {
    :name "GNU Affero General Public License Version 3"
    :url "https://www.gnu.org/licenses/agpl-3.0.en.html"
  }

  :min-lein-version "2.9.1"

  ;; JVM memory
  :jvm-opts ^:replace ["-Xms512m" "-Xmx2048m" "-server"]

  :dependencies [
    ;; Lisp on the JVM http://clojure.org/documentation
    [org.clojure/clojure "1.10.3"]
    [org.clojure/tools.cli "1.0.206"] ; commandline parsing https://github.com/clojure/tools.cli
    [http-kit "2.5.3"] ; Web client/server http://http-kit.org/
    ;; Web application library https://github.com/ring-clojure/ring
    [ring/ring-devel "1.9.1"]
    ;; Web application library https://github.com/ring-clojure/ring
    ;; NB: clj-time pulled in by oc.lib
    ;; NB: joda-time pulled in by oc.lib via clj-time
    ;; NB: commons-codec pulled in by oc.lib
    [ring/ring-core "1.9.1" :exclusions [clj-time joda-time]]
    ;; Ring logging https://github.com/nberger/ring-logger-timbre
    ;; NB: com.taoensso/encore pulled in by oc.lib
    ;; NB: com.taoensso/timbre pulled in by oc.lib
    [ring-logger-timbre "0.7.6" :exclusions [com.taoensso/encore com.taoensso/timbre]]
    ;; Web routing https://github.com/weavejester/compojure
    [compojure "1.6.2"]
    ;; HTTP client https://github.com/dakrone/clj-http
    [clj-http "3.12.1"]
    ;; Simple scheduler https://github.com/juxt/tick
    ;; NB: Don't upgrade to +0.4, it's not backward compatible as timeline/clock/schedele namespaces all deprecated
    [tick "0.3.5"]
    ;; Clojure wrapper for Java 8 Date-Time https://github.com/dm3/clojure.java-time
    [clojure.java-time "0.3.2"]

    ;; Library for OC projects https://github.com/open-company/open-company-lib
    ;; ************************************************************************
    ;; ****************** NB: don't go under 0.17.29-alpha60 ******************
    ;; ***************** (JWT schema changes, more info here: *****************
    ;; ******* https://github.com/open-company/open-company-lib/pull/82) ******
    ;; ************************************************************************
    [open-company/lib "0.19.0-alpha5" :exclusions [org.clojure/tools.logging]]
    ;; ************************************************************************
    ;; In addition to common functions, brings in the following common dependencies used by this project:
    ;; defun - Erlang-esque pattern matching for Clojure functions https://github.com/killme2008/defun
    ;; if-let - More than one binding for if/when macros https://github.com/LockedOn/if-let
    ;; Component - Component Lifecycle https://github.com/stuartsierra/component
    ;; RethinkDB - RethinkDB client for Clojure https://github.com/apa512/clj-rethinkdb
    ;; Schema - Data validation https://github.com/Prismatic/schema
    ;; Timbre - Pure Clojure/Script logging library https://github.com/ptaoussanis/timbre
    ;; Amazonica - A comprehensive Clojure client for the AWS API. https://github.com/mcohen01/amazonica
    ;; sentry-clj - Interface to Sentry error reporting https://github.com/getsentry/sentry-clj
    ;; Cheshire - JSON encoding / decoding https://github.com/dakrone/cheshire
    ;; clj-jwt - A Clojure library for JSON Web Token(JWT) https://github.com/liquidz/clj-jwt
    ;; clj-time - Date and time lib https://github.com/clj-time/clj-time
    ;; Environ - Get environment settings from different sources https://github.com/weavejester/environ

    ;; General data-binding functionality for Jackson: works on core streaming API https://github.com/FasterXML/jackson-databind
    [com.fasterxml.jackson.core/jackson-databind "2.12.2"]
  ]

  :plugins [
    [lein-ring "0.12.5"]
    [lein-environ "1.2.0"] ; Get environment settings from different sources https://github.com/weavejester/environ
  ]

  :profiles {
    ;; QA environment and dependencies
    :qa {
      :env {
        :db-name "open_company_auth_qa"
        :hot-reload "false"
        :open-company-auth-passphrase "this_is_a_qa_secret" ; JWT secret
      }
      :dependencies [
        ;; Example-based testing https://github.com/marick/Midje
        ;; NB: clj-time is pulled in by oc.lib
        ;; NB: joda-time is pulled in by oc.lib via clj-time
        ;; NB: commons-codec pulled in by oc.lib
        [midje "1.9.10" :exclusions [joda-time clj-time commons-codec]]
      ]
      :plugins [
        ;; Example-based testing https://github.com/marick/lein-midje
        [lein-midje "3.2.2"]
        ;; Linter https://github.com/jonase/eastwood
        [jonase/eastwood "0.3.14"]
        ;; Static code search for non-idiomatic code https://github.com/jonase/kibit
        [lein-kibit "0.1.8" :exclusions [org.clojure/clojure]]
      ]
    }

    ;; Dev environment and dependencies
    :dev [:qa {
      :env ^:replace {
        :db-name "open_company_auth_dev"
        :hot-reload "true"
        :open-company-auth-passphrase "this_is_a_dev_secret" ; JWT secret
        :aws-access-key-id "CHANGE-ME"
        :aws-secret-access-key "CHANGE-ME"
        :log-level "debug"
      }
      :plugins [
        ;; Check for code smells https://github.com/dakrone/lein-bikeshed
        ;; NB: org.clojure/tools.cli is pulled in by lein-kibit
        [lein-bikeshed "0.5.2" :exclusions [org.clojure/tools.cli]]
        ;; Runs bikeshed, kibit and eastwood https://github.com/itang/lein-checkall
        [lein-checkall "0.1.1"]
        ;; pretty-print the lein project map https://github.com/technomancy/leiningen/tree/master/lein-pprint
        [lein-pprint "1.3.2"]
        ;; Check for outdated dependencies https://github.com/xsc/lein-ancient
        [lein-ancient "1.0.0-RC3"]
        ;; Catch spelling mistakes in docs and docstrings https://github.com/cldwalker/lein-spell
        [lein-spell "0.1.0"]
        ;; Dead code finder https://github.com/venantius/yagni
        [venantius/yagni "0.1.7" :exclusions [org.clojure/clojure]]
      ]
    }]

    ;; Production environment
    :prod {
      :env {
        :env "production"
        :db-name "open_company_auth"
        :hot-reload "false"
      }
    }

    :repl-config [:dev {
      :dependencies [
        ;; Network REPL https://github.com/clojure/tools.nrepl
        [org.clojure/tools.nrepl "0.2.13"]
        ;; Pretty printing in the REPL (aprint ...) https://github.com/razum2um/aprint
        [aprint "0.1.3"]
      ]
      ;; REPL injections
      :injections [
        (require '[aprint.core :refer (aprint ap)]
                 '[clojure.stacktrace :refer (print-stack-trace)]
                 '[clj-time.core :as t]
                 '[clj-time.format :as format]
                 '[clojure.string :as s]
                 '[rethinkdb.query :as r]
                 '[schema.core :as schema]
                 '[java-time :as jt]
                 '[tick.core :as tick]
                 '[tick.timeline :as timeline]
                 '[tick.clock :as clock]
                 '[tick.schedule :as schedule]
                 '[oc.lib.db.common :as db-common]
                 '[oc.lib.schema :as lib-schema]
                 '[oc.lib.jwt :as jwt]
                 '[oc.digest.config :as config]
                 '[oc.digest.schedule :as digest-schedule]
                 '[oc.digest.resources.user :as user-res])
      ]
    }]
  }

  :repl-options {
    :welcome (println (str "\n" (slurp (clojure.java.io/resource "ascii_art.txt")) "\n"
                      "OpenCompany Digest REPL\n"
                      "\nReady to do your bidding... I suggest (go) or (go <port>) or (go-db) as your first command.\n"))
      :init-ns dev
  }

  :aliases{
    "build" ["do" "clean," "deps," "compile"] ; clean and build code
    "dry-run" ["run" "-m" "oc.digest.cli" "--dry"] ; process a day's digest (not actually sent)
    "wet-run" ["run" "-m" "oc.digest.cli"] ; process a day's digest (actually sent)
    "start" ["do" "run" "-m" "oc.digest.app"] ; start a development server
    "start!" ["with-profile" "prod" "do" "start"] ; start a server in production
    "midje!" ["with-profile" "qa" "midje"] ; run all tests
    "test!" ["with-profile" "qa" "do" "clean," "build," "midje"] ; build, init the DB and run all tests
    "autotest" ["with-profile" "qa" "midje" ":autotest"] ; watch for code changes and run affected tests
    "repl" ["with-profile" "+repl-config" "repl"]
    "spell!" ["spell" "-n"] ; check spelling in docs and docstrings
    "bikeshed!" ["bikeshed" "-v" "-m" "120"] ; code check with max line length warning of 120 characters
    "ancient" ["ancient" ":all" ":allow-qualified"] ; check for out of date dependencies
  }

  ;; ----- Code check configuration -----

  :eastwood {
    ;; Disable some linters that are enabled by default
    ;; constant-test - just seems mostly ill-advised, logical constants are useful in something like a `->cond`
    ;; wrong-arity - unfortunate, but it's failing on 3/arity of sqs/send-message
    ;; implicit-dependencies - uhh, just seems dumb
    :exclude-linters [:constant-test :wrong-arity :implicit-dependencies]
    ;; Enable some linters that are disabled by default
    :add-linters [:unused-namespaces :unused-private-vars]

    :config-files ["third-party-macros.clj"]

    ;; Exclude testing namespaces
    :tests-paths ["test"]
    :exclude-namespaces [:test-paths]
  }

  ;; ----- Application -----

  :resource-paths ["resources" ]

  :main oc.digest.app
)
