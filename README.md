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

### async

There's also an async version of the debug-repl, for use in contexts where you cannot call the function directly (e.g. in a web request or worker queue).

``` clojure
user> (require '[com.gfredericks.debug-repl.async :refer [break! unbreak! wait-for-break]])
nil
user> (defn handler [req] (break!) {:status 200})
#'handler
user> (def srv (future (run-jetty handler {:port 8080})))
2019-06-22 13:29:31.400:INFO:oejs.Server:clojure-agent-send-off-pool-0: jetty-9.4.12.v20180830; built: 2018-08-30T13:59:14.071Z; git: 27208684755d94a92186989f695db2d7b21ebc51; jvm 1.8.0_202-b08
#'user/srv
user> (wait-for-breaks) ; At this point I opened my browser to http://localhost:8080
Hijacking repl for breakpoint: unnamed
user> req
{:ssl-client-cert nil, :protocol "HTTP/1.1", :remote-addr "127.0.0.1", :headers {"host" "localhost:8080", "user-agent" "Mozilla/5.0 (X11; Linux x86_64; rv:67.0) Gecko/20100101 Firefox/67.0", "cookie" "__stripe_mid=d277c210-219f-4aa7-8fe7-b23715befe83", "connection" "keep-alive", "upgrade-insecure-requests" "1", "accept" "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", "accept-language" "en-GB,en-US;q=0.7,en;q=0.3", "accept-encoding" "gzip, deflate", "dnt" "1", "cache-control" "max-age=0"}, :server-port 8080, :content-length nil, :content-type nil, :character-encoding nil, :uri "/", :server-name "localhost", :query-string nil, :body #object[org.eclipse.jetty.server.HttpInputOverHTTP 0x6710feb8 "HttpInputOverHTTP@6710feb8[c=0,q=0,[0]=null,s=STREAM]"], :scheme :http, :request-method :get}
user> (unbreak!)
nil
```

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
