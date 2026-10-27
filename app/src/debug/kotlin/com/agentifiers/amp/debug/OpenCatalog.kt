package com.agentifiers.amp.debug

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.catalog.AmpCatalog
import com.agentifiers.amp.design.catalog.CatalogSection
import kotlinx.coroutines.delay
import org.json.JSONObject

/** `open-catalog {width?, section?}`: shows the :core:design catalog in a pane `width` dp wide (default 382), one section or all. */
object OpenCatalog : AppEventHandler {
    override val name = "open-catalog"

    override suspend fun handle(payload: JSONObject, app: AppEventContext): String {
        val width = payload.optInt("width", 382)
        if (width !in 240..1200) throw BadPayload("width $width is outside 240..1200 dp")
        val key = payload.optString("section").takeIf { it.isNotEmpty() }
        if (key != null && CatalogSection.parse(key) == null) {
            throw BadPayload("unknown section \"$key\"; known: ${CatalogSection.entries.joinToString { it.key }}")
        }
        // The event activity brings the app's window forward as it finishes; opening first would put the catalog under it.
        delay(1_500)
        app.context.startActivity(
            Intent(app.context, CatalogActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(CatalogActivity.WIDTH, width)
                .putExtra(CatalogActivity.SECTION, key),
        )
        return "opening the catalog at ${width}dp, ${key ?: "all sections"}"
    }
}

class CatalogActivity : ComponentActivity() {
    private var width by mutableStateOf(382)
    private var section by mutableStateOf<CatalogSection?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        read(intent)
        setContent { AmpTheme { AmpCatalog(width.dp, section) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    private fun read(intent: Intent) {
        width = intent.getIntExtra(WIDTH, 382)
        section = intent.getStringExtra(SECTION)?.let(CatalogSection::parse)
    }

    companion object {
        const val WIDTH = "width"
        const val SECTION = "section"
    }
}
