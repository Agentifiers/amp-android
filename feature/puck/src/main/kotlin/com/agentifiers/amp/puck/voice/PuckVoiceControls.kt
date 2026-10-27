package com.agentifiers.amp.puck.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.PuckTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.model.PuckConversationId
import com.agentifiers.amp.sync.api.AccountScope

/** Start, watch, mute, route and end a voice call with Puck in [conversation]. */
@Composable
fun PuckVoiceControls(conversation: PuckConversationId, scope: AccountScope, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val snapshot by PuckCalls.snapshot.collectAsStateWithLifecycle()
    val call = snapshot?.takeIf { it.conversation == conversation }
    val busy = snapshot?.let { it.conversation != conversation && !it.over } == true
    var micDenied by rememberSaveable { mutableStateOf(false) }
    val place = { PuckCalls.place(context, conversation, scope.puck.voice(conversation)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        micDenied = granted[Manifest.permission.RECORD_AUDIO] != true
        if (!micDenied) place()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm)) {
        call?.let { Text(it.voice.words(), Modifier.testTag(PuckTags.voiceStatus), style = AmpTheme.type.body) }
        if (call == null || call.over) {
            AmpButton(
                if (busy) "On another Puck call" else "Start voice call",
                {
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) place() else ask.launch(CALL_PERMISSIONS)
                },
                PuckTags.voiceStart,
                rank = AmpButtonRank.Primary,
                enabled = !busy,
            )
            if (micDenied) Text("Amp needs the microphone to call Puck.", Modifier.testTag(PuckTags.voiceMicDenied))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm)) {
                AmpButton(if (call.muted) "Unmute" else "Mute", PuckCalls::toggleMute, PuckTags.voiceMute)
                if (call.canSwitchRoute) AmpButton(call.route ?: "Audio", PuckCalls::switchRoute, PuckTags.voiceRoute)
                AmpButton("End", PuckCalls::hangUp, PuckTags.voiceEnd, rank = AmpButtonRank.Destructive)
            }
        }
    }
}

private val CALL_PERMISSIONS = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
