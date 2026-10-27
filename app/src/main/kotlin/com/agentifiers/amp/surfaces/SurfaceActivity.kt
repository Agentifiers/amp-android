package com.agentifiers.amp.surfaces

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.agentifiers.amp.MainActivity
import com.agentifiers.amp.WindowNavigator
import com.agentifiers.amp.puck.voice.CallPuckResult
import com.agentifiers.amp.puck.voice.callPuck
import com.agentifiers.amp.sync.api.AmpEngines
import com.agentifiers.amp.sync.api.Loadable
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The one entry for launcher shortcuts, widget taps, and the Quick Settings tile. A pane request goes to the window
 * through [WindowNavigator] and brings the app's window forward, so a request made before sign-in lands after it.
 * Call Puck stays open, translucent, until the call is placed: Android lets the call's microphone service start only
 * while one of the app's activities is in front.
 */
@AndroidEntryPoint
class SurfaceActivity : ComponentActivity() {
    @Inject lateinit var navigator: WindowNavigator
    @Inject lateinit var engines: AmpEngines

    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) placeCall() else finishWith("Amp needs the microphone to call Puck.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (val action = SurfaceAction.parse(intent)) {
            null -> {
                Log.w(TAG, "ignored ${intent.action}")
                finish()
            }
            is SurfaceAction.Navigate -> {
                Log.i(TAG, "opening ${action.destination.route}")
                navigator.open(action.destination)
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                finish()
            }
            SurfaceAction.CallPuck -> if (savedInstanceState == null) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) placeCall() else microphone.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun placeCall() {
        lifecycleScope.launch {
            val roster = engines.roster.first { it !is Loadable.Loading }
            val account = glanceAccount(roster) ?: return@launch finishWith("Sign in to Amp to call Puck.")
            when (val result = callPuck(this@SurfaceActivity, engines.scope(account))) {
                is CallPuckResult.Placed -> {
                    Log.i(TAG, "call puck: placed in ${result.conversation.thread.raw}")
                    finish()
                }
                is CallPuckResult.Busy -> finishWith("You are already on a call with Puck.")
                CallPuckResult.NeedsMicrophone -> finishWith("Amp needs the microphone to call Puck.")
                is CallPuckResult.Failed -> finishWith(result.reason)
            }
        }
    }

    private fun finishWith(message: String) {
        Log.i(TAG, "call puck: $message")
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private companion object {
        const val TAG = "AmpSurface"
    }
}
