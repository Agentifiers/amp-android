package com.agentifiers.amp.sync.internal.images

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.agentifiers.amp.sync.api.AuthenticatedImageLoader
import com.agentifiers.amp.sync.internal.net.Bearer
import com.agentifiers.amp.sync.internal.net.BearerTokens
import com.agentifiers.amp.sync.internal.net.Endpoint
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.File

/**
 * Coil on the account's OkHttp client. Attachment URLs answer 401 without the Bearer and 200 with it (S12); the
 * Bearer goes only to the account's own origin, so an image hosted elsewhere never sees the token. Images are
 * cached in [cacheDir], which sign-out deletes with the account.
 */
internal class AuthedImages(context: Context, client: OkHttpClient, endpoint: Endpoint, tokens: BearerTokens, cacheDir: File) : AuthenticatedImageLoader {
    private val origin = endpoint.base

    private val authed = client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val bearer = if (sameOrigin(request.url)) runBlocking { tokens.current() } else null
            chain.proceed(if (bearer is Bearer.Token) request.newBuilder().header("Authorization", "Bearer ${bearer.value}").build() else request)
        }
        .build()

    override val coil: ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { authed })) }
        .diskCache { DiskCache.Builder().directory(cacheDir).build() }
        .build()

    private fun sameOrigin(url: HttpUrl) = url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port
}
