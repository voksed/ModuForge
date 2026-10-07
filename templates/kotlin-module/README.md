# Kotlin module template

A complete, buildable project for a ModuForge module written in Kotlin. Copy this folder,
rename it and change the package, the id and the code.

Once, to make the SDK available to the build (in a clone of the ModuForge repository):

```
gradlew :sdk:publishToMavenLocal
```

Then in the copy of this folder:

```
gradlew assembleRelease
mfrg push src/main/assets --dex build/outputs/apk/release/my-module-release-unsigned.apk
```

`mfrg push` packs the module, sends it to the phone (developer mode must be on, see the
user guide), restarts it and prints its output. To make a package for other people use
`mfrg pack` with the same arguments instead.

Files:

| File | Purpose |
|---|---|
| `src/main/kotlin/…/MyModule.kt` | The module class: lifecycle callbacks and a small interface |
| `src/main/assets/moduforge.json` | The manifest: id, version, permissions, entry class |
| `build.gradle.kts` | Build settings; keep `compileOnly` for the SDK |

When you rename the package, change it in four places: `namespace` and `applicationId` in
`build.gradle.kts`, the `package` line of the class, and `id` and `entry` in the manifest.
Documentation: [Kotlin modules](../../docs/en/kotlin-modules.md).
