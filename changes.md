# Changelog

## 0.0.12 (2021-01-23)

Thanks to Darrick Wiebe for these features.

- Fix a bug in `catch-break!`.
- Add support for `return!` from `catch-break!`
- Add label support to `catch-break!`

## 0.0.11 (2019-08-04)

Adds the new `com.gfredericks.debug-repl.async` namespace.

## 0.0.10 (2019-02-10)

Supports the namespace change in newer versions of nrepl (and thus
leiningen) ([#7](https://github.com/gfredericks/debug-repl/issues/7)
and [#8](https://github.com/gfredericks/debug-repl/pull/8)).

## 0.0.9 (2017-08-24)

Fixes a [bug with primitive type hints](https://github.com/gfredericks/debug-repl/issues/4).

## 0.0.8

Fixed a race condition in `unbreak!!` that caused it to just not work.

## 0.0.7

Added `catch-break!`.

## 0.0.6

Sorta fix `*1`, `*2`, etc.

## 0.0.5

Detects the NREPL-53 bug instead of failing strangely.

## 0.0.4

Compatibility with clojure 1.4.

## 0.0.3

Add `unbreak!!` for disabling future breaks.
