# User guide

## Installing a module

A module comes as a file with the `.mfrg` extension, or as a link to one.

1. Get the module in any of these ways:
   - open ModuForge, tab **Modules**, press **Import file** and pick the file;
   - open the `.mfrg` file from a file manager, a download or a chat and choose ModuForge;
   - open an install link (`moduforge://install…`) or scan its QR code with the camera.
     The app shows the address and downloads the file only after you agree.
2. Read the review screen:
   - **Signed by** — the fingerprint of the author's key. The name inside a package can be
     anything; the fingerprint cannot be faked. Compare it with the one the author
     published. When you came by an install link that names the author's key, the app has
     compared it for you and says so; a file signed by anyone else is refused.
   - **Description** — the author's own words, not checked by anyone.
   - **Permissions it may ask for** — everything the module will ever be able to get.
3. Press **Install**.

Installing grants nothing and runs nothing. A package that was altered after signing, or
is not signed at all, is refused before this screen appears.

## A module of your own, without a computer

**Create module** opens an editor: pick the language (Lua or JavaScript) and a starting
point, name the module, edit the code and press **Save and run**. The editor colours the
code, numbers the lines, inserts ready calls from the row of snippets and lets a module
consist of several files. Details are in [Writing modules](writing-modules.md).

A `.lua` or `.js` file is imported with the same button as a package. It is not installed right
away; it opens in the editor, so you see the code and decide whether to save it as a
module.

Modules written on the phone carry no signature — you are their author. Their screen has
an **Edit code** button, and after an error a button that opens the editor on the line
where it happened. Such a module cannot be replaced by a package from a file, and a
signed module cannot be replaced by unsigned code.

## Running a module

Open the module from the list.

| Control | What it does |
|---|---|
| **Enabled** | A disabled module cannot run and is refused every permission |
| **Start** | Runs the module. If it declares permissions it does not hold yet, you are asked first |
| **Stop** | Asks the module to finish, then removes its process |
| **Kill** | Removes the process at once, without asking the module |
| **Start automatically** | Starts the module when the phone boots and whenever ModuForge starts |

A script that finishes or fails stops by itself; the reason is in the module output and
in the audit log.

### Settings of a module

Many modules ask for something on their first start: a token, an address, how often to
check. These values are kept and shown in the **Settings** card on the module's screen.
Press **Change** to set a new value; a running module is restarted to pick it up. Secrets
are shown as dots and are typed anew, never displayed. Clearing a value makes the module
ask for it again, or use its built-in default.

### Module output

**Module output** shows what the module writes while it works, and why it stopped. The
last 500 lines are kept, also after the app or the phone was restarted. **Share** sends the
text to another app — for example to the module's author when something goes wrong;
**Clear** empties it.

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

## Developer mode

For people who write modules on a computer. **Settings → For module developers →
Developer mode** lets the author's computer send a module to this phone with one command
(`mfrg push`): the module is installed and restarted without tapping through the app, and
its output is shown on the computer.

- It is off until you turn it on, and works only while the app is running.
- The computer must know the **pairing token** shown on that screen. **New token** locks
  out every computer paired before.
- By default only a computer connected by USB can reach the phone. **Accept over Wi-Fi**
  also accepts connections from the local network; switch it on only in a network you
  trust.
- A module that arrives this way is verified like any other and appears in the audit log.
  It gets no permissions by itself: you still confirm them on the phone.

If you do not write modules, leave it off.

## Troubleshooting

| What you see | Why, and what to do |
|---|---|
| "Not installed: signature does not match the package contents" | The file was changed after the author signed it. Get it again from the author |
| "Not installed: package is not signed" | The file is not a finished package |
| "…signed by a different key than the installed module" | This is not an update from the same author. Uninstall the old module first if you trust the new one |
| "The downloaded package is signed by a different key than the link names" | The file behind the link is not from the author the link vouches for. Do not look for another way to install it; tell the author |
| "Download failed" | No connection, or the file was moved. Ask the author for a fresh link |
| "…runtime 'python' is not supported by this host" | The module needs a language this version cannot run |
| The module stops when the screen turns off | It has no background permission, or Android's battery saving stopped the app |
| Notifications do not appear | Notifications are turned off for ModuForge in the system settings |
| Start does nothing | The module is disabled, or it finished immediately — look at the module output |
