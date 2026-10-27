package com.agentifiers.amp.files

import androidx.core.content.FileProvider

/** Its own class so the manifest merger never collides with another module's `androidx.core.content.FileProvider`. */
class SharedFileProvider : FileProvider()
