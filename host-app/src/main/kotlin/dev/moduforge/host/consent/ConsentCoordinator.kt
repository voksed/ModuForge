package dev.moduforge.host.consent

import dev.moduforge.core.permission.ConsentDecision
import dev.moduforge.core.permission.ConsentPrompt
import dev.moduforge.core.permission.ConsentPrompter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the permission broker to the UI: exposes the prompt awaiting an answer and
 * resumes the broker once the user decides. The broker submits one prompt at a time.
 */
@Singleton
class ConsentCoordinator @Inject constructor() : ConsentPrompter {

    private class Pending(val prompt: ConsentPrompt, val answer: CompletableDeferred<ConsentDecision>)

    private val current = MutableStateFlow<Pending?>(null)
    private val _pending = MutableStateFlow<ConsentPrompt?>(null)

    /** Prompt to display, or null when nothing awaits the user. */
    val pending: StateFlow<ConsentPrompt?> = _pending.asStateFlow()

    override suspend fun requestConsent(prompt: ConsentPrompt): ConsentDecision {
        val entry = Pending(prompt, CompletableDeferred())
        current.value = entry
        _pending.value = prompt
        try {
            return entry.answer.await()
        } finally {
            current.compareAndSet(entry, null)
            _pending.compareAndSet(prompt, null)
        }
    }

    /** Answers [prompt]; ignored when it is no longer the pending one. */
    fun answer(prompt: ConsentPrompt, decision: ConsentDecision) {
        current.value?.takeIf { it.prompt === prompt }?.answer?.complete(decision)
    }
}
