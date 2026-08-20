fastlane documentation
----

# Installation

Make sure you have the latest version of the Xcode command line tools installed:

```sh
xcode-select --install
```

For _fastlane_ installation instructions, see [Installing _fastlane_](https://docs.fastlane.tools/#installing-fastlane)

# Available Actions

### check

```sh
[bundle exec] fastlane check
```

Everything that has to be true before this is pushed

### quick

```sh
[bundle exec] fastlane quick
```

The fast half of `check` -- what the pre-push hook runs

### kotlin_tests

```sh
[bundle exec] fastlane kotlin_tests
```

Kotlin tests, on the desktop target

### swift_tests

```sh
[bundle exec] fastlane swift_tests
```

Swift tests, in the simulator

### linters

```sh
[bundle exec] fastlane linters
```

detekt, SwiftLint and Android Lint

### analyzers

```sh
[bundle exec] fastlane analyzers
```

CRAP, SCRAP and duplication

### verify_versions

```sh
[bundle exec] fastlane verify_versions
```

Fail if any platform's version has drifted from gradle.properties

### version

```sh
[bundle exec] fastlane version
```

Write gradle.properties' version into the iOS Info.plist

### dmg

```sh
[bundle exec] fastlane dmg
```

Build the desktop DMG

### install_hooks

```sh
[bundle exec] fastlane install_hooks
```

Install the pre-push hook

----

This README.md is auto-generated and will be re-generated every time [_fastlane_](https://fastlane.tools) is run.

More information about _fastlane_ can be found on [fastlane.tools](https://fastlane.tools).

The documentation of _fastlane_ can be found on [docs.fastlane.tools](https://docs.fastlane.tools).
