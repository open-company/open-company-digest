# [OpenCompany](https://github.com/open-company) Digest Service

[![MPL License](http://img.shields.io/badge/license-MPL-blue.svg?style=flat)](https://www.mozilla.org/MPL/2.0/)
[![Build Status](https://travis-ci.org/open-company/open-company-digest.svg)](https://travis-ci.org/open-company/open-company-digest)
[![Dependencies Status](https://versions.deps.co/open-company/open-company-digest/status.svg)](https://versions.deps.co/open-company/open-company-digest)

## Background

> The single most important ingredient in the recipe for success is transparency because transparency builds trust.

> -- [Denise Morrison](https://en.wikipedia.org/wiki/Denise_Morrison)

Companies struggle to keep everyone on the same page. People are hyper-connected in the moment but still don’t know what’s happening across the company. Employees and investors, co-founders and execs, customers and community, they all want more transparency. The solution is surprisingly simple and effective - great company updates that build transparency and alignment.

With that in mind we designed the [Carrot](https://carrot.io/) software-as-a-service application, powered by the open source [OpenCompany platform](https://github.com/open-company). The product design is based on three principles:

1. It has to be easy or no one will play.
2. The "big picture" should always be visible.
3. Alignment is valuable beyond the team, too.

Carrot simplifies how key business information is shared with stakeholders to create alignment. When information about growth, finances, ownership and challenges is shared transparently, it inspires trust, new ideas and new levels of stakeholder engagement. Carrot makes it easy for founders to engage with employees and investors, creating alignment for everyone.

[Carrot](https://carrot.io/) is GitHub for the rest of your company.

Transparency expectations are changing. Organizations need to change as well if they are going to attract and retain savvy employees and investors. Just as open source changed the way we build software, transparency changes how we build successful companies with information that is open, interactive, and always accessible. Carrot turns transparency into a competitive advantage.

To get started, head to: [Carrot](https://carrot.io/)


## Overview

TBD.

## Local Setup

Prospective users of [Carrot](https://carrot.io/) should get started by going to [Carrot.io](https://carrot.io/). The following local setup is **for developers** wanting to work on the OpenCompany Digest Service.

Most of the dependencies are internal, meaning [Leiningen](https://github.com/technomancy/leiningen) will handle getting them for you. There are a few exceptions:

* [Java 8](http://www.oracle.com/technetwork/java/javase/downloads/index.html) - a Java 8 JRE is needed to run Clojure
* [Leiningen](https://github.com/technomancy/leiningen) v2.7.1+ - Clojure's build and dependency management tool
* [RethinkDB](http://rethinkdb.com/) v2.3.5+ - a multi-modal (document, key/value, relational) open source NoSQL database

#### Java

Chances are your system already has Java 8+ installed. You can verify this with:

```console
java -version
```

If you do not have Java 8+ [download it](http://www.oracle.com/technetwork/java/javase/downloads/index.html) and follow the installation instructions.

#### Leiningen

Leiningen is easy to install:

1. Download the latest [lein script from the stable branch](https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein).
1. Place it somewhere that's on your $PATH (`env | grep PATH`). `/usr/local/bin` is a good choice if it is on your PATH.
1. Set it to be executable. `chmod 755 /usr/local/bin/lein`
1. Run it: `lein` This will finish the installation.

Then let Leiningen install the rest of the dependencies:

```console
git clone https://github.com/open-company/open-company-digest.git
cd open-company-digest
lein deps
```

#### RethinkDB

RethinkDB is easy to install with official and community supported packages for most operating systems.

##### RethinkDB for Mac OS X via Brew

Assuming you are running Mac OS X and are a [Homebrew](http://mxcl.github.com/homebrew/) user, use brew to install RethinkDB:

```console
brew update && brew install rethinkdb
```

If you already have RethinkDB installed via brew, check the version:

```console
rethinkdb -v
```

If it's older, then upgrade it with:

```console
brew update && brew upgrade rethinkdb && brew services restart rethinkdb
```


Follow the instructions provided by brew to run RethinkDB every time at login:

```console
ln -sfv /usr/local/opt/rethinkdb/*.plist ~/Library/LaunchAgents
```

And to run RethinkDB now:

```console
launchctl load ~/Library/LaunchAgents/homebrew.mxcl.rethinkdb.plist
```

Verify you can access the RethinkDB admin console:

```console
open http://localhost:8080/
```

After installing with brew:

* Your RethinkDB binary will be at `/usr/local/bin/rethinkdb`
* Your RethinkDB data directory will be at `/usr/local/var/rethinkdb`
* Your RethinkDB log will be at `/usr/local/var/log/rethinkdb/rethinkdb.log`
* Your RethinkDB launchd file will be at `~/Library/LaunchAgents/homebrew.mxcl.rethinkdb.plist`

##### RethinkDB for Mac OS X (Binary Package)

If you don't use brew, there is a binary installer package available for Mac OS X from the [Mac download page](http://rethinkdb.com/docs/install/osx/).

After downloading the disk image, mounting it (double click) and running the rethinkdb.pkg installer, you need to manually create the data directory:

```console
sudo mkdir -p /Library/RethinkDB
sudo chown <your-own-user-id> /Library/RethinkDB
mkdir /Library/RethinkDB/data
```

And you will need to manually create the launchd config file to run RethinkDB every time at login. From within this repo run:

```console
cp ./opt/com.rethinkdb.server.plist ~/Library/LaunchAgents/com.rethinkdb.server.plist
```

And to run RethinkDB now:

```console
launchctl load ~/Library/LaunchAgents/com.rethinkdb.server.plist
```

Verify you can access the RethinkDB admin console:

```console
open http://localhost:8080/
```

After installing with the binary package:

* Your RethinkDB binary will be at `/usr/local/bin/rethinkdb`
* Your RethinkDB data directory will be at `/Library/RethinkDB/data`
* Your RethinkDB log will be at `/var/log/rethinkdb.log`
* Your RethinkDB launchd file will be at `~/Library/LaunchAgents/com.rethinkdb.server.plist`


##### RethinkDB for Linux

If you run Linux on your development environment (good for you, hardcore!) you can get a package for you distribution or compile from source. Details are on the [installation page](http://rethinkdb.com/docs/install/).

##### RethinkDB for Windows

RethinkDB [isn't supported on Windows](https://github.com/rethinkdb/rethinkdb/issues/1100) directly. If you are stuck on Windows, you can run Linux in a virtualized environment to host RethinkDB.

#### Required Secrets

A secret is shared between the [Storage service](https://github.com/open-company/open-company-storage) and the Digest service for creating and validating [JSON Web Tokens](https://jwt.io/).

Make sure you update the `CHANGE-ME` items in the section of the `project.clj` that looks like this to contain your actual JWT, Slack, and AWS secrets:

```clojure
;; Dev environment and dependencies
:dev [:qa {
  :env ^:replace {
    :db-name "open_company_auth_dev"
    :open-company-auth-passphrase "this_is_a_dev_secret" ; JWT secret
    :hot-reload "true" ; reload code when changed on the file system
    :aws-access-key-id "CHANGE-ME"
    :aws-secret-access-key "CHANGE-ME"
    :log-level "debug"
  }
```

You can also override these settings with environmental variables in the form of `OPEN_COMPANY_AUTH_PASSPHRASE` and
`AWS_ACCESS_KEY_ID`, etc. Use environmental variables to provide production secrets when running in production.


## Usage

Prospective users of [Carrot](https://carrot.io/) should get started by going to [Carrot.io](https://carrot.io/). The following usage is **for developers** wanting to work on the OpenCompany Digest Service.

**Make sure you've updated `project.clj` as described above.**

To start a production instance:

```console
lein start!
```

Or to start a development instance:

```console
lein start
```

Open your browser to [http://localhost:3008/ping](http://localhost:3008/ping) and check that it's working.

To clean all compiled files:

```console
lein clean
```

To create a production build run:

```console
lein build
```

To initiate a test of a daily or weekly digest run (nothing will be sent out):

```console
lein dry-run-daily
```

-or-

```console
lein dry-run-weekly
```

To initiate a daily or weekly digest run:

```console
lein run-daily
```

-or-

```console
lein run-weekly
```


## Technical Design

The Digest Service is composed of 4 main responsibilities:

- Send daily email digests to users
- Send daily Slack digests to users
- Send weekly email digests to users
- Send weekly Slack digests to users

The Digest Service runs on a daily and weekly schedule, or a digest run can be invoked manually. It uses read-only access to the RethinkDB database of the [Authorization Service](https://github.com/open-company/open-company-auth) to enumerate users that have requested a digest. For each of those users, a digest request is generated for each organization the user has access to (most users just have 1 organization) by generating a [JSON Web Token](https://jwt.io/) for that user, using the JWToken to request a date range of activity for the particular user and the particular org from the REST API of [Storage Service](https://github.com/open-company/open-company-storage), and then generating a JSON digest request, sent via [SQS](https://aws.amazon.com/sqs/), to either the [Email Service](https://github.com/open-company/open-company-email) or [Bot Service](https://github.com/open-company/open-company-bot).


## Testing

Tests are run in continuous integration of the `master` and `mainline` branches on [Travis CI](https://travis-ci.org/open-company/open-company-digest):

[![Build Status](http://img.shields.io/travis/open-company/open-company-digest.svg?style=flat)](https://travis-ci.org/open-company/open-company-digest)

To run the tests locally:

```console
lein test!
```


## Participation

Please note that this project is released with a [Contributor Code of Conduct](https://github.com/open-company/open-company-digest/blob/mainline/CODE-OF-CONDUCT.md). By participating in this project you agree to abide by its terms.


## License

Distributed under the [Mozilla Public License v2.0](http://www.mozilla.org/MPL/2.0/).

Copyright © 2017-2018 OpenCompany, LLC