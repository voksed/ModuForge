# ModuForge

ModuForge is an Android app that runs other people's modules — bots, scripts, small
automations — each in its own sandbox. A module can do only what you allowed it to do, and
everything it does on the network is written to a log you can read.

The app does nothing on its own. It finds, checks, installs, runs, isolates and stops
modules, and decides what each of them may reach.

## Who this is for

| You want to | Read |
|---|---|
| Install and run modules on your phone | [User guide](user-guide.md) |
| Write a bot or a script | [Writing modules](writing-modules.md), the [cookbook](cookbook.md), then the [Lua API](lua-api.md), the [JavaScript API](js-api.md) or the [Python API](python-api.md) |
| Write a compiled module with its own UI | [Kotlin modules](kotlin-modules.md) |
| Understand what a module can and cannot do | [Security model](security.md) |
| Build the app or change it | [Building from source](building.md) |

## What works today

- Installing signed module packages (`.mfrg`): from a file, by opening the file from
  another app, or by an install link that also verifies the author's key.
- Modules in Lua, JavaScript and Python, and compiled Kotlin modules, each in an isolated process.
- Writing a module in the app: an editor with highlighting, snippets and several files.
- Host services behind permissions: internet access (HTTP, raw connections, WebSocket),
  encrypted storage, background execution, notifications, questions to the user, a simple
  interface drawn by the app.
- Device control for scenarios: open apps, touch and read the screen (autoclickers, "press the
  button when it appears", reacting to notifications) and take photos — each its own
  permission, visible while in use, and recorded in the audit log.
- Module settings the user can change, and module output that is kept and can be shared.
- Automatic start on boot, updates of installed modules, an audit log.
- Updating the app itself from the releases on GitHub, on request.
- For authors: `mfrg run` executes a module on the computer, `mfrg push` sends it to the
  phone and shows its output, `mfrg pack` and `mfrg link` publish it. Libraries for
  Telegram bots, settings and schedules in both languages.
- Themes: light, dark, wallpaper or custom colours, palettes, corner shapes, text size.

## What does not exist yet

- A module catalogue. Modules are distributed as files and install links.
- Services behind the `FILE_SHARED`, `CLIPBOARD`, `AI_INFERENCE`, `BOT_GATEWAY`,
  `DEVICE_INFO` and `LOCAL_NETWORK_SCAN` permissions. They can be declared and granted but
  unlock nothing.
- A release on Google Play. Play does not allow apps that run code downloaded from
  elsewhere, so ModuForge is distributed as an APK.

## Responsible use

ModuForge is a tool for automation, learning and authorized testing. Use modules only
against systems you own or are explicitly permitted to test. The platform ships no exploits
or offensive payloads, and modules that perform denial of service, mass targeting,
credential theft or covert surveillance are out of scope for this project.
