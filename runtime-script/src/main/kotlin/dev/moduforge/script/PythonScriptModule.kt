package dev.moduforge.script

import dev.moduforge.script.py.Interpreter
import dev.moduforge.script.py.PyExit
import dev.moduforge.script.py.PyError
import dev.moduforge.script.py.PyHost
import dev.moduforge.script.py.PyHostModule
import dev.moduforge.script.py.PyInterrupted
import dev.moduforge.script.py.PyModule
import dev.moduforge.sdk.Module
import dev.moduforge.sdk.ModuleContext
import dev.moduforge.sdk.ui.UiEvent
import kotlin.concurrent.thread

/**
 * A module written in Python. The script runs on its own thread in the interpreter of the runtime —
 * a large part of Python 3, not CPython: no packages from PyPI, no `async`, no C extensions — and
 * reaches the device only through the `mf` module.
 */
internal class PythonScriptModule(private val files: Map<String, ByteArray>, private val entry: String) : Module {

    private var main: Thread? = null

    @Volatile
    private var host: ScriptHost? = null

    override suspend fun onStart(context: ModuleContext) {
        val source = files[entry]?.decodeToString() ?: error("entry script $entry is missing")
        val scriptHost = ScriptHost(context).also { host = it }
        main = thread(name = "python-main", isDaemon = true, contextClassLoader = null, priority = Thread.NORM_PRIORITY) {
            // A deep recursion needs more stack than a default thread has.
            val runner = Thread(null, { run(context, scriptHost, source) }, "python-script", STACK_BYTES)
            runner.isDaemon = true
            runner.start()
            try {
                runner.join()
            } catch (e: InterruptedException) {
                runner.interrupt()
            }
        }
    }

    private fun run(context: ModuleContext, scriptHost: ScriptHost, source: String) {
        val pyHost = object : PyHost {
            override fun log(text: String) = scriptHost.log(text)

            override fun file(path: String): String? = files[path]?.decodeToString()

            override fun bundled(path: String): String? =
                PythonScriptModule::class.java.getResourceAsStream("/python/$path")?.use { it.readBytes().decodeToString() }

            override fun hostModule(interpreter: Interpreter): PyModule = PyHostModule(scriptHost).create(interpreter)

            override fun ask(question: String): String? = scriptHost.ask(question, false)
        }
        val interpreter = Interpreter(pyHost)
        try {
            runInterpreter(context, interpreter, source)
        } finally {
            interpreter.flushOutput()
        }
    }

    private fun runInterpreter(context: ModuleContext, interpreter: Interpreter, source: String) {
        try {
            interpreter.runMain(source, entry)
            // A line left unfinished by print(..., end="") comes before the closing note.
            interpreter.flushOutput()
            context.log.info("script finished")
            context.stopSelf("script finished")
        } catch (e: PyExit) {
            context.log.info("script finished")
            context.stopSelf("script finished")
        } catch (e: PyInterrupted) {
            // The module was stopped.
        } catch (e: PyError) {
            val place = if (e.line > 0) " (${e.file.ifEmpty { entry }}:${e.line})" else ""
            context.log.error("script failed: ${e.message}$place")
            context.stopSelf("script failed: ${e.message}$place")
        } catch (e: InterruptedException) {
            // The module was stopped.
        } catch (e: StackOverflowError) {
            context.log.error("script failed: RecursionError: maximum recursion depth exceeded")
            context.stopSelf("script failed: RecursionError")
        }
    }

    override suspend fun onUiEvent(context: ModuleContext, event: UiEvent) {
        host?.uiEvents?.put(event)
    }

    override suspend fun onStop(context: ModuleContext) {
        main?.interrupt()
        main = null
    }

    private companion object {
        const val STACK_BYTES = 48L * 1024 * 1024
    }
}
