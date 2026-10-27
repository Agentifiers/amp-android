package com.agentifiers.amp.sync.internal

import com.agentifiers.amp.fake.FakeAmp
import com.agentifiers.amp.model.Children
import com.agentifiers.amp.model.FileContent
import com.agentifiers.amp.model.FileNode
import com.agentifiers.amp.sync.api.EngineError
import com.agentifiers.amp.sync.api.Outcome
import com.agentifiers.amp.sync.internal.MachineHarness.Companion.GATE_THREAD
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Instant

class FilesEngineTest {

    @Test
    fun treeReadAndUploadAgainstTheS19Workspace() = runBlocking {
        FakeAmp.start("files", servedAt = FakeAmp.RECORDED_AT).use { server ->
            MachineHarness(server).use { harness ->
                val files = harness.thread(GATE_THREAD).machine.files

                val root = withTimeout(20_000) { files.tree(".").first { (it as FileNode.Dir).children != Children.Loading } }
                val recorded = persistentListOf(
                    FileNode.Dir(".git", Children.NotLoaded),
                    FileNode.Dir("__pycache__", Children.NotLoaded),
                    FileNode.File("calc.py", Instant.fromEpochMilliseconds(1_792_729_569_472)),
                )
                assertEquals(FileNode.Dir(".", Children.Loaded(recorded)), root)

                assertEquals(
                    Outcome.Ok(FileContent.Text("calc.py", "def add_numbers(a, b):\n    return a + b\n", "python")),
                    files.read("calc.py"),
                )

                val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789\n"
                val random = Random(19)
                val big = ByteArray(1_048_576) { alphabet[random.nextInt(alphabet.length)].code.toByte() }
                assertEquals(Outcome.Ok("big.txt"), files.upload(".", "big.txt", flowOf(big), big.size.toLong(), overwrite = false))
                val readBack = files.read("big.txt") as Outcome.Ok
                assertArrayEquals(big, (readBack.value as FileContent.Text).text.encodeToByteArray())
                assertArrayEquals(big, server.workspaceFile(GATE_THREAD, "big.txt"))

                val listed = withTimeout(20_000) {
                    files.tree(".").first { node -> ((node as FileNode.Dir).children as? Children.Loaded)?.nodes?.any { it.path == "big.txt" } == true }
                }
                assertEquals(
                    listOf(".git", "__pycache__", "big.txt", "calc.py"),
                    ((listed as FileNode.Dir).children as Children.Loaded).nodes.map { it.path },
                )

                assertEquals(
                    Outcome.Failed(EngineError.Server("ALREADY_EXISTS", "File already exists")),
                    files.upload(".", "big.txt", flowOf(big), big.size.toLong(), overwrite = false),
                )

                val tooBig = ByteArray(1_048_577)
                assertEquals(
                    Outcome.Refused("Files over 1 MiB can't be written directly yet."),
                    files.upload(".", "too-big.bin", flowOf(tooBig), tooBig.size.toLong(), overwrite = false),
                )
                assertNull(server.workspaceFile(GATE_THREAD, "too-big.bin"))
            }
        }
    }
}
