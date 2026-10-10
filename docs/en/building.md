# Building from source

## Requirements

- JDK 17
- Android SDK with platform 37 (the Android Gradle plugin downloads missing packages once
  the SDK licences are accepted)
- A device or emulator with Android 8.0 or newer; the device tests also need it to be
  online

Point `local.properties` in the repository root at the SDK, or set `ANDROID_HOME`:

```properties
sdk.dir=/path/to/android-sdk
```

The build uses Gradle 9.8 through the wrapper, Android Gradle plugin 9.4, Kotlin 2.4.

## Commands

| Command | Result |
|---|---|
| `gradlew build` | Everything: unit tests, lint, debug and release APKs |
| `gradlew :host-app:assembleDebug` | `host-app/build/outputs/apk/debug/host-app-debug.apk` |
| `gradlew :host-app:installDebug` | Builds and installs on the connected device. The debug build has its own id, `dev.moduforge.host.debug`, so it lives next to an installed release without clashing over the signature |
| `gradlew :sdk:test :core:test :runtime-script:test :tools:packer:test :runtime-sandbox:testDebugUnitTest` | Unit tests; no device needed |
| `gradlew :host-app:connectedDebugAndroidTest` | Device tests. **Uninstalls the app from the device afterwards, with all its data** |
| `gradlew :tools:packer:installDist` | The `mfrg` packer in `tools/packer/build/install/mfrg` |
| `gradlew :sdk:publishToMavenLocal` | The module SDK as `dev.moduforge:moduforge-sdk:1.0.0` |

The release APK is unsigned; sign it with your own key before distributing.

## Layout

| Path | Kind | Contents |
|---|---|---|
| `sdk/` | Kotlin/JVM library | The API modules compile against: `Module`, `ModuleContext`, capabilities, manifest, declarative UI |
| `core/` | Kotlin/JVM library | Host logic with no Android dependency: permission broker, module lifecycle, audit log interfaces, package format and signatures, network policy |
| `runtime-script/` | Kotlin/JVM library | The Lua, JavaScript and Python runtimes with their libraries and the device calls; shared by the app and `mfrg run` |
| `runtime-sandbox/` | Android library | The isolated-process service, AIDL protocol, network relay, encrypted storage, package store |
| `host-app/` | Android application | UI (Compose), Room database, dependency wiring (Hilt), background service, notifications |
| `tools/packer/` | Kotlin/JVM application | The `mfrg` command |
| `modules/hello/` | Module project | Template of a compiled module |
| `modules/sandbox-probe/` | Module project | Test module that tries to escape the sandbox |
| `examples/` | Script projects | Lua: `lua-hello`, `telegram-echo-bot`; JavaScript: `js-counter`, `js-weather`; Python: `py-site-watch` |
| `docs/` | | This documentation |

Dependencies point one way: `host-app → runtime-sandbox → runtime-script → core → sdk`.

## How a module runs

1. **Import.** `ModuleInstaller` copies the file into staging and `ModulePackageVerifier`
   checks structure and signature. The UI shows the review screen.
2. **Install.** The package moves to `files/modules/<id>/`; `ModuleManager` records the
   module (disabled, no grants) and runs `onInstall` in a short-lived sandbox.
3. **Start.** `SandboxModuleRuntime` binds a fresh isolated instance of `SandboxService`,
   passes the package as a file descriptor together with an `IHostBridge`, and calls
   `onStart`.
4. **Inside the sandbox** `ModuleLoader` reads the code into memory and creates either the
   compiled module or a `LuaScriptModule`. Everything the module asks for goes through the
   bridge.
5. **On the host side** the bridge checks the grant with `PermissionBroker` and performs
   the service: `ModuleNetworkRelay`, `ModuleStorage`, notifications, questions.
6. **Stop.** `onStop` with a timeout, then the binding is released and Android destroys
   the process.

## Tests

| Where | What they cover |
|---|---|
| `sdk/src/test` | Version ranges, manifest parsing and validation, UI tree serialization |
| `core/src/test` | Permission broker, module lifecycle, package signatures, network policy |
| `tools/packer/src/test` | Packer commands and the project wizard |
| `runtime-script/src/test` | The Lua, JavaScript and Python runtimes and their bundled libraries against an in-memory host |
| `runtime-sandbox/src/test` | The sandbox protocol and the host side of the bridge |
| `host-app/src/androidTest` | On a device: sandbox isolation, package import and update, network and storage through the host, database migration |

The device tests start real isolated processes. They assert that a module without grants
could not open a socket, resolve a name, read, write or list the host's files, list shared
storage, read the clipboard or find the window service; that killing or stopping leaves no
process behind; and that tampered packages are rejected. The network cases contact
`example.com` and `wrong.host.badssl.com`.

## Conventions

- Code comments are impersonal documentation of what the code does and why.
- User-visible text lives in `host-app/src/main/res/values` (English) and `values-ru`
  (Russian); add both.
- A change to the database schema needs a migration and a case in
  `DatabaseMigrationTest`.
- Anything a module can trigger must be bounded (size, count or time) on the host side.
