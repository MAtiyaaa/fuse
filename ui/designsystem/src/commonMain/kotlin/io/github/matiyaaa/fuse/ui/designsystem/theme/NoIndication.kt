package io.github.matiyaaa.fuse.ui.designsystem.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.Modifier

/** Disables platform ripples and highlights: Fuse components animate their own pressed state. */
internal object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}
    override fun hashCode(): Int = 0
    override fun equals(other: Any?) = other === this
}
