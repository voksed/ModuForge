# Writing modules

A module is a folder with a manifest (`moduforge.json`) and code. The folder is packed
into one signed file, `.mfrg`, which users import in the app.

This page covers script modules in Lua. For compiled modules see
[Kotlin modules](kotlin-modules.md).

## The simplest way: write the module in the app

A module of your own needs no computer, no packer, no manifest and no signature.

1. In the app, on the **Modules** tab, press **Create module** and pick a starting point:
   Telegram bot, page watcher or empty script.
2. Name the module and edit the code in the built-in editor.
3. Press **Save and run**. Confirm the permissions on the first start.

What the app does for you:

- **Permissions are worked out from the code.** Using `mf.http` declares internet access,
  `mf.storage` or `mf.config` storage, `mf.notify` notifications. Background work is
  switched on by you in the editor.
- **The version goes up on every save**; the module's files and granted permissions are
  kept.
- **A running module is restarted** with the new code.

To get back to the code, open the module and press **Edit code**.

A ready `.lua` file can be imported with the same button as a package: it opens in the
editor, where the whole code is visible, and becomes a module when you save.

Such a module is one script and exists only on your phone. To give a module to other
people, or to build one from several files, you need a signed package — the rest of this
page.

## A package for distribution: quick start

You need Java 17 or newer and this repository.

```
gradlew :tools:packer:installDist     once: builds the packer
mfrg new                              asks a few questions, creates a project folder
mfrg pack <folder>                    builds <id>-<version>.mfrg
```

`mfrg` is `mfrg.cmd` in the repository root; on other systems run
`tools/packer/build/install/mfrg/bin/mfrg`.

`mfrg new` offers three starting points:

| Template | What you get |
|---|---|
| Telegram bot | A bot that answers `/start` and echoes text |
| Watcher | Downloads a page every five minutes and notifies when it changes |
| Empty script | One line that writes to the module output |

Copy the `.mfrg` file to a phone and import it in the app.

## The project folder

```
my-bot/
  moduforge.json     manifest
  main.lua           entry script
  lib/util.lua       any other files, loaded with require("lib.util")
```

Every file in the folder except `moduforge.json` becomes module code. Left out, and listed
by the packer when found: `*.session`, `*.session-journal`, `.env` and `.env.*` files,
and the directories `.git`, `.hg`, `.svn`, `.idea`, `.vscode`, `__pycache__`,
`node_modules`, `venv`, `.venv`. File names may contain letters, digits and `_ . @ + -`.

## The manifest

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
| `runtime` | no | `lua` or `dex` (compiled). Default `dex`. `js` and `python` are reserved and rejected at installation |
| `entry` | yes | For `lua`: path of the main script inside the folder. For `dex`: class name |
| `permissions` | no | What the module may be granted. Anything not listed is refused without asking the user |
| `permissionReasons` | no | Your explanation of each permission, shown to the user. Up to 300 characters each; keys must be listed in `permissions` |
| `author` | no | Shown to the user |
| `description` | no | Shown before installation, up to 2000 characters |
| `ui` | no | `none` (default) or `compose`; only compiled modules can show UI |

Unknown fields and unknown permission names make the manifest invalid.

## Permissions

| Name | Unlocks |
|---|---|
| `NETWORK_OUTBOUND` | `mf.http` |
| `FILE_SANDBOXED` | `mf.storage`, `mf.config` |
| `BACKGROUND_EXECUTION` | Running while the app is not on screen; automatic start |
| `NOTIFICATIONS` | `mf.notify` |

Before a module starts, the app shows the user every declared permission it does not hold
yet, with your reasons. You do not have to request them in code. If the user leaves one
off, the calls that need it return `nil` and a message; the script keeps running.

`FILE_SHARED`, `CLIPBOARD`, `AI_INFERENCE`, `BOT_GATEWAY`, `DEVICE_INFO` and
`LOCAL_NETWORK_SCAN` are accepted in a manifest but unlock nothing yet.

## How a script runs

- The entry script runs from top to bottom on its own thread when the module starts.
- While it runs, the module is "Running". A bot is a script with an endless loop.
- When the script reaches its end or raises an error, the module is stopped and the reason
  is written to the audit log.
- When the user stops the module, the process is destroyed. Do not rely on cleanup code.
- `require("name")` loads `name.lua` from the folder, `require("dir.name")` loads
  `dir/name.lua`.
- Lua 5.2 with the `string`, `table`, `math`, `coroutine` and `bit32` libraries. There is
  no `os`, no `io` and no file system.

The whole API is in the [Lua API reference](lua-api.md).

## Secrets

Never put tokens, passwords or session files into the folder: packages get passed around.
Ask for them at run time and keep them in module storage, which is encrypted on the
user's phone:

```lua
local config = require("mf.config")
local token = config.get("token", { ask = "API token", secret = true })
```

## Signing

Every package is signed. Without `--key`, `mfrg pack` uses your personal key and creates it
on first use:

- location: `~/.moduforge/key.json`, or the file named by the `MODUFORGE_KEY` environment
  variable;
- **keep it private and back it up**. Whoever has the file can publish updates to your
  modules; if you lose it, you cannot.

The packer prints the key's fingerprint as "signer". Users see the same fingerprint when
they install. There is no central authority that vouches for authors, so publish your
fingerprint where your users can compare it.

## Updating

1. Raise `version` in `moduforge.json`.
2. `mfrg pack` with the same key.
3. Users import the new file.

The app accepts an update only when the signer is the same and the version is not older.
The module must be stopped. Its files and granted permissions are kept; grants for
permissions you removed from the manifest are deleted.

## Packer commands

| Command | What it does |
|---|---|
| `mfrg new [dir]` | Interactive project wizard |
| `mfrg pack <dir> [--key file] [--out file.mfrg] [--dex apk-or-dex]` | Builds and signs a package |
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
| Text of a question to the user | 500 characters |
| Notification | title 80, text 1000 characters |
| One line of module output | 4000 characters; the app keeps the last 200 lines |

## Testing on a device

1. Install the app (see [Building from source](building.md)) and import your package.
2. Enable the module, press Start, switch on the permissions.
3. Watch **Module output** on the module screen; `mf.log` and `print` write there.
4. After a change: raise the version, pack, import again.
