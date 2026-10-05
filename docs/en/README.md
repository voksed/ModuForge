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
| Write a bot or a script | [Writing modules](writing-modules.md), then the [Lua API](lua-api.md) |
| Write a compiled module with its own UI | [Kotlin modules](kotlin-modules.md) |
| Understand what a module can and cannot do | [Security model](security.md) |
| Build the app or change it | [Building from source](building.md) |

## What works today

- Importing signed module packages (`.mfrg`) from a file, with a review screen.
- Lua scripts and compiled Kotlin modules, each in an isolated process.
- Host services behind permissions: internet access, encrypted storage, background
  execution, notifications, questions to the user.
- Automatic start on boot, updates of installed modules, an audit log.
- A packer and a project wizard for authors, with libraries for Telegram bots, settings
  and schedules.
- Themes: light, dark, wallpaper or custom colours, palettes, corner shapes, text size.

## What does not exist yet

- JavaScript and Python runtimes. Only Lua and compiled Kotlin run today.
- A module catalogue. Modules are distributed as files.
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
