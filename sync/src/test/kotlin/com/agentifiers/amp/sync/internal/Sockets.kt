package com.agentifiers.amp.sync.internal

import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.ConnectException
import java.net.Socket
import java.net.SocketAddress
import java.util.Collections
import javax.net.SocketFactory

/**
 * An OkHttp client whose sockets a test can cut at teardown. A graceful WebSocket close waits for the fake's reply,
 * and MockWebServer's shutdown gives up after 5 s while one is still open.
 */
internal class CuttableClient {
    private val opened = Collections.synchronizedList(mutableListOf<Socket>())

    val client: OkHttpClient = OkHttpClient.Builder().socketFactory(object : SocketFactory() {
        override fun createSocket(): Socket = Socket().also(opened::add)
        override fun createSocket(host: String, port: Int): Socket = Socket(host, port).also(opened::add)
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
            Socket(host, port, local, localPort).also(opened::add)
        override fun createSocket(host: InetAddress, port: Int): Socket = Socket(host, port).also(opened::add)
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
            Socket(host, port, local, localPort).also(opened::add)
    }).build()

    fun cut() {
        synchronized(opened) { opened.forEach { it.close() } }
    }
}

/** A client whose every connection is refused, as by a host with nothing listening, with no free port to race for. */
internal val refusingClient: OkHttpClient = OkHttpClient.Builder().socketFactory(object : SocketFactory() {
    private fun refused(): Nothing = throw ConnectException("Connection refused")
    override fun createSocket(): Socket = object : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int) = refused()
    }
    override fun createSocket(host: String, port: Int): Socket = refused()
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = refused()
    override fun createSocket(host: InetAddress, port: Int): Socket = refused()
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = refused()
}).build()