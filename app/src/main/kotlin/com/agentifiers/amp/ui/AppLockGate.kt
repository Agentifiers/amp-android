package com.agentifiers.amp.ui

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.AppLockController
import com.agentifiers.amp.LockState
import com.agentifiers.amp.settings.AppLockScreen

/** Shows [content] only while the process is unlocked. The window's saved state survives a lock and unlock. */
@Composable
internal fun AppLockGate(lock: AppLockController, content: @Composable () -> Unit) {
    val state by lock.state.collectAsStateWithLifecycle()
    val saved = rememberSaveableStateHolder()
    when (state) {
        LockState.Checking -> Unit
        LockState.Locked -> {
            val context = LocalContext.current
            var error by remember { mutableStateOf<String?>(null) }
            AppLockScreen(
                onUnlock = {
                    error = null
                    promptUnlock(context, onSuccess = lock::unlock, onError = { error = it })
                },
                error = error,
            )
        }
        LockState.Unlocked -> saved.SaveableStateProvider("window", content)
    }
}

private fun promptUnlock(context: Context, onSuccess: () -> Unit, onError: (String) -> Unit) {
    // Removing the device's screen lock needs the screen lock, so a device without one has nothing to prompt with.
    if (!context.getSystemService(KeyguardManager::class.java).isDeviceSecure) return onSuccess()
    BiometricPrompt.Builder(context)
        .setTitle("Unlock Amp")
        .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        .build()
        .authenticate(
            CancellationSignal(),
            context.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onError(errString.toString())
            },
        )
}
