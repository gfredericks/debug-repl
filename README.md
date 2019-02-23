# debug-repl

Inspired by [an older
library](https://github.com/georgejahad/debug-repl), debug-repl is a
debug repl implemented as an nrepl middleware. It allows you to set
breakpoints that cause the execution to stop and switches the repl to
evaluate code in the context of the breakpoint.

## Usage

Add the dependency and the middleware, e.g. in your `:user` profile:

``` clojure
:dependencies [[com.gfredericks/debug-repl "0.0.10"]]
:repl-options
  {:nrepl-middleware
    [com.gfredericks.debug-repl/wrap-debug-repl]}
```

Then when you're ready to set breakpoints:

``` clojure
user> (require '[com.gfredericks.debug-repl :refer [break! unbreak!]])
nil
user> (let [x 41] (break!))
Hijacking repl for breakpoint: unnamed
user> (inc x)
42
user> (unbreak!)
nil
nil
```

### `unbreak!!`

The `unbreak!!` function (with two `!`s) will cancel all further
breakpoints for the remainder of the original evaluation's scope.

### `catch-break!`

A macro which will break only if the wrapped code throws an exception.

## nREPL compatibility

A major change in the nREPL ecosystem around 2018 resulted in a
confusing situation for dev setups involving a composition of
different nREPL-based tools. Because the maven artefact id change
(`org.clojure/nrepl` -> `nrepl/nrepl`) and the base namespace changed
`clojure.tools.nrepl` -> `nrepl`), it is possible to have both the old
version and the new version of nREPL on the classpath at the same
time, with the possibility that some tools expect to use one and some
tools expect to use the other.

debug-repl assumes the old version of nREPL for versions `0.0.9` and
earlier, and supports both as of version `0.0.10`; if it happens that
both versions are on your classpath, debug-repl will optimistically
pick the newer version; however, likely the best situation is to
figure out how to only have the newer version on your classpath.

## TODOs

- Implement `(throw! ex-fn)` for unbreaking and causing the original
  execution to throw an exception.

## License

Copyright © 2014 Gary Fredericks

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
