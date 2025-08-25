# makina

<!-- badges -->
[![cljdoc badge](https://cljdoc.org/badge/com.lambdaisland/makina)](https://cljdoc.org/d/com.lambdaisland/makina) [![Clojars Project](https://img.shields.io/clojars/v/com.lambdaisland/makina.svg)](https://clojars.org/com.lambdaisland/makina)
<!-- /badges -->

System/component lifecycle management

## Overview

Makina is a System Lifecycle Manager for Clojure. You define the components your
application is made of, and the dependencies between them. And Makina takes care
of starting and stopping them in the right order, and plugging them together.

Makina has two APIs, `lambdaisland.makina.system` is the base API. It is
unopionated and flexible, it implements the basic mechanisms for system
lifecycle management.

`lambdaisland.makina.app` is a
[policy](https://lambdaisland.com/blog/2022-03-10-mechanism-vs-policy)
namespace, it's opinionated, it assumes you follow specific conventions, and
integrates with [tools.namespace](https://github.com/clojure/tools.namespace)
for smart code reloading. It uses [Aero](https://github.com/juxt/aero) for
loading config files.

Makina plays well with
[com.lambdaisland/config](https://github.com/lambdaisland/config)

## Quickstart

```clojure
(ns my.app
  (:refer-clojure :exclude [get])
  (:require
   [lambdaisland.config :as config]
   [lambdaisland.makina.app :as app]))

(def prefix "my-app")

(defonce config (config/create {:prefix prefix}))

(def get (partial config/get config))

(defonce system
  (app/create
   {:prefix prefix
    :data-readers {'config get}}))

(def load! (partial app/load! system))
(def start! (partial app/start! system))
(def stop! (partial app/stop! system))
(def refresh (partial app/refresh `system))
(def refresh-all (partial app/refresh-all `system))

(comment
  system
  (start!)
  (stop!)
  (refresh))
```

Then add a `resources/my-app/system.edn` (make sure `"resources"` is on the
classpath).

```clj
{:my.app/first-component {:some "settings"}
 :my.app/second-component {:dep #makina/ref :my.app/first-component}}
```

And define handlers for your components. You have two options for this. For
instance, for `:my.app/first-component` , you either define a var named
`#'my.app/first-component`, or `#'my.app.first-component/component`

```clj
(ns my.app.first-component)

(def component 
 {:start (fn [opts] ,,,)
  :stop  (fn [v] ,,,)}
```

The `start` function receives configuration for that component from `system.edn`, plus some additional keys map

```clojure
{:makina/id :my.app/first-component
 :makina/type :my.app/first-component
 :makina/state :stopped
 :makina/signal :start}
```

`:stop` receives the value returned from `:start`. If it's a map, then
`:makina/id`, `:makina/type`, `:makina/state`, and `:makina/signal` are added to
the map before calling `:stop`.

These additional keys allow you to write more generic handlers that can deal
with multiple types of components.

## Further reading

- [Blog post with rationale](https://arnebrasseur.net/2025-02-06-open-source-diary.html)
- [Follow-up blog post](https://arnebrasseur.net/2024-02-09-open-source-diary.html)
- [Tea garden: Reloadable Component System](https://gaiwan.co/wiki/ReloadableComponentSystem.md)

<!-- installation -->
## Installation

To use the latest release, add the following to your `deps.edn` ([Clojure CLI](https://clojure.org/guides/deps_and_cli))

```
com.lambdaisland/makina {:mvn/version "0.2.12"}
```

or add the following to your `project.clj` ([Leiningen](https://leiningen.org/))

```
[com.lambdaisland/makina "0.2.12"]
```
<!-- /installation -->

## Rationale

## Usage

<!-- opencollective -->
## Lambda Island Open Source

Thank you! makina is made possible thanks to our generous backers. [Become a
backer on OpenCollective](https://opencollective.com/lambda-island) so that we
can continue to make makina better.

<a href="https://opencollective.com/lambda-island">
<img src="https://opencollective.com/lambda-island/organizations.svg?avatarHeight=46&width=800&button=false">
<img src="https://opencollective.com/lambda-island/individuals.svg?avatarHeight=46&width=800&button=false">
</a>
<img align="left" src="https://github.com/lambdaisland/open-source/raw/master/artwork/lighthouse_readme.png">

&nbsp;

makina is part of a growing collection of quality Clojure libraries created and maintained
by the fine folks at [Gaiwan](https://gaiwan.co).

Pay it forward by [becoming a backer on our OpenCollective](http://opencollective.com/lambda-island),
so that we continue to enjoy a thriving Clojure ecosystem.

You can find an overview of all our different projects at [lambdaisland/open-source](https://github.com/lambdaisland/open-source).

&nbsp;

&nbsp;
<!-- /opencollective -->

<!-- contributing -->
## Contributing

We warmly welcome patches to makina. Please keep in mind the following:

- adhere to the [LambdaIsland Clojure Style Guide](https://nextjournal.com/lambdaisland/clojure-style-guide)
- write patches that solve a problem 
- start by stating the problem, then supply a minimal solution `*`
- by contributing you agree to license your contributions as MPL 2.0
- don't break the contract with downstream consumers `**`
- don't break the tests

We would very much appreciate it if you also

- update the CHANGELOG and README
- add tests for new functionality

We recommend opening an issue first, before opening a pull request. That way we
can make sure we agree what the problem is, and discuss how best to solve it.
This is especially true if you add new dependencies, or significantly increase
the API surface. In cases like these we need to decide if these changes are in
line with the project's goals.

`*` This goes for features too, a feature needs to solve a problem. State the problem it solves first, only then move on to solving it.

`**` Projects that have a version that starts with `0.` may still see breaking changes, although we also consider the level of community adoption. The more widespread a project is, the less likely we're willing to introduce breakage. See [LambdaIsland-flavored Versioning](https://github.com/lambdaisland/open-source#lambdaisland-flavored-versioning) for more info.
<!-- /contributing -->

<!-- license -->
## License

Copyright &copy; 2024-2025 Arne Brasseur and Contributors

Licensed under the term of the Mozilla Public License 2.0, see LICENSE.
<!-- /license -->
