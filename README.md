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

In addition there's `lambdaisland.makina.test`, meant for setting up and pulling
down a system during (unit, integration) testing.

Makina plays well with
[com.lambdaisland/config](https://github.com/lambdaisland/config)

<!-- installation -->
## Installation

To use the latest release, add the following to your `deps.edn` ([Clojure CLI](https://clojure.org/guides/deps_and_cli))

```clj
com.lambdaisland/makina {:mvn/version "0.3.16"}
```

or add the following to your `project.clj` ([Leiningen](https://leiningen.org/))

```clj
[com.lambdaisland/makina "0.3.16"]
```
<!-- /installation -->

## Quickstart

This shows the pattern of using Makina with lambdaisland/config.

```clj
(ns my.app
  (:refer-clojure :exclude [get])
  (:require
   [lambdaisland.config :as config]
   [lambdaisland.makina.app :as app]))

(def prefix "my-app")

(defonce config (config/create {:prefix prefix}))

(defonce system
  (app/create
   {:prefix prefix
    :data-readers {'config (partial config/get config)}
    :profile (:env config)}))

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

```clj
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

## Deep Dive

Let's take a step back here, and explain Makina from the ground up.

### The Base Layer

Everything starts from a system `config`. The config tells us which components
there are in the system, configuration values passed in when starting each
component, and dependencies between the components. Here's a config for a very
basic web app. It has a handler (a function which takes HTTP requests and
returns HTTP responses), and a server which listens on a port, and which
receives a reference to the handler.

The dependencies are indicated with a "ref" (reference) value. You can use the
`#makina/ref` prefix for this (Makina ships with a `data_readers.cljc`), or you
can construct them explicitly with `lambdaisland.makina.system/->Ref`.

```clj
(def config
  {:http/handler {}
   :http/server {:port 1234
                 :handler (sys/->Ref :http/handler)}})
;;=>
{:http/server {:port 1234, :handler #makina/ref :http/handler}
 :http/handler {}}
```

Now we'll convert that config into a "system" map. It still has one entry for
each component, but now contains a bunch of extra bookkeeping.

```clj
(def system (sys/system config))
;;=>
{:http/server
 {:makina/id     :http/server
  :makina/type   :http/server
  :makina/state  :stopped
  :makina/config {:port    1234
                  :handler #makina/ref :http/handler}
  :makina/value  {:port        1234
                  :handler     #makina/ref :http/handler
                  :makina/type :http/server
                  :makina/id   :http/server}}
 :http/handler
 {:makina/id     :http/handler
  :makina/type   :http/handler
  :makina/state  :stopped
  :makina/config {}
  :makina/value  {:makina/type :http/handler
                  :makina/id   :http/handler}}}
```

The config key has been used both as `id` and `type`, you can explicitly set a
`:makina/type` as well. This is useful if you have multiple components with
identical start/stop logic, but different parameters. You can also use it with
`#makina/refset :some/type`. This way rather than passing one component value to
another, the component receives a collection of all components with the same
type.

The components are currently `:stopped`, we'll try to start them in a moment. We
track the original `config`. We want to be able to inspect the parameters a
component started with, and we may need them to restart this component later on.

Finally there is a `:makina/value`, which initially is just the configuration +
id and type. While `:makina/config` will stay the same over time, the
`:makina/value` will change when we start the components.

### Handlers and Signals

Now let's start the system. For this, Makina needs to know which "handlers" to
use. (Note: "handler" is a bit overloaded in this example, we have the "http
handler" on the one hand, which handles HTTP requests, and we have Makina
"handlers", functions which start or stop components, these are not the same.)

```clj
(defn http-handler [req]
  {:status 200 :body "OK"})

(defn start-web-server [{:keys [port handler]}]
  ;; some ring adapter, e.g. ring.adapter.jetty/run-jetty
  (http/run-http handler {:port port :join? false}))

(defn stop-web-server [server]
  (.close server))


(def handlers
  {:http/handler {:start (constantly http-handler)} 
   ;; or: (constantly http-handler)
   :http/server {:start start-web-server
                 :stop  stop-web-server}})

(def started-system 
  (sys/start system handlers))

;; later
;; (sys/stop started-system handlers)
                 
;;=>
{:http/server
 {:makina/id        :http/server
  :makina/type      :http/server
  :makina/state     :started
  :makina/config    {:port 1234 :handler #makina/ref :http/handler}
  :makina/value     #object["http-ring-adapter"]
  :makina/timestamp #time/instant "2025-11-28T09:29:35.702743635Z"}
 :http/handler
 {:makina/id        :http/handler
  :makina/type      :http/handler
  :makina/state     :started
  :makina/config    {}
  :makina/value     #function[makina.repl-sessions.walkthrough/http-handler]
  :makina/timestamp #time/instant "2025-11-28T09:29:35.702316410Z"}}
```

What happened here? Makina figured out through "topological sorting" that the
handler needed to be started first, followed by the http server. It sends the
`:start` signal to the handler component. To do this, it looks for a "handler"
for this component and signal. In the handlers map passed to `system/start` it
tries in order:

```clj
(get-in handlers [id]) ;; only if (= :start signal)
(get-in handlers [id signal])
(get-in handlers [id :default])
(get-in handlers [type]) ;; only if (= :start signal)
(get-in handlers [type signal])
(get-in handlers [type :default])
(get-in handlers [:default]) ;; only if (= :start signal)
(get-in handlers [:default signal])
(get-in handlers [:default :default])
```

In other words, it first tries to find a handler for this specific component's
id, if it doesn't find one, it looks for a handler based on the component type,
and otherwise it falls back to `:default`

For each it first checks if the the value in the handler map entry is a
function, in that case it uses that as the `:start` handler. So if a component
only has a start handler, you don't need to wrap it in a `{:start handler}` map.

You can also use vars in your handler map, and they will be derefed. This is
quite useful for getting late binding when redefining things (e.g. in a REPL).

So now Makina has found a handler function that can handle the `:start` signal
for each component. This handler function now receives the current
`:makina/value`, the return value is used as the new `:makina/value`, and the
component transitions to `:started`. If the initial value is a map, then Makina
also adds the `:makina/signal` and `:makina/state` keys to it, so what the
handler receives looks like this:

```clj
{:port          1234
 :handler       ,,http handler function,,,
 :makina/type   :http/server
 :makina/id     :http/server
 :makina/signal :start
 :makina/state  :stopped}
```

This allows you to create generic handler functions that can be shared across
components. This is especially useful for fallback `:default` start or stop
handlers, which can e.g. log and throw.

Makina.system needs explicit handlers for each signal, so stopping our
`started-system` will fail, because there is no `:stop` handler for
`:http/handler`. 

```clj
Unhandled clojure.lang.ExceptionInfo
No handler found for [:http/handler :stop]
{:makina/type :http/handler, :makina/signal :stop}
```

It's quite common for components to not have specific shutdown logic, if you
want to ignore missing `:stop` handlers, you can add `:default {:stop identity}`
to your handlers map. When using `makina.app` this is done automatically.

If the handler throws, then instead of ending up in the `:started` state, it
will be in the `:error` state, and it will have a new key `:makina/error`
containing the exception. Its value will be unchanged. In this case Makina will
stop trying to start additional components, but components that have already
started will be left in their `:started` state. So you end up with a system
value where some components are `:started`, some are still `:stopped`, and one
is in the `:error` state. If you call `start` on this system again, it will
retry starting from the failed component. If you call `stop`, it will only stop
the components that had successfully started.

Both `start` and `stop` can optionally take a sequence of keys, to limit their
scope. For instance, you can tell it to only start a single component, as well
as its dependencies.

The system namespace contains a bunch of functions to query a system map, like
`value` (map from id to component value), `component` (a single component
value), `state` (returns the state of a single component), and `error` (returns
the exception, if any)

The `lambdaisland.makina.system` namespace is purely functional, it receives and
returns Clojure data structures and function references. There are no mutable
objects. It is `cljc`, and so can be used in both Clojure and ClojureScript.

### Makina Application (`lambdaisland.makina.app`)

For specific opinionated use cases it can be useful to use the system namespace
directly, but you will likely end up implementing a bunch of additional
bookkeeping, storing the system in something mutable (an atom or var), so you
can keep track of it as it changes. You need to load and hook up handlers, it
might be nice to do that automatically based on naming conventions. You probably
want to set up [tools.namespace](https://github.com/clojure/tools.namespace) for
code reloading. These and other quality of life things are handled by
`lambdaisland.makina.app`, and we generally recommend using this higher-level
API.

```clj
(require '[lambdaisland.makina.app :as app])

(def app (app/create {:prefix "my-app"}))
(def app (app/create {:config {,,,}))
(def app (app/create {:config (fn [] {,,,})))
(def app (app/create {:config "app.edn"))
```

In this case, we start with calling `app/create`, it has a handful of different
options, the most important ones are to help it find your system config map. The
easiest is to give it a "prefix", and it will then locate `<prefix>/system.edn`
on the classpath. This is analogous to the "prefix" argument used by
[lambdaisland.config](https://github.com/lambdaisland/config), so in addition to
`resources/<app-name>/config.edn|dev.edn|prod.edn` you'll have
`resources/<app-name>/system.edn` containing your system config. This mirrors
conventions used in other libraries and projects, and will hopefully become a
recognizable point of entry as Makina gains more traction.

The `system.edn` file is read by Aero, you can pass a `:profile` to `create` to
influence how the file gets read. The default profile is `:default`. You can
pass extra `:data-readers`, a common pattern is to provide a `config` reader for
configuration values coming from separate configuration files or environment
variables.

```clj
(app/create {:prefix "my-app", :data-readers {'config read-config-value}, :profile :prod})
```

Alternatively, you can still pass the config map explicitly, pass a function or
var, an instance of `File` or `URL`, or you can give it a string, which will be
resolved as a resource on the classpath.

The result of `app/create` is an atom, containing an "application" map (hey, we
had to call it something to distinguish it from the "system" map).

```clj
(def app (app/create {:config config}))
;;=>
#<Atom@57e08b0a: 
  {:makina/state :not-loaded,
   :makina/config
   {:http/server {:port 1234, :handler #makina/ref :http/handler},
    :http/handler {}},
   :makina/extra-handlers nil,
   :makina/data-readers
   {ref #function[lambdaisland.makina.system/eval12377/->Ref--12392],
    refset #function[lambdaisland.makina.system/eval12398/->Refset--12413]}}>
```

One of the main benefits of using the app namespace is the auto-loading. When we
call `app/start!`, it will try to load namespaces and resolve handlers based on
the keys used in the config.

So if you have a component named `:my.app.http/handler`, Makina will look for a
var named `#'my.app.http/handler`, or if it can't find it,
`#'my.app.http.handler/component`. It will then construct a handler map, using
these vars directly, and pass that on to `makina.system`, meaning the rules of
`makina.system` apply from there. So the var can contain a function (used as the
start signal), or it can contain a map with signals.

Generally it's a good idea to define each component's logic in its own namespace.

```clj
(ns my.app.http.handler)

(defn component [cfg]
  ,,,start handler, return value,,,)
  
;; OR

(def component 
  {:start (fn [cfg] ,,,)
   :stop (fn [val] ,,,)})
```

You can pass a `:ns-prefix` to `create`, so in the example above, we could say
`{:ns-prefix "my.app"}`, and then simply use `:http/handler` as the component
id, and Makina will know to look for `my.app.http.handler`.

You can also pass additional `:handlers`, same as the handlers passed to
`system/start`, if a handler is passed explicitly for a given type or id, then
this short circuits the auto-loading.

Now we can `start!` this app! The return value of `start!` is the system value
(component keys with their associated current value). If you look inside the
atom, you can see this application is now `:started`, and has the system value
inside it.

```clj
(app/start! app)
;;=>
{:http/server #object[,,,]
 :http/handler #function[my.app.http.handler/http-handler]}

@app
;;=>
{:makina/state :started
 ;; ,,, data-readers, extra-handlers, config ,,,
 :makina/system
 {:http/server
  {:makina/id     :http/server
   :makina/state  :started
   :makina/config {:port 1234 :handler #makina/ref :http/handler}
   :makina/value  #object[,,,]
   :makina/type   :http/server}
  :http/handler
  {:makina/id     :http/handler
   :makina/state  :started
   :makina/config {}
   :makina/value  #function[my.app.http.handler/http-handler]
   :makina/type   :http/handler}}
 :makina/handlers
 {:default      {:stop #function[clojure.core/identity]}
  :http/handler #'my.app.http.handler/component
  :http/server  #'my.app.http.server/component}}
```

Now we can actually do a full system reload, (See [Reloaded
Workflow](https://gaiwan.co/wiki/ReloadableComponentSystem.md#alessandra-sierra-s-component-library-and-reloaded-workflow-article)
for background).

This shuts down the system, uses
[tools.namespace](https://github.com/clojure/tools.namespace), to unload and
then reload all namespaces, ensuring a completely fresh REPL state, before
starting the system again. This is done with `app/refresh` (reload namespaces
that have changed since last refresh), or `refresh-all` (reload ALL namespaces).
Note that you need to add the dependency to tools.namespace in your own project,
it is not an explicit dependency of Makina, to avoid it blowing up the size of
production artifacts, it's recommended to only add it in development.

`app/refresh` and `app/refresh-all` work in the same way, you pass them the name
of the var that contains your app, as a fully qualified symbol (the backtick '`'
is your friend). The reason we need to pass it as a symbol is that as part of
this process the namespace containing the app will get reloaded as well, so we
need to be able to find the new "app" var once the reload is done.

```clj
(app/refresh `app)
```

The rest of the app API mimics system, there's `start!`, `stop!`, `value`,
`component`, `error`. To inspect your system, there's `print-table`. It can be
nice to call this as part of your application's startup logic so you can
visually inspect that everything is up and running.

If a start handler throws, the app will transition to an `:error` state, and the
exception gets rethrown as well. This is different from the `system` behavior,
where the system containing the error is returned but no exception is thrown. In
this case since we have the atom we can stash away the failed system before we
throw, so you can inspect it, shut it down, or try again to continue the startup
process.

### Testing (`lambdaisland.makina.test`)

When writing tests, you often need at least part of the system to be available,
but you don't necessarily need or want all of it. Especially components that
themselves rely on external services like databases might be best avoided or
mocked out. Part of the reason we created Makina is because we wanted a more
elegant way to express this, overriding the startup/teardown logic of components
on multiple levels.

The `lambdaisland.makina.test` namespace builds on `system` and `app`. It
contains a dynamic var called `*app*` which can be bound to a started app value
(so without the wrapping atom) during a test or test run. There are few ways to
achieve this, depending on your needs.

The most basic version is the `with-app` macro, it takes a settings map just
like `lambdaisland.makina.app/create`, i.e. `:prefix`, `:config`, `:profile`,
etc. It will load and start the app, bind it to `*app*`, run the code inside the
body, and then tear down the app again. There's an additional key, `:keys`, to
control which components get started. The configuration is loaded using Aero
with the `:test` profile, unless a profile is specified explicitly.

Note that you can pass in `:handlers`, which will replace the
default/auto-loaded handlers. Perfect for mocking out components during testing.

```clj
(require '[lambdaisland.makina.test :as makina-test])

(makina-test/with-app {:prefix "my-app" :keys [:http/handler] :handlers {...}}
  ,,, code that relies on makina-test/*app* ,,,
  )
```

Building on that is `make-fixture-fn`, like its name suggest, the return value
of `make-fixture-fn` is a function that can be used with
`clojure.test/use-fixture`.

```clj
(def wrap-app (makina-test/make-fixture-fn {:prefix "my-app" :handlers {}))

(t/use-fixture :once (wrap-app [:keys :to :start]))
;; OR
(t/use-fixture :once (wrap-app {:keys [:keys :to :start] :handlers {}}))
```

What's nice about `make-fixture-fn` is that you can have a utility namespace
where you set up sensible defaults, like where to load the system config from,
and test-specific handler overrides. You can also set up accessor functions here
for components your test code might want to reference directly. Then in each
test namespace, you declare which components are needed (as a vector/set, or
with `{:keys ,,,}`), and if necessary additional overrides.

This does mean that for each test namespace your system gets started and
stopped. Depending on your start/stop logic, this can take anything from
milliseconds to minutes. If you have a fairly heavy system, you may prefer to
start/stop it only once for the entire test run. In that case, you can use
[Kaocha](https://github.com/lambdaisland/kaocha)'s Hooks plugin with the
`lambdaisland.makina.test/wrap-run` function.

```clj
;; tests.edn
#kaocha/v1
{:plugins [:hooks]
 :kaocha.hooks/wrap-run [lambdaisland.makina.test/wrap-run]
 :makina/settings {:prefix "my-app"}
 ;; OR
 :makina/settings my-app.config.makina-opts}
```

Now the system will be started and stopped once, and bound to `*app*` for the
duration of the test run. The settings in this case can be set directly in
tests.edn, or you can provide the name of a var that contains either the
settings map, or a function that returns the settings map.

While working on tests, you will likely want to evaluate bits of test code from
the REPL or your editor. If these reference `*app*` then that's a problem, since
`*app*` is only bound during actual test runs (when using the fixture or hooks
approach). For this scenario there is `lambdaisland.makina.test/start!`. This
takes a similar settings map as `with-app` or `make-fixture-fn`, but permantly
binds `*app*`.

### Making use the Aero profile

The `system.edn` file is loaded with Aero, which means you get a bunch of handy
reader literals, like `#env`, `#or`, and `#profile`. This last provides an
interesting alternative to overriding handlers explicitly during tests.

Say that by default you store HTTP session information in Redis, but during
integration tests you just want to use an in-memory store. You can do something
like this in `system.edn`

Effectively swapping out the redis component for an in-memory component.

```clj
{:session-store
 {:makina/type #profile {:default :redis-store
                         :test :memory-store}}
```

As is often the case, there is more than one way to do it, the choice is yours.

## Further reading

- [Blog post with rationale](https://arnebrasseur.net/2025-02-06-open-source-diary.html)
- [Follow-up blog post](https://arnebrasseur.net/2024-02-09-open-source-diary.html)
- [Tea garden: Reloadable Component System](https://gaiwan.co/wiki/ReloadableComponentSystem.md)

## Exemplars

These open source applications are built with Makina:

- [Oak IAM](https://git.gaiwan.co/Gaiwan/Oak)

Built something cool with Makina? Send us a PR to add it to this list!


### How to organize the component source? ns-prefix

Typically, we write our `resources/${app-name-as-prefix}/system.edn` like

```
{:category/first-comp {:some "settings ..."}
 :category/second-comp {:some "settings ..."}}
```

A typical `category/first-comp` may be `system/http`.

and then, we want to organize our component source in a way like

```
src/org/my_org/my_app/category/first_comp.clj
```

In above case, we need to let **makina** know the namespace as `"org.my_org.my_app"` 

We can tell makina by passing `ns-prefix`:

```
(app/create
   {:prefix ${app-name-as-prefix}
    :ns-prefix "org.my_org.my_app"
    :data-readers {'config get}})
```

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
