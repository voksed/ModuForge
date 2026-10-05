# Security policy

ModuForge runs untrusted modules. A way for a module to get past its sandbox, its
permissions or the package signature check is a security bug.

## Reporting

Report it privately through **Security → Report a vulnerability** on this repository.
Please do not open a public issue and do not publish a working exploit module.

Include:

- what a module can do that it should not;
- steps to reproduce, with the module source if you have one;
- device model and Android version.

## Scope

In scope: escaping the isolated process, using a host service without the matching grant,
acting as another module, installing a package whose signature does not match, reaching
the device or the local network through the network permission, reading another module's
storage.

Out of scope: rooted devices and a compromised operating system, what a module does with
data the user knowingly gave it, and the limits listed under "Limits of the protection" in
[docs/en/security.md](docs/en/security.md).
