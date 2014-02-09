# crucible-cli

A Clojure library for using Crucible API from the REPL instead of
using the web interface

## Installation

Clone the project and starts the REPL with leiningen
```
lein repl
```

Use it in the REPL

```clojure
(use 'crucible-cli.core)
```

Before starting to execute commands with need to configure the basic
credentials and the crucible server address

Create the file  `~/.crucible-cli` and fill the following information:

```clojure
{:basic-auth ["username" "password"]
 :host    "http://crucible.com"
 :repository "repositoryName"}
```
## Usage

### Create a review

```clojure
(create-review  "31743be" "baracus,templeton.peck,murdock")
```

Creates a review for the changeset 31743be where the users
  baracus,templeton.peck and murdock will be the reviewers. The description
  of the review will be automatically retrieved from the comment of
  the changeSet

### Show the status of the reviews

```clojure
(show-status)

To Review:
    CR-0055 Adds feature 3

To Summarize:
	CR-0029 Adds feature 1

Out For Review:
	CR-0023	Fixes tests
	CR-0050	Adds feature 2
	CR-0060	Removes useless code
```

### Summarize all the reviews in state "toSummarize"
```clojure
(let [r (get-all-reviews "toSummarize")
      ids (map :id r)]
  (map summarize-review ids))
```
### Future
