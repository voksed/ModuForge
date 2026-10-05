package dev.moduforge.sdk.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public enum class TextStyle { TITLE, BODY, CAPTION, CODE }

/**
 * Element of a module's user interface. A module describes its UI as a tree of nodes;
 * the host draws the tree with its own toolkit and reports interaction as [UiEvent]s.
 * A module never draws pixels itself and cannot cover or imitate host UI.
 */
@Serializable
public sealed interface UiNode {
    /** Children stacked vertically. */
    @Serializable
    @SerialName("column")
    public data class Column(val children: List<UiNode>) : UiNode

    /** Children laid out horizontally. */
    @Serializable
    @SerialName("row")
    public data class Row(val children: List<UiNode>) : UiNode

    @Serializable
    @SerialName("text")
    public data class Text(val text: String, val style: TextStyle = TextStyle.BODY) : UiNode

    /** @property id reported back in [UiEvent.Click]; unique within the tree. */
    @Serializable
    @SerialName("button")
    public data class Button(val id: String, val label: String, val enabled: Boolean = true) : UiNode

    /**
     * Single-line text input.
     *
     * @property id reported back in [UiEvent.TextChanged]; unique within the tree.
     * @property value text to display; showing a tree with a different value replaces what the user typed.
     */
    @Serializable
    @SerialName("field")
    public data class TextField(val id: String, val value: String, val label: String = "") : UiNode
}

@Serializable
public sealed interface UiEvent {
    public val id: String

    @Serializable
    @SerialName("click")
    public data class Click(override val id: String) : UiEvent

    @Serializable
    @SerialName("text")
    public data class TextChanged(override val id: String, val value: String) : UiEvent
}

/**
 * Screen area the host gives to a module whose manifest declares `"ui": "compose"`.
 * For other modules every call is ignored.
 */
public interface UiSurface {
    /** Replaces the displayed tree. Oversized trees are rejected by the host. */
    public fun show(root: UiNode)

    public fun clear()
}

/** Builder for [UiNode.Column] and [UiNode.Row] children. */
public class UiChildren internal constructor() {
    internal val nodes: MutableList<UiNode> = mutableListOf()

    public fun text(text: String, style: TextStyle = TextStyle.BODY) {
        nodes += UiNode.Text(text, style)
    }

    public fun button(id: String, label: String, enabled: Boolean = true) {
        nodes += UiNode.Button(id, label, enabled)
    }

    public fun textField(id: String, value: String, label: String = "") {
        nodes += UiNode.TextField(id, value, label)
    }

    public fun column(content: UiChildren.() -> Unit) {
        nodes += dev.moduforge.sdk.ui.column(content)
    }

    public fun row(content: UiChildren.() -> Unit) {
        nodes += dev.moduforge.sdk.ui.row(content)
    }
}

public fun column(content: UiChildren.() -> Unit): UiNode.Column = UiNode.Column(UiChildren().apply(content).nodes.toList())

public fun row(content: UiChildren.() -> Unit): UiNode.Row = UiNode.Row(UiChildren().apply(content).nodes.toList())
