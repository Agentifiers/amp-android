package com.agentifiers.amp.fake

import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/** Host mode: `./gradlew :fake-amp:run --args='<scenario> <port>'`. An emulator reaches it at `http://10.0.2.2:<port>`. */
public fun main(args: Array<String>) {
    val scenario = args.getOrNull(0)
    if (scenario == null) {
        System.err.println("usage: fake-amp <scenario> [port]\nscenarios: ${FakeAmp.scenarios.joinToString()}")
        exitProcess(2)
    }
    val server = try {
        FakeAmp.start(scenario, port = args.getOrNull(1)?.toInt() ?: 0)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(2)
    }
    val stopped = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread {
        server.close()
        stopped.countDown()
    })
    println("fake-amp $scenario serving ${server.ampBaseUrl} (actors ${server.actorsBaseUrl}); Ctrl-C stops it")
    stopped.await()
}
