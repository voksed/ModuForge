package dev.moduforge.host.consent

import dev.moduforge.core.module.UserInputPrompter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** A question a module is waiting on. */
class PendingQuestion(val moduleId: String, val moduleName: String, val question: String, val secret: Boolean) {
    internal val answer = CompletableDeferred<String?>()
}

/** Bridges module questions to the UI, one at a time. */
@Singleton
class InputCoordinator @Inject constructor() : UserInputPrompter {

    private val mutex = Mutex()
    private val _pending = MutableStateFlow<PendingQuestion?>(null)

    /** Question to display, or null when none is waiting. */
    val pending: StateFlow<PendingQuestion?> = _pending.asStateFlow()

    override suspend fun ask(moduleId: String, moduleName: String, question: String, secret: Boolean): String? =
        mutex.withLock {
            val entry = PendingQuestion(moduleId, moduleName, question, secret)
            _pending.value = entry
            try {
                entry.answer.await()
            } finally {
                _pending.compareAndSet(entry, null)
            }
        }

    /** Answers [question]; a null [text] means the user dismissed it. */
    fun answer(question: PendingQuestion, text: String?) {
        question.answer.complete(text)
    }
}
