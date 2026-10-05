package dev.moduforge.host.runtime

import dev.moduforge.core.module.ModuleUiSink
import dev.moduforge.sdk.ui.UiNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** UI trees currently published by running modules. */
@Singleton
class ModuleUiStore @Inject constructor() : ModuleUiSink {

    private val trees = MutableStateFlow<Map<String, UiNode>>(emptyMap())

    override fun show(moduleId: String, root: UiNode?) {
        trees.update { if (root == null) it - moduleId else it + (moduleId to root) }
    }

    fun observe(moduleId: String): Flow<UiNode?> = trees.map { it[moduleId] }.distinctUntilChanged()
}
