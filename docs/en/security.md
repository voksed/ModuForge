# Security model

ModuForge treats every module as untrusted. This page describes what stands between a
module and the rest of the phone, what is recorded, and where the limits of the protection
are.

## The sandbox

Each running module lives in its own process, created from a service declared with
`android:isolatedProcess="true"`. Android gives such a process a throwaway user ID with:

- no permissions at all;
- no network: creating a socket or resolving a host name fails;
- no access to ModuForge's data directory or to shared storage;
- no visibility of most system services, including clipboard and window manager.

Module code is read from a file descriptor passed over Binder and loaded in memory. The
only channel to the outside is an AIDL interface to the host (`IHostBridge`). The host
creates one bridge per sandbox and ties it to the module's identity itself, so a module
cannot speak for another module.

On Android 10 and newer every module has its own process. Android 8 and 9 provide a single
sandbox process, so one module runs at a time.

## Permissions

Nothing is available by default. For a module to use a host service:

1. the permission must be listed in the module's manifest — otherwise the request is
   refused without asking the user;
2. the module must be enabled;
3. the user must have granted it.

The manifest is covered by the package signature, so the list cannot be extended after the
author signed the package.

| Permission | Host service | Enforcement |
|---|---|---|
| `NETWORK_OUTBOUND` | The host opens the connection and relays bytes into the sandbox | Checked on every connection. Only public internet addresses; the name is resolved once and the connection goes to the address that was checked. Revoking closes open connections within about two seconds |
| `FILE_SANDBOXED` | The host stores the module's files | Checked on every call. Paths cannot leave the module's directory. Files are encrypted with AES-256-GCM under a key held in the Android Keystore |
| `BACKGROUND_EXECUTION` | A foreground service keeps the host alive | A running module without the grant is stopped when the host leaves the screen |
| `NOTIFICATIONS` | The host posts the notification | Checked on every call. The module's name is added by the host; one notification slot per module |

The remaining permissions (`FILE_SHARED`, `CLIPBOARD`, `AI_INFERENCE`, `BOT_GATEWAY`,
`DEVICE_INFO`, `LOCAL_NETWORK_SCAN`) are defined, but no host service implements them, so
granting one gives the module nothing.

Permissions that act against other systems — today only `LOCAL_NETWORK_SCAN` — are designed
to be granted per target, with the user confirming for each target that they are
authorized to test it.

## Packages and signatures

A `.mfrg` package is a ZIP archive with the manifest, the code and a signature
(ECDSA P-256 over SHA-256) that covers the name and content of every other entry. Before
anything is shown to the user or installed, the host checks that:

- the signature matches — any changed, added, removed or renamed file is detected;
- the archive holds nothing outside the expected layout;
- the manifest is valid and the host can run the module.

The signature proves that a package was not altered and that two packages come from the
same key. **It does not prove who the author is.** There is no certificate authority; the
user sees the key's fingerprint and has to compare it with one obtained from the author.

Updates are accepted only from the key that signed the installed version, and never to an
older version.

Modules written in the app's editor, or imported as a plain script, are unsigned. They are
never installed silently: the script opens in the editor, where the user sees the code and
saves it. They run in the same sandbox behind the same permissions, which are still asked
for before the first start. An unsigned module and a signed one cannot replace each other.

### Device control: apps, screen, camera

`LAUNCH_APPS`, `SCREEN_CONTROL` and `CAMERA` let a module act on the phone, so they have their
own sensitivity level, "controls the device", and rules of their own:

- **Nothing runs by itself.** The module needs the grant like any other, and for the screen
  the user must also turn on the accessibility service of ModuForge in the system settings —
  a switch only the user can flip, which Android itself guards with a warning. Turning it off
  removes the ability from every module at once. The camera needs Android's own camera
  permission as well.
- **Visible while in use.** A notification names the module while it uses the camera or the
  screen, and Android shows its camera indicator during a photo.
- **Recorded.** Every call that acts, and every photo, is an entry in the audit log with the
  module, the call and its arguments; reading the screen is logged once a minute. Typed text
  is never written to the log.
- **Closed screens.** Whatever the grant, a module cannot read or operate the system
  settings, the permission and installer dialogs, or ModuForge itself. Otherwise a module
  could press "Allow" on its own consent dialog or switch off what guards it. Back and home
  always work, so the user can always get out. Password fields are never read, typed into or
  reported.
- **Photos stay in the module.** A photo is written to the module's private storage; leaving
  the phone needs `NETWORK_OUTBOUND` as well, which the user has seen.

