package dev.moduforge.sdk.ui

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class UiTest {

    private val tree = column {
        text("Title", TextStyle.TITLE)
        row {
            button("ok", "OK")
            button("cancel", "Cancel", enabled = false)
        }
        textField("name", "Ada", label = "Name")
    }

    @Test
    fun `builder produces the declared structure`() {
        assertEquals(
            UiNode.Column(
                listOf(
                    UiNode.Text("Title", TextStyle.TITLE),
                    UiNode.Row(listOf(UiNode.Button("ok", "OK"), UiNode.Button("cancel", "Cancel", enabled = false))),
                    UiNode.TextField("name", "Ada", "Name"),
                ),
            ),
            tree,
        )
    }

    @Test
    fun `tree survives serialization`() {
        val json = Json.encodeToString(UiNode.serializer(), tree)
        assertEquals(tree, Json.decodeFromString(UiNode.serializer(), json))
    }

    @Test
    fun `events survive serialization`() {
        listOf(UiEvent.Click("ok"), UiEvent.TextChanged("name", "Grace")).forEach { event ->
            val json = Json.encodeToString(UiEvent.serializer(), event)
            assertEquals(event, Json.decodeFromString(UiEvent.serializer(), json))
        }
    }
}
