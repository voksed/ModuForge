package dev.moduforge.sdk

/** Version of the module API. Hosts accept a module only when its `sdkRange` contains this version. */
public object ModuForgeSdk {
    public const val VERSION: String = "1.0.0"

    public val version: SemVer = SemVer.parse(VERSION)
}
