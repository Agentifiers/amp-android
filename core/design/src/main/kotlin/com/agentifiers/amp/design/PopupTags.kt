package com.agentifiers.amp.design

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * Menus, dialogs and sheets compose in their own window, outside the root that turns test tags into resource-ids,
 * so each one sets it again or agent-device cannot select its items by `id=`.
 */
fun Modifier.tagsAsResourceIds(): Modifier = semantics { testTagsAsResourceId = true }
