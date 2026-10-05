# Contributing

How to build and how the code is organised: [docs/en/building.md](docs/en/building.md)
([по-русски](docs/ru/building.md)).

## Before you open a pull request

```
gradlew build                                   unit tests, lint, both APKs
gradlew :host-app:connectedDebugAndroidTest     with a device connected
```

The device tests uninstall the app from the device when they finish. They are not run in
CI, so say in the pull request whether you ran them.

## What a change needs

- **Tests.** Logic in `core`, `sdk` and the packer is covered by unit tests; anything that
  touches the sandbox, the package installer or a host service gets a device test.
- **Both languages.** User-visible strings go to `values` and `values-ru`. A change to
  behaviour that is documented goes to `docs/en` and `docs/ru`.
- **Bounds.** Whatever a module can trigger must be limited on the host side by size,
  count or time.
- **A migration** for any change to the database schema, with a case in
  `DatabaseMigrationTest`.

## What does not belong here

The host stays small: it finds, checks, installs, runs, isolates and stops modules. A
feature that can be a module should be a module.

Exploits, offensive payloads and modules for denial of service, mass targeting, credential
theft or covert surveillance are out of scope.

## Licence

By contributing you agree that your contribution is licensed under the
[Apache License 2.0](LICENSE).
