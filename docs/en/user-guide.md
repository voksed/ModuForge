# User guide

## Installing a module

A module comes as a file with the `.mfrg` extension.

1. Copy the file to the phone.
2. Open ModuForge, tab **Modules**, press **Import module (.mfrg)** and pick the file.
3. Read the review screen:
   - **Signed by** — the fingerprint of the author's key. The name inside a package can be
     anything; the fingerprint cannot be faked. Compare it with the one the author
     published.
   - **Description** — the author's own words, not checked by anyone.
   - **Permissions it may ask for** — everything the module will ever be able to get.
4. Press **Install**.

Installing grants nothing and runs nothing. A package that was altered after signing, or
is not signed at all, is refused before this screen appears.

## Running a module

Open the module from the list.

| Control | What it does |
|---|---|
| **Enabled** | A disabled module cannot run and is refused every permission |
| **Start** | Runs the module. If it declares permissions it does not hold yet, you are asked first |
| **Stop** | Asks the module to finish, then removes its process |
| **Kill** | Removes the process at once, without asking the module |
| **Start automatically** | Starts the module when the phone boots and whenever ModuForge starts |

A script that finishes or fails stops by itself; the reason is in the audit log.

## Permissions

A module can get only the permissions listed on its review screen, and only those you
switched on. Before the first start ModuForge shows them on one screen; nothing is switched
on in advance.

| Permission | What the module can do with it |
|---|---|
| Internet access | Connect to servers on the internet. It cannot reach your phone itself or devices in your home network |
| Private storage | Keep its own files, encrypted, invisible to other modules |
| Run in background | Keep working when ModuForge is not on screen |
| Notifications | Show notifications under its own name |

The module screen lists every permission with its state. **Revoke** takes a permission back
at any moment; open internet connections of the module are closed within a few seconds.

A module may ask for a permission again while it runs. You can refuse; refusing is not
remembered, so it can ask again later.

Some permissions on the review screen — shared files, clipboard, AI models, bot hosting,
device information, local network scan — are reserved for future versions. Granting them
changes nothing today.

## When a module asks you something

A module can show a dialog with a question, for example for a bot token or a login code.
The dialog is drawn by ModuForge, names the module and reminds you that the answer goes to
that module. Do not type passwords for accounts that have nothing to do with the module.

## Background work

- A running module **with** the background permission keeps ModuForge alive; a permanent
  notification shows how many modules are running.
- A running module **without** it is stopped when ModuForge leaves the screen, including
  when the phone is locked.
- **Start automatically** works only for an enabled module that holds the background
  permission.

Android can still stop background apps to save battery. If modules stop on their own, take
ModuForge off battery optimisation in the system settings (on Samsung: Settings → Apps →
ModuForge → Battery → Unrestricted).

## Updating and removing

To update, import the newer `.mfrg` the same way. ModuForge accepts it only if it is signed
with the same key as the installed module and its version is not older. Stop the module
first. Its files and permissions are kept.

To remove a module, disable it and press **Uninstall**. Its files and permissions are
deleted with it.

## Audit log

The **Audit log** tab records, newest first:

- installations, updates, rejections and removals;
- every start, stop, kill and crash;
- every permission request and the decision;
- every internet connection: the address, and whether it was allowed.

Reading and writing of a module's own files are not recorded.

The module screen also shows **Module output** — what the module printed. It is kept in
memory only and is lost when ModuForge restarts.

## Appearance

**Settings → Appearance**:

| Setting | Choices |
|---|---|
| Theme | As in system, light, dark |
| Colours | From the wallpaper (Android 12 and newer), one of nine presets, or your own `#RRGGBB` |
| Palette | Six ways to build a colour scheme from the chosen colour |
| Black background | True black in the dark theme |
| Corners | Sharp, standard, round |
| Text size | 85–130% of the system size |
| Compact module list | Rows instead of cards |
| Labels in the navigation bar | On or off |

**Reset appearance** returns everything to the defaults.

## Troubleshooting

| What you see | Why, and what to do |
|---|---|
| "Not installed: signature does not match the package contents" | The file was changed after the author signed it. Get it again from the author |
| "Not installed: package is not signed" | The file is not a finished package |
| "…signed by a different key than the installed module" | This is not an update from the same author. Uninstall the old module first if you trust the new one |
| "…runtime 'python' is not supported by this host" | The module needs a language this version cannot run |
| The module stops when the screen turns off | It has no background permission, or Android's battery saving stopped the app |
| Notifications do not appear | Notifications are turned off for ModuForge in the system settings |
| Start does nothing | The module is disabled, or it finished immediately — look at the module output |
