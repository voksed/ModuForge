package dev.moduforge.sdk

import kotlinx.serialization.Serializable

/** How prominently a capability is surfaced to the user. Every level requires an explicit grant. */
public enum class Sensitivity {
    /** Confined to the module's own sandbox or to user-visible output. */
    NORMAL,

    /** Reaches data or systems outside the module's sandbox. */
    SENSITIVE,

    /** Acts against third-party systems; each target needs a separate authorization confirmation. */
    INTRUSIVE,

    /** Operates the device itself: its camera, its screen, its apps. Always visible to the user while in use. */
    DEVICE_CONTROL,
}

/**
 * Unit of access a module may be granted. Nothing is available by default:
 * a capability must be declared in the manifest and granted by the user.
 */
@Serializable
public enum class Capability(public val sensitivity: Sensitivity) {
    /** Outbound connections to the internet. */
    NETWORK_OUTBOUND(Sensitivity.SENSITIVE),

    /** Read/write inside the module's private storage. */
    FILE_SANDBOXED(Sensitivity.NORMAL),

    /** Read/write of user-selected locations outside the module's storage. */
    FILE_SHARED(Sensitivity.SENSITIVE),

    /** Read/write of the system clipboard. */
    CLIPBOARD(Sensitivity.SENSITIVE),

    /** Posting notifications on behalf of the module. */
    NOTIFICATIONS(Sensitivity.NORMAL),

    /** Running while the host UI is not in the foreground. */
    BACKGROUND_EXECUTION(Sensitivity.NORMAL),

    /** Access to the host AI engine (local or cloud models configured by the user). */
    AI_INFERENCE(Sensitivity.SENSITIVE),

    /** Hosting a long-lived bot session with credentials stored by the host. */
    BOT_GATEWAY(Sensitivity.SENSITIVE),

    /** Reading device model, OS version and similar identifiers. */
    DEVICE_INFO(Sensitivity.SENSITIVE),

    /** Probing hosts on the local network. Requires per-target authorization. */
    LOCAL_NETWORK_SCAN(Sensitivity.INTRUSIVE),

    /** Listing, launching and opening links in the apps installed on the device. */
    LAUNCH_APPS(Sensitivity.DEVICE_CONTROL),

    /** Taking photos with the device's cameras. */
    CAMERA(Sensitivity.DEVICE_CONTROL),

    /**
     * Touching the screen and reading what is on it in every app, through the system accessibility
     * service the user turns on: taps, swipes, typing, finding and pressing buttons by their text.
     */
    SCREEN_CONTROL(Sensitivity.DEVICE_CONTROL),
    ;

    /** True when every request must name a target the user confirms being authorized to test. */
    public val requiresTargetAuthorization: Boolean
        get() = sensitivity == Sensitivity.INTRUSIVE
}

/**
 * Request for a capability, submitted through [CapabilityGateway].
 *
 * @property rationale shown to the user verbatim in the consent prompt.
 * @property target host, address or CIDR the operation is aimed at; mandatory when
 * [Capability.requiresTargetAuthorization] is true, ignored otherwise.
 */
@Serializable
public data class CapabilityRequest(
    val capability: Capability,
    val rationale: String,
    val target: String? = null,
)

@Serializable
public enum class DenialReason {
    /** The module is not installed on this host. */
    MODULE_UNKNOWN,

    /** The module is installed but not enabled. */
    MODULE_NOT_ENABLED,

    /** The capability is absent from the module manifest. */
    NOT_DECLARED,

    /** The capability needs a target and none was supplied. */
    TARGET_REQUIRED,

    /** The user did not confirm being authorized to act against the target. */
    TARGET_NOT_AUTHORIZED,

    /** The user declined the request. */
    USER_DENIED,
}

@Serializable
public sealed interface CapabilityResult {
    @Serializable
    public data object Granted : CapabilityResult

    @Serializable
    public data class Denied(val reason: DenialReason) : CapabilityResult
}

/** Module-side entry point to the host permission broker. */
public interface CapabilityGateway {
    /** Asks for a capability; may suspend while the user is prompted. */
    public suspend fun request(request: CapabilityRequest): CapabilityResult

    /** Reports whether a capability is currently usable, without prompting. */
    public suspend fun isGranted(capability: Capability, target: String? = null): Boolean
}
