# ModuForge

[![CI](https://github.com/voksed/ModuForge/actions/workflows/ci.yml/badge.svg)](https://github.com/voksed/ModuForge/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

**[Документация на русском](docs/ru/README.md)** · [Documentation in English](docs/en/README.md)

An Android app that runs other people's modules — bots, scripts, small automations — each
in its own sandbox. A module can do only what you allowed, and everything it does on the
network is written to a log you can read. The app does nothing on its own: every feature
is a module.

Version 1.0.0. Android 8.0 or newer.

```lua
-- A complete Telegram bot. The token is asked for on the first start.
local telegram = require("mf.telegram")
local bot = assert(telegram.bot())
bot:on("text", function(message) bot:reply(message, message.text) end)
bot:run()
```

## What it does

- **Sandbox.** Each module runs in an isolated process with no permissions, no network
  and no access to files. It reaches the outside only through the host.
- **Permissions.** Internet, encrypted storage, background work and notifications are
  services the host performs for a module — after you switched them on, one by one.
- **Signed packages.** Modules come as `.mfrg` files. Before installing you see who
  signed the package, what it says about itself and everything it can ever ask for.
- **Audit log.** Every installation, start, permission decision and network connection.
- **Lua, JavaScript and Python.** The same API in all three (Python is an interpreter built
  into the app, a large part of Python 3 rather than CPython), with libraries for Telegram bots,
  settings and schedules; HTTP, raw connections, WebSocket, hashes, dates, a simple UI.
- **Scenarios with the phone.** `mf.apps`, `mf.screen`, `mf.camera`: open apps, press buttons
  in them, run an autoclicker, react to what appears on the screen, take photos. Each is its
  own permission; the screen works only in the full edition and only while you keep the accessibility service on, a
  notification shows while it is used, and some screens (settings, permission dialogs,
  ModuForge itself) stay closed to modules.
- **Write a module on the phone.** Create module → pick a starting point → edit → run.
  An editor with highlighting, snippets and several files; no computer, manifest or
  signature; permissions are worked out from the code.
- **For authors.** `mfrg run` executes a module on your computer, `mfrg push` sends it to
  the phone with one command and prints its output, `mfrg pack` and `mfrg link` publish
  it. Compiled Kotlin modules are supported too.
- **Install by link.** A link (or QR code) carries the author's key, so the app verifies
  the author instead of the user comparing fingerprints.
- **Themes.** Light, dark, wallpaper or custom colours, palettes, corner shapes, text size.

Not there yet: a module catalogue, a Google Play release
(Play does not allow apps that run downloaded code; ModuForge is distributed as an APK).

## Getting started

| You want to | Go to |
|---|---|
| Use the app | [User guide](docs/en/user-guide.md) |
| Write a module | [Writing modules](docs/en/writing-modules.md), [cookbook](docs/en/cookbook.md), [Lua API](docs/en/lua-api.md), [JavaScript API](docs/en/js-api.md), [Python API](docs/en/python-api.md) |
| Build from source | [Building](docs/en/building.md) |
| Know what a module can do | [Security model](docs/en/security.md) |

```sh
./gradlew :host-app:assembleDebug        # the app
./gradlew :tools:packer:installDist      # the mfrg tool
mfrg new                                 # create a module project
mfrg run my-module                       # run it on this computer
mfrg push my-module                      # install on the phone, restart, show output
mfrg pack my-module                      # build a signed .mfrg
```

The rest of this file is a technical overview; the documentation above is the reference.

## Layout

| Gradle module | Type | Purpose |
|---|---|---|
| `:sdk` | Kotlin/JVM, published as `dev.moduforge:moduforge-sdk` | Stable API modules compile against: `Module`, `ModuleContext`, `Capability`, manifest, semver, declarative UI |
| `:core` | Kotlin/JVM | Host logic without Android dependencies: `PermissionBroker`, `ModuleManager`, `AuditLog`, `ModuleRuntime` contract |
| `:runtime-script` | Kotlin/JVM | Lua, JavaScript and Python runtimes and the script API, shared by the app and `mfrg run` |
| `:runtime-sandbox` | Android library | Isolated-process sandbox, AIDL protocol, module package store |
| `:host-app` | Android application | Compose UI, Room storage, Hilt wiring, consent dialog |
| `:tools:packer` | Kotlin/JVM CLI (`mfrg`) | Creates, runs, pushes and packs modules |
| `:modules:hello` | Module package | Example module: lifecycle, UI, one permission |
| `:modules:sandbox-probe` | Module package | Test module that tries to escape the sandbox; packed into the device tests only |

## Sandbox

- Each running module lives in its own process created from a service declared with
  `android:isolatedProcess="true"`: a throwaway UID with no permissions, no network, no
  access to the host's data directory and no visibility of most system services.
- Module code is read from a file descriptor passed over Binder and loaded in memory.
  The module sees the platform, the SDK and the Kotlin runtime, and nothing else of the host.
- The host and the module talk through AIDL only (`ISandbox`, `IHostBridge`). The host binds
  each bridge to a module identity; a module cannot speak for another one.
- Stopping delivers `onStop` with a timeout and then releases the process. Killing releases
  it immediately; the system destroys an isolated process as soon as it is unbound.
- `onInstall`, `onEnable`, `onDisable` and `onUninstall` run in a short-lived sandbox that is
  destroyed when the callback returns.
- On Android 10+ every module has its own process. Android 8–9 offer one sandbox process,
  so one module runs at a time.

### Module UI

An isolated process cannot create windows, so a module does not draw. It describes its UI
as a tree (`dev.moduforge.sdk.ui`), the host renders the tree with Compose inside a frame
labelled as module content, and interaction comes back as events. A module therefore cannot
cover or imitate host UI such as the consent dialog.

## Writing a module

A module is a folder with a manifest and code, packed into a signed `.mfrg` file that users
import from the module list. Before installing, the host shows the signer, the author's
description and every permission the module may ask for.

```
moduforge.json      manifest
code/...            script sources, or classes*.dex for compiled modules
data/...            read-only files shipped with the module
META-INF/MFRG.SIG   author's signature over everything else
```

```json
{
  "id": "com.example.mybot",
  "name": "My bot",
  "version": "1.0.0",
  "sdkRange": ">=1.0.0 <2.0.0",
  "runtime": "lua",
  "entry": "main.lua",
  "author": "Example",
  "description": "What the module does.",
  "permissions": ["NETWORK_OUTBOUND"],
  "permissionReasons": { "NETWORK_OUTBOUND": "Talks to the bot API." }
}
```

`runtime` is `lua`, `js`, `python` or `dex`. For a script runtime `entry` is the main script:
it runs on its own thread from start until the module is stopped.

### Packing

```sh
./gradlew :tools:packer:installDist          # builds tools/packer/build/install/mfrg
# mfrg.cmd in the project root runs it; Java 17+ must be installed
mfrg new                                     # asks a few questions, creates a project
mfrg run my-bot                              # runs on this computer, real network
mfrg push my-bot                             # installs on the phone and restarts the module
mfrg pack my-bot                             # -> <id>-<version>.mfrg
mfrg verify my-bot-1.0.0.mfrg
mfrg link my-bot-1.0.0.mfrg https://...      # install link carrying your key's fingerprint
```

`mfrg new` offers starting points (Telegram bot, page watcher, empty script). `pack` signs
with your personal key, created on first use at `~/.moduforge/key.json`; `--key` names
another one.

`pack` takes every file in the folder as module code. Session files (`*.session`), `.env`
files and VCS or cache directories are left out and listed: credentials must not travel
inside a package. For a compiled module add `--dex path/to/module.apk`.

The key's fingerprint is what users see as the signer. There is no central authority:
publish the fingerprint where your users can compare it.

### Script API

The table lists the core calls in their Lua form; the full references are
[Lua API](docs/en/lua-api.md), [JavaScript API](docs/en/js-api.md) and [Python API](docs/en/python-api.md).

| Call | Meaning |
|---|---|
| `mf.log(text)`, `print(...)` | Write to the module output |
| `mf.request(capability, reason [, target])` | Ask for a permission; returns `granted, denialReason`; waits for the user |
| `mf.granted(capability [, target])` | Check without asking |
| `mf.sleep(seconds)` | Pause |
| `mf.http{url=, method=, headers=, body=, form=, files=}` | HTTP(S) request; returns `{status, body, headers, url}`. Follows redirects; bodies up to 8 MB |
| `mf.connect(host, port [, {tls=}])`, `mf.websocket(url)` | Raw TCP/TLS connection; WebSocket |
| `mf.date(format)`, `mf.hash.*`, `mf.hmac.*`, `mf.base64.*`, `mf.random(n)` | Dates, digests, encodings, random bytes |
| `mf.ui.show(tree)`, `mf.ui.wait()` | A simple interface drawn by the host; events back |
| `mf.storage.read(path)` / `.write(path, data)` / `.delete(path)` / `.list()` | Module files |
| `mf.notify(title [, text])` | Show a notification |
| `mf.ask(question [, secret])` | Ask the user to type an answer; returns it, or `nil` when dismissed |
| `mf.json.decode(text)` / `mf.json.encode(value)` | JSON; arrays are 1-based tables, `null` becomes `nil` |
| `mf.urlencode(text)` | Percent-encoding for URLs |
| `mf.id`, `mf.name`, `mf.version` | Manifest fields |

Libraries shipped with the runtime: `require("mf.telegram")` (bot framework with commands,
handlers and keyboards), `require("mf.config")` (settings asked once, remembered and editable by the user in the app),
`require("mf.schedule")` (periodic tasks).

Before a module starts, the host offers the user every declared permission it does not hold
yet on one screen, so scripts do not have to request them.

Network and storage calls return `nil, message` on failure, including a missing grant.
`require` loads other files of the package. `io` and `luajava` are not available; of `os`
only `time`, `date` and `clock`. In JavaScript failures are thrown as `Error`.
`examples/lua-hello` is a complete script module. `examples/telegram-echo-bot` is a working
Telegram bot: it asks for the token on first start, keeps it in module storage and
long-polls the Bot API.

### Updating a module

Importing a package for a module that is already installed updates it, if the package is
signed by the same key and its version is not older. The module must not be running. Its
data and grants are kept; grants for permissions the new version no longer declares are
removed.

### Compiled modules

`:modules:hello` is the template for a Kotlin module: it implements
`dev.moduforge.sdk.Module`, compiles against `:sdk` (`compileOnly`) and can show UI.
Build it with `gradlew :modules:hello:assembleRelease` and pack the result with
`mfrg pack <dir with moduforge.json> --key my-key.json --dex <built apk>`.

The host itself ships without modules; everything is imported as a `.mfrg` package.

## Permission model

- A module has no capability by default. A capability must be declared in the manifest
  **and** granted by the user; an undeclared capability is refused without a prompt.
- A disabled module is refused everything, including capabilities granted earlier.
- Intrusive capabilities (currently `LOCAL_NETWORK_SCAN`) are granted per target. The user
  must confirm being authorized to test each target; the confirmation is audited.
- Every request, decision, revocation and lifecycle change is written to the audit log.
- Grants can be revoked from the module screen at any time.

## Host services

A sandbox has no network, no file system and no lifetime of its own. What a grant unlocks
is a service the host performs for the module:

| Capability | What the host does |
|---|---|
| `NETWORK_OUTBOUND` | Opens TCP connections (optionally TLS, with certificate validation) and relays the bytes into the sandbox. Only public internet addresses: the device itself and the local network are refused. Every connection is audited; revoking the grant cuts open connections within seconds. |
| `FILE_SANDBOXED` | Keeps the module's files, encrypted with a Keystore key, in a directory only that module can address. 512 KB per file, 64 MB per module. Removed on uninstall. This is where bot sessions and tokens belong. |
| `BACKGROUND_EXECUTION` | Keeps the host alive with a foreground service and a notification while the module runs. A running module without this grant is stopped when the host leaves the screen. |

| `NOTIFICATIONS` | Posts a notification that always carries the module's name. One slot per module: a new notification replaces the previous one. |

Without any capability a module can also ask the user a question (`prompt.ask`): the host
shows a dialog that names the module and warns that the answer goes to it. This is how a
bot gets its token or a login code without shipping them in the package.

The remaining capabilities are defined and can be granted, but no host service uses them yet.

A module marked "Start automatically" is started when the device boots and whenever the
host starts, provided it is enabled and holds `BACKGROUND_EXECUTION`. A script that runs to
its end, or fails, is stopped by the host with the reason recorded in the audit log.

## Building

Requires JDK 17 and the Android SDK (platform 37). Point `local.properties` at the SDK:

```properties
sdk.dir=/path/to/android-sdk
```

```sh
./gradlew build                                # unit tests, lint, debug and release APKs
./gradlew :sdk:test :core:test                 # host logic tests, no device needed
./gradlew :host-app:connectedDebugAndroidTest  # sandbox isolation tests, needs a device
./gradlew :sdk:publishToMavenLocal             # publish the SDK for module authors
```

The device tests run real isolated processes and assert that a module without grants
could not open a socket, resolve a host name, read, write or list the host's files, list
shared storage, read the clipboard or find the window service; that killing or stopping a
module leaves no process behind; that tampered packages are rejected; and that network and
storage work only through granted host services. The network cases need the device online.

## Distribution

Loading executable code that did not ship with the app is prohibited by Google Play policy.
A Play build can only run bundled modules; importing `.mfrg` packages is for builds
distributed outside Play.

## Responsible use

ModuForge is a tool for authorized testing, education and automation.

- Use modules only against systems you own or have explicit permission to test.
  Unauthorized access to or probing of computer systems is illegal in most jurisdictions.
- The platform ships no exploits or offensive payloads and enables no scanning by default.
  It does not implement denial of service, mass targeting, credential theft or covert
  surveillance, and modules providing them are out of scope for this project.
- Intrusive capabilities stay off until the user confirms authorization for a concrete
  target. Each confirmation is recorded with the target and the time.
- Third-party modules are untrusted. Review the capabilities a module requests before
  enabling it, and revoke the ones it does not need.
- You are responsible for complying with the law and with the policies of the app store
  and of the services your modules talk to.
