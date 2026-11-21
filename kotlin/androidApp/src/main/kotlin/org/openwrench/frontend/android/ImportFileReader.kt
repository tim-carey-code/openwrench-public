package org.openwrench.frontend.android

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Immediate bounded stream ownership shared by both picker routes. No retained grant or writes. */
internal object ImportFileReader {
    fun drives(open: () -> InputStream?): List<SavedDrive> =
        (open() ?: error("Cannot open recording")).use(DriveReplayImport::read)

    fun snapshotText(open: () -> InputStream?): String {
        val bytes = (open() ?: error("Cannot open document")).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= SnapshotCodec.MAX_BYTES) { "Snapshot exceeds 1 MiB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }
}
