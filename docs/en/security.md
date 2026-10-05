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
- **Author identity rests on the user comparing fingerprints.** A user who installs without
  checking can be given a look-alike module signed by someone else.
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
