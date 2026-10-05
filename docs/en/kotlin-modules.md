# Kotlin modules

A compiled module is a class that implements `dev.moduforge.sdk.Module`. Compared with a
Lua script it can react to every lifecycle step and show its own interface. It runs in the
same sandbox and gets the same host services.

The template is `modules/hello` in this repository.

## Project

A module project is an Android application module used only as a container for compiled
code. It is never installed into the OS.

`build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.mymodule"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.example.mymodule"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }
}

// The SDK and the Kotlin runtime are provided by the sandbox.
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.kotlinx")
}

dependencies {
    compileOnly(project(":sdk"))
}
```

Outside this repository, publish the SDK to your local Maven repository with
`gradlew :sdk:publishToMavenLocal` and depend on `dev.moduforge:moduforge-sdk:1.0.0`
(`compileOnly`). The SDK is not published to a public repository.

The manifest lives in `src/main/assets/moduforge.json`, with `"runtime": "dex"` (or no
`runtime` field) and the class name in `entry`. Fields are described in
[Writing modules](writing-modules.md#the-manifest).

## Packing

```
gradlew :modules:hello:assembleRelease
mfrg pack modules/hello/src/main/assets --dex modules/hello/build/outputs/apk/release/hello-release-unsigned.apk
```

`--dex` takes the compiled classes out of the APK; the folder supplies `moduforge.json`.

## The module class

```kotlin
class MyModule : Module {
    private var work: CoroutineScope? = null

    override suspend fun onStart(context: ModuleContext) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { work = it }
        scope.launch {
            val connection = context.network.connect("example.com", 443, tls = true)
            // …
        }
    }

    override suspend fun onStop(context: ModuleContext) {
        work?.cancel()
    }
}
```

The class needs a public constructor without arguments.

| Callback | When | Where it runs |
|---|---|---|
| `onInstall` | After installation | A short-lived sandbox, destroyed when the callback returns |
| `onEnable` | The user enabled the module | Short-lived sandbox |
| `onStart` | The module is started | The sandbox that stays alive |
| `onUiEvent` | The user touched the module's UI | Same sandbox, only between start and stop |
| `onStop` | The module is being stopped | Same sandbox, which is destroyed afterwards |
| `onDisable` | The user disabled the module | Short-lived sandbox |
| `onUninstall` | Before removal | Short-lived sandbox |

Rules:

- **Callbacks must return quickly.** One that takes longer than 10 seconds is treated as
  failed; a failed `onStart` means the module does not start. Do long work, and anything
  that waits for the user, in a coroutine you launch from `onStart`.
- A callback that throws is recorded in the audit log and never blocks the user: stop,
  disable and uninstall always go through.
- Each short-lived sandbox is a new process. Fields of your class do not survive between
  `onInstall`, `onEnable` and `onStart`; keep state in `context.storage`.
- After `onStop` the process is destroyed whether or not your code finished.

## `ModuleContext`

The only link between a module and the outside. Every member is a call to the host.

| Member | Purpose | Permission |
|---|---|---|
| `manifest` | The module's own manifest | — |
| `log` | `info`, `warn`, `error` — module output | — |
| `capabilities` | `request(CapabilityRequest)`, `isGranted(capability)` | — |
| `network` | `connect(host, port, tls)` → `Connection` with `input` and `output` streams | `NETWORK_OUTBOUND` |
| `storage` | `read`, `write`, `delete`, `list` | `FILE_SANDBOXED` |
| `notifications` | `notify(title, text)` | `NOTIFICATIONS` |
| `prompt` | `ask(question, secret)` → the user's answer or null | — |
| `ui` | `show(tree)`, `clear()` | manifest `"ui": "compose"` |
| `stopSelf(reason)` | Ask the host to stop the module | — |

A service called without its permission throws `CapabilityNotGrantedException`. Network
and storage failures throw `IOException`.

`network.connect` gives a raw byte stream. With `tls = true` the host performs the TLS
handshake and certificate check, and the stream carries the decrypted data. There is no
HTTP client in the SDK; write the request yourself or bundle a pure-Kotlin client that
works on streams.

## User interface

A sandbox cannot create windows, so a module does not draw. It describes its interface as
a tree; the app renders the tree inside a frame labelled as module content and sends back
what the user did. Set `"ui": "compose"` in the manifest.

```kotlin
private var clicks = 0

override suspend fun onStart(context: ModuleContext) = render(context)

override suspend fun onUiEvent(context: ModuleContext, event: UiEvent) {
    if (event is UiEvent.Click && event.id == "count") clicks++
    render(context)
}

private fun render(context: ModuleContext) {
    context.ui.show(
        column {
            text("Counter", TextStyle.TITLE)
            row {
                button("count", "Count")
                text("Clicks: $clicks")
            }
        },
    )
}
```

| Node | Builder | Notes |
|---|---|---|
| Column | `column { … }` | Children stacked vertically |
| Row | `row { … }` | Children side by side |
| Text | `text(text, style)` | Styles: `TITLE`, `BODY`, `CAPTION`, `CODE` |
| Button | `button(id, label, enabled)` | Sends `UiEvent.Click(id)` |
| Text field | `textField(id, value, label)` | Sends `UiEvent.TextChanged(id, value)` on every edit |

To change what is shown, call `show` again with a new tree. A tree may hold at most 500
nodes and 256 KB when serialized; a larger one is rejected and noted in the module output.

For a text field, pass back the value you received. Passing a different value replaces
what the user typed.

## What module code can use

- The Android platform classes and the Kotlin standard library with coroutines.
- `dev.moduforge.sdk.*`.
- Any pure-JVM library you compile into the module.

Not available: AndroidX and the other libraries of the host app, native libraries, and
anything that needs an Android `Context`, a file path or a socket — the process has no
permissions, no data directory and no network. See
[Security model](security.md).
