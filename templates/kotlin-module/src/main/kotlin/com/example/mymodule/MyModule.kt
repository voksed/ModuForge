package com.example.mymodule

import dev.moduforge.sdk.Module
import dev.moduforge.sdk.ModuleContext
import dev.moduforge.sdk.ui.UiEvent
import dev.moduforge.sdk.ui.column

/** A counter with a button: shows a screen, reacts to taps and reports through the module output. */
class MyModule : Module {

    private var taps = 0

    override suspend fun onStart(context: ModuleContext) {
        context.log.info("started ${context.manifest.name} ${context.manifest.version}")
        render(context)
    }

    override suspend fun onUiEvent(context: ModuleContext, event: UiEvent) {
        if (event is UiEvent.Click && event.id == "tap") {
            taps++
            context.log.info("tap number $taps")
        }
        render(context)
    }

    override suspend fun onStop(context: ModuleContext) {
        context.log.info("stopped after $taps taps")
    }

    private fun render(context: ModuleContext) = context.ui.show(
        column {
            text("Hello from a compiled module")
            text("Taps: $taps")
            button("tap", "Tap me")
        },
    )
}
