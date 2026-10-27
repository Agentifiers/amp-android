package com.agentifiers.amp.thread.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.agentifiers.amp.model.ThreadId

/** The draft for one thread, shared by the composer and the queue strip's Remove. */
@Composable
fun rememberComposerText(thread: ThreadId): MutableState<String> = rememberSaveable(thread.raw) { mutableStateOf("") }