What this does not protect against: with the screen grant a module can do anything in the
other apps a person could do by touch — send messages, make purchases in an open shopping app,
read what is shown. It is as powerful as the accessibility services on the phone are by design.
Grant it only to modules whose code you have read or whose author you trust, and treat a
module that asks for it without a clear reason the way you would treat any app that asks for
accessibility access.

### Updates of the app

The only other time the host itself connects to the network is for its own updates, and
only when the user presses **Check for updates** or has switched on the check at startup
(off by default). The check reads the public release description of the project on GitHub.
An APK is offered only when its address is an HTTPS address on GitHub and the file is under
200 MB; after the download the app verifies that it carries this app's package name and the
same signing certificate as the installed copy, and only then hands it to the system
installer, which makes the same check and asks the user to confirm.

What this does not protect against: whoever controls the GitHub account and the signing key
of the project can publish an update every user is offered. That is the trust placed in
the author of any app that is not distributed through a store.

### Install links

A `moduforge://install` link names an HTTPS address and, optionally, the fingerprint of the
author's key. Opening one never installs anything by itself:

- the app shows the address and downloads only after the user agrees — this is, with
  app updates, one of the two cases where the host itself connects to the network, and
  only then;
- only `https://` addresses are accepted, and the file is limited to 64 MB;
- the downloaded file goes through the same verification and the same review screen as a
  file picked by hand;
- when the link names a key, a package signed by any other key is refused. The server that
  hosts the file therefore cannot swap it for its own.

A link is as trustworthy as the place you got it from: a link with a fingerprint moves the
question "is this the author's key?" to "is this the author's link?".

### Developer mode

Developer mode opens a connection point through which a computer can install a package and
restart its module without the review screen. It exists to shorten the author's edit–run
cycle and is built to be useless to anyone else:

- off by default; the user turns it on in the settings and can turn it off at any time;
- by default it listens on the device itself only, reachable through a USB cable with
  debugging enabled; accepting connections from the local network is a separate switch;
- the computer must know a pairing token shown on the phone. The token is never sent: each
  request is authenticated with an HMAC over a fresh one-time challenge and the package, so
  a recorded exchange can be neither read for the token nor replayed;
- a pushed package passes the same signature and manifest checks and the same signer rules
  as any other, and its installation is written to the audit log;
- nothing is granted: permissions are still confirmed on the phone.

What it does give up is the review screen for packages sent by whoever holds the token. On
a shared or hostile Wi-Fi network leave the Wi-Fi switch off.

## What the user sees

- **Before installation:** signer fingerprint, the author's description, every permission
  the module can ever get, the author's reason for each.
- **Before start:** the declared permissions not yet granted, nothing switched on in
  advance.
- **Module UI** is drawn by the host inside a labelled frame, and questions from a module
  are shown in a host dialog that names the module. A module cannot draw over the app or
  imitate its dialogs.
- **Audit log:** installations, updates, rejections, lifecycle changes, every permission
  request with its outcome, every connection with host name and port.

## Stopping a module

Stop delivers `onStop` and then releases the process; Kill releases it immediately. Android
destroys an isolated process as soon as the host lets go of it. A module that ignores
`onStop` is destroyed after ten seconds.

## Limits of the protection

Stated plainly, so that nobody relies on more than there is:

- **A module with internet access can send out everything it has.** That is what the
  permission means. The sandbox limits what a module *has*: its own files and whatever the
  user typed into its questions.
- **The audit log records connections, not content.** Storage reads and writes are not
  recorded at all.
- **A question from a module is a phishing surface.** The dialog warns the user, but cannot
  stop them from typing a password that the module should not get.
- **Author identity rests on the user comparing fingerprints,** or on trusting the place
  an install link came from. A user who installs without checking can be given a
  look-alike module signed by someone else.
- **Rooted devices and a compromised OS are out of scope.** Isolation and the Keystore are
  only as strong as the system underneath.
- **Resource use is bounded only loosely.** Storage, connections and message sizes are
  capped; CPU and memory are not, beyond what Android enforces. A greedy module can drain
  the battery until the user stops it.
- **The class-loader filter is hygiene, not a boundary.** Module code sees the SDK and the
  Kotlin runtime and not the rest of the host's classes, but the protection is the process
  isolation.
- **Backups:** ModuForge excludes its data from cloud backup and device transfer. Module
  data does not move to a new phone.

## Reporting a problem

If you find a way for a module to get past any of the above, treat it as a security bug:
describe the steps and the device, and do not publish a working exploit module.
