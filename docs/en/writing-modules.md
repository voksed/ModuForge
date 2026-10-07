# Writing modules

A module is a script — in **Lua** or **JavaScript** — plus a short description of what it
needs. There are three ways to write one, from the lightest to the most complete:

| Way | You need | Good for |
|---|---|---|
| [In the app](#in-the-app) | Only the phone | A module for yourself |
| [A script on a computer](#on-a-computer) | The `mfrg` tool | Comfortable editing and debugging |
| [A signed package](#a-package-for-other-people) | The `mfrg` tool | Giving the module to other people |

The API is the same everywhere: [Lua](lua-api.md), [JavaScript](js-api.md). Ready-made
pieces for common tasks are in the [cookbook](cookbook.md). Compiled modules are covered
in [Kotlin modules](kotlin-modules.md).

## In the app

No computer, no manifest, no signature.

1. On the **Modules** tab press **Create module**, choose the language and a starting point:
   Telegram bot, page watcher or empty script.
2. Name the module and edit the code.
3. Press **Save and run**. Confirm the permissions on the first start.

The editor:

- colours the code and numbers the lines;
- has a row of **snippets** above the code — `http`, `storage`, `config`, `telegram`, `ui`
  and others; a tap inserts a working call at the cursor;
- **Add file** creates further files of the module (`lib/util.lua`), loaded from the main
  script with `require`;
- when the module stopped with an error, its screen shows **Open main.lua, line 12** — the
  editor opens with the cursor on that line.

What the app does for you:

- **Permissions are worked out from the code.** Using `mf.http`, `mf.connect` or
  `mf.websocket` declares internet access, `mf.storage` or the `config` library storage,
  `mf.notify` notifications. Background work is switched on by you in the editor.
- **The version goes up on every save**; the module's files and granted permissions are
  kept.
- **A running module is restarted** with the new code.

A ready `.lua` or `.js` file can be imported with the same button as a package: it opens in
the editor, where the whole code is visible, and becomes a module when you save.

Such a module exists only on your phone and carries no signature. To give it to other
people, make a [package](#a-package-for-other-people).

## On a computer

You need Java 17 or newer and this repository:

```
gradlew :tools:packer:installDist     once: builds the mfrg tool
```

`mfrg` is `mfrg.cmd` in the repository root; on other systems run
`tools/packer/build/install/mfrg/bin/mfrg`.

### Run it right here: `mfrg run`

```
mfrg run bot.lua            a single script, no manifest needed
mfrg run my-module          a project folder
```

The module runs on the computer with the same runtime the app uses and with real internet
access. Output goes to the terminal, questions from `mf.ask` are asked there, notifications
and the interface are printed as text. Storage lives in `.mfrg-run/` next to the code, so
settings survive between runs. Press Ctrl+C to stop.

| Option | Effect |
|---|---|
| `--deny NOTIFICATIONS,FILE_SANDBOXED` | Behave as if the user refused these permissions |
| `--allow-local` | Allow addresses of the local network and of this computer, e.g. a test server on `localhost`. On the phone they stay forbidden |

For a single script every permission its code uses counts as granted, and scripts lying
next to it are found by `require`.

### Send it to the phone: `mfrg push`

One command packs the module, installs it on the phone, restarts it and shows its output:

```
mfrg push my-module
```

Setting up, once:

1. In the app open **Settings → For module developers** and turn on **Developer mode**.
2. Connect the phone by USB with USB debugging on, and run the command shown on that screen
   — it carries the pairing token: `mfrg push --token XXXX-XXXX-XXXX-XXXX`.

The token and the address are remembered; afterwards `mfrg push` in the module folder is
enough. To work without a cable, turn on **Accept over Wi-Fi** and pass the phone's address
once: `mfrg push --host 192.168.1.20`. `--host usb` switches back.

After the push the terminal prints what the module writes, until Ctrl+C; the module keeps
running. `--no-follow` returns right after the installation.

Things to know:

- The app must be open (or a module must be working in the background) to accept a push.
- The package is signed with your key and checked like any other; an update must come from
  the same key. Permissions are still confirmed on the phone, the first time the module
  needs them.
- The token itself is never sent; the computer only proves that it knows it. Use Wi-Fi mode
  only in a network you trust and turn developer mode off when you are done.

### A project

`mfrg new` asks a few questions — name, language, starting point — and creates a folder:

```
my-bot/
  moduforge.json     manifest
  main.lua           entry script (main.js for JavaScript)
  lib/util.lua       any other files
```

Every file in the folder except `moduforge.json` becomes module code. Left out, and listed
by the packer when found: `*.session`, `*.session-journal`, `.env` and `.env.*` files, the
`.mfrg-run` folder, and the directories `.git`, `.hg`, `.svn`, `.idea`, `.vscode`,
`__pycache__`, `node_modules`, `venv`, `.venv`. File names may contain letters, digits and
`_ . @ + -`.

Loading other files:

| Language | Call | Loads |
|---|---|---|
| Lua | `require("lib.util")` | `lib/util.lua` |
| JavaScript | `require("./lib/util")` | `lib/util.js`, relative to the current file |

## A package for other people

```
mfrg pack my-module         builds <id>-<version>.mfrg, signed with your key
```

Users install the file in the app: with the **Import file** button, by opening it from a
file manager or a chat, or through an install link.

### An install link

Upload the `.mfrg` file anywhere it can be downloaded over HTTPS (a release on GitHub, your
site) and make a link:

```
mfrg link my-bot-1.0.0.mfrg https://example.com/files/my-bot-1.0.0.mfrg
```

The command prints a `moduforge://install?...` link. Publish it as text or turn it into a
QR code with any QR generator. Opening it on a phone with ModuForge downloads the package
after the user agrees, and — because the link carries the fingerprint of your key — the app
itself checks that the package is signed by you. Nobody has to compare fingerprints by eye.

### The manifest

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
  "permissions": ["NETWORK_OUTBOUND", "FILE_SANDBOXED"],
  "permissionReasons": {
    "NETWORK_OUTBOUND": "Talks to the bot API.",
    "FILE_SANDBOXED": "Keeps the token."
  }
}
```

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | Lowercase reverse-DNS name, unique on a device, at most 128 characters. Never change it: a different id is a different module |
| `name` | yes | Shown to the user, 1–64 characters |
| `version` | yes | [Semantic version](https://semver.org), e.g. `1.4.0` |
| `sdkRange` | yes | Versions of the module API you support. Use `>=1.0.0 <2.0.0` |
| `runtime` | no | `lua`, `js` or `dex` (compiled). Default `dex`. `python` is reserved and rejected at installation |
| `entry` | yes | For scripts: path of the main script inside the folder. For `dex`: class name |
| `permissions` | no | What the module may be granted. Anything not listed is refused without asking the user |
| `permissionReasons` | no | Your explanation of each permission, shown to the user. Up to 300 characters each; keys must be listed in `permissions` |
| `author` | no | Shown to the user |
| `description` | no | Shown before installation, up to 2000 characters |
| `ui` | no | `none` (default) or `compose`. Set `compose` when the module shows an interface (`mf.ui`) |

Unknown fields and unknown permission names make the manifest invalid.

### Signing

Every package is signed. Without `--key`, `mfrg pack` and `mfrg push` use your personal key
and create it on first use:

- location: `~/.moduforge/key.json`, or the file named by the `MODUFORGE_KEY` environment
  variable;
- **keep it private and back it up**. Whoever has the file can publish updates to your
  modules; if you lose it, you cannot.

The packer prints the key's fingerprint as "signer". Users see the same fingerprint when
they install. There is no central authority that vouches for authors: publish install links
(they carry the fingerprint) or the fingerprint itself where your users can find it.

### Updating

1. Raise `version` in `moduforge.json`.
2. `mfrg pack` with the same key.
3. Users install the new file or open the new link.

The app accepts an update only when the signer is the same and the version is not older.
Its files and granted permissions are kept; grants for permissions you removed from the
manifest are deleted. (`mfrg push` accepts the same version again, so there is no need to
raise it while developing.)

## Permissions

| Name | Unlocks |
|---|---|
| `NETWORK_OUTBOUND` | `mf.http`, `mf.connect`, `mf.websocket` |
| `FILE_SANDBOXED` | `mf.storage`, the `config` library |
| `BACKGROUND_EXECUTION` | Running while the app is not on screen; automatic start |
| `NOTIFICATIONS` | `mf.notify` |

Before a module starts, the app shows the user every declared permission it does not hold
yet, with your reasons. You do not have to request them in code. If the user leaves one
off, the calls that need it fail softly — `nil` and a message in Lua, an `Error` in
JavaScript — and the script keeps running.

`FILE_SHARED`, `CLIPBOARD`, `AI_INFERENCE`, `BOT_GATEWAY`, `DEVICE_INFO` and
`LOCAL_NETWORK_SCAN` are accepted in a manifest but unlock nothing yet.

## How a script runs

- The entry script runs from top to bottom on its own thread when the module starts.
- While it runs, the module is "Running". A bot is a script with an endless loop.
- When the script reaches its end or fails, the module is stopped. The reason, with the
  file and line of an error, is in the module output and in the audit log.
- When the user stops the module, the process is destroyed. Do not rely on cleanup code.
- There is no file system and no access to other apps: everything goes through `mf`.

## Settings and secrets

Never put tokens, passwords or session files into the folder: packages get passed around.
Ask for them at run time and keep them in module storage, which is encrypted on the
user's phone:

```lua
local config = require("mf.config")
local token = config.get("token", { ask = "API token", secret = true })
local interval = config.get("interval", { label = "Seconds between checks", default = 300 })
```

Every value read this way appears on the module's screen under **Settings**, where the
user can change it later; the module is restarted with the new value. You do not write any
settings screen yourself.

## Seeing what the module does

- `mf.log` (and `print`, `console.log`) writes to **Module output** on the module's screen.
  The last 500 lines are kept, also across restarts of the app.
- **Share** sends the output as text — handy when a user reports a problem to you.
- When a script fails, the output shows the message, the file and the line.
- `mfrg push` prints the same output in your terminal.

## Commands of `mfrg`

| Command | What it does |
|---|---|
| `mfrg new [dir]` | Interactive project wizard |
| `mfrg run <dir-or-script> [--deny …] [--allow-local]` | Runs a script module on this computer |
| `mfrg push [dir] [--token …] [--host …] [--no-follow]` | Installs on the phone, restarts the module, prints its output |
| `mfrg pack <dir> [--key file] [--out file.mfrg] [--dex apk-or-dex]` | Builds and signs a package |
| `mfrg link <file.mfrg> <https address>` | Prints an install link for an uploaded package |
| `mfrg verify <file.mfrg>` | Checks a package and prints its manifest summary and signer |
| `mfrg keygen <file>` | Creates a signing key in a place of your choice |
| `mfrg init <dir> --id … --runtime … --entry … [--name …]` | Writes only a manifest template |

## Limits

| What | Limit |
|---|---|
| Module code in a package | 64 MB |
| Files in a package | 10 000 |
| One file in module storage | 512 KB |
| Module storage in total | 64 MB, 2000 files |
| Open connections per module | 16 |
| HTTP response body | 8 MB |
| Redirects followed by `mf.http` | 5 by default, at most 10 |
| Text of a question to the user | 500 characters |
| Notification | title 80, text 1000 characters |
| One line of module output | 4000 characters; the app keeps the last 500 lines |
