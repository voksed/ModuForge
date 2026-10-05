package dev.moduforge.core.permission

import dev.moduforge.sdk.Capability

/**
 * What the user is asked to decide.
 *
 * @property rationale text supplied by the module; untrusted, displayed verbatim.
 * @property target non-null exactly when the capability requires target authorization.
 */
data class ConsentPrompt(
    val moduleId: String,
    val moduleName: String,
    val capability: Capability,
    val rationale: String,
    val target: String?,
)

sealed interface ConsentDecision {
    /** @property targetAuthorizationConfirmed the user stated they are authorized to act against the target. */
    data class Allow(val targetAuthorizationConfirmed: Boolean = false) : ConsentDecision

    data object Deny : ConsentDecision
}

/** Presents a consent prompt to the user and suspends until it is answered. */
fun interface ConsentPrompter {
    suspend fun requestConsent(prompt: ConsentPrompt): ConsentDecision
}
