# 0.7.32 (2025-11-28 / 0f1e1ac)

## Added

- Add `lambdaisland.makina.test`
- Add Kaocha plugin
- Add a full write-up to the README

## Fixed

- Fix cljs compatiblity for `lambdaisland.makina.system`
- Fix cljdoc build

## Changed

- `:config-source` is now just `:config`, and can receive a map, function, var,
  resource (URL), file (java.io.File), or relative resource path (string)
- in the `app` layer, separate atom operations from their purely functional
  logic, so either version can be called directly

# 0.3.16 (2025-09-01 / 4691756)

## Fixed

- Only restart started keys after refresh

# 0.2.12 (2025-08-25 / 3df0a75)

Several improvements and bugfixes, first generally usable version.

# 0.1.8 (2025-08-14 / d48ccc1)

First release for internal testing
