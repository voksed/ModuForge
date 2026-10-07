package dev.moduforge.script.py

import java.util.concurrent.SynchronousQueue

/**
 * A running generator function. The body runs on a thread of its own and hands every yielded
 * value over to the consumer, so that `yield` can sit anywhere in the body, loops included.
 */
internal class PyGenerator(private val interpreter: Interpreter, private val body: (PyGenerator) -> Any?) : Iterator<Any?> {

    private class Message(val kind: Int, val value: Any?)

    private val toBody = SynchronousQueue<Message>()
    private val fromBody = SynchronousQueue<Message>()
    private var thread: Thread? = null
    private var finished = false
    private var running = false
    private var buffered: Any? = Missing

    override fun hasNext(): Boolean {
        if (buffered === Missing && !finished) buffered = advance(PyNone)
        return buffered !== Missing
    }

    override fun next(): Any? {
        if (!hasNext()) throw NoSuchElementException()
        return buffered.also { buffered = Missing }
    }

    /** Python's `next()`: the next value, or raises `StopIteration`. */
    fun pythonNext(send: Any? = PyNone): Any? {
        if (buffered !== Missing) return buffered.also { buffered = Missing }
        val value = advance(send)
        if (value === Missing) throw PyError.of("StopIteration", "")
        return value
    }

    /** Called by the body: hands [value] to the consumer and waits for what the consumer sends back. */
    fun yieldValue(value: Any?): Any? {
        fromBody.put(Message(YIELD, value))
        val answer = toBody.take()
        if (answer.kind == THROW) throw answer.value as Throwable
        return answer.value
    }

    private fun advance(send: Any?): Any? {
        if (finished) return Missing
        if (running) throw PyError.of("ValueError", "generator already executing")
        running = true
        try {
            if (thread == null) {
                thread = Thread(null, {
                    Interpreter.enter(interpreter)
                    val outcome = try {
                        Message(DONE, body(this))
                    } catch (e: Throwable) {
                        Message(FAILED, e)
                    }
                    try {
                        fromBody.put(outcome)
                    } catch (e: InterruptedException) {
                        // The consumer is gone.
                    }
                }, "py-generator", STACK_BYTES).apply {
                    isDaemon = true
                    start()
                }
            } else {
                toBody.put(Message(SEND, send))
            }
            val message = fromBody.take()
            return when (message.kind) {
                YIELD -> message.value
                DONE -> { finished = true; Missing }
                else -> { finished = true; throw message.value as Throwable }
            }
        } finally {
            running = false
        }
    }

    /** `gen.throw(error)`: raises [error] at the point where the body is waiting. */
    fun throwInto(error: Throwable): Any? {
        if (thread == null || finished) {
            finished = true
            throw error
        }
        toBody.put(Message(THROW, error))
        val message = fromBody.take()
        return when (message.kind) {
            YIELD -> message.value
            DONE -> { finished = true; throw PyError.of("StopIteration", "") }
            else -> { finished = true; throw message.value as Throwable }
        }
    }

    fun close() {
        finished = true
        thread?.interrupt()
    }

    private companion object {
        const val YIELD = 0
        const val DONE = 1
        const val FAILED = 2
        const val SEND = 3
        const val THROW = 4
        const val STACK_BYTES = 16L * 1024 * 1024
    }
}
