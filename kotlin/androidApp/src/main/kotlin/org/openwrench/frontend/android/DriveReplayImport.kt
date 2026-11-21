package org.openwrench.frontend.android

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/** Reads selected evidence into memory only. Import never creates or modifies drive history. */
object DriveReplayImport {
    const val MAX_INPUT_BYTES = 25_000_000
    const val MAX_UNCOMPRESSED_BYTES = 25_000_000
    const val MAX_DRIVES = 20
    const val MAX_ENTRIES = 100
    private val drivePath = Regex("drives/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.json")

    /** The caller owns [input]. Unsupported, ambiguous or damaged evidence fails the whole import. */
    fun read(input: InputStream): List<SavedDrive> {
        val bytes = readBounded(input, MAX_INPUT_BYTES)
        if (bytes.size < 2 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte()) {
            require(bytes.size <= DriveHistoryCodec.MAX_FILE_BYTES) { "Drive archive is too large" }
            return listOf(DriveHistoryCodec.decode(utf8(bytes)))
        }
        val directory = zipDirectory(bytes)
        val drives = mutableListOf<SavedDrive>()
        val names = mutableSetOf<String>()
        val ids = mutableSetOf<String>()
        var total = 0L
        var count = 0
        ZipInputStream(bytes.inputStream(), Charsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= MAX_ENTRIES) { "ZIP contains too many entries" }
                val name = entry.name
                require(names.add(name)) { "ZIP contains duplicate entries" }
                validatePath(name)
                val expected = directory[name] ?: error("ZIP directory does not match its entries")
                val match = drivePath.matchEntire(name)
                require(!name.startsWith("drives/") || entry.isDirectory || match != null) {
                    "Unsupported drive archive entry"
                }
                val output = if (match != null) ByteArrayOutputStream() else null
                var size = 0L
                val buffer = ByteArray(8192)
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    size += n
                    total += n
                    require(total <= MAX_UNCOMPRESSED_BYTES) { "ZIP expanded data is too large" }
                    require(output == null || size <= DriveHistoryCodec.MAX_FILE_BYTES) { "Drive archive is too large" }
                    output?.write(buffer, 0, n)
                }
                require(size == expected.size && entry.crc == expected.crc && entry.compressedSize == expected.compressedSize) {
                    "ZIP directory does not match its entries"
                }
                if (output != null) {
                    require(drives.size < MAX_DRIVES) { "ZIP contains too many drives" }
                    val drive = DriveHistoryCodec.decode(utf8(output.toByteArray()))
                    require(ids.add(drive.id)) { "ZIP contains duplicate drive IDs" }
                    require(drive.id == match!!.groupValues[1]) { "Drive ID does not match its archive filename" }
                    drives += drive
                }
                zip.closeEntry()
            }
        }
        require(names == directory.keys) { "ZIP is incomplete" }
        require(drives.isNotEmpty()) { "No saved drive recordings found in this ZIP" }
        return drives.sortedByDescending { it.startedAtEpochMillis }
    }

    private fun readBounded(input: InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer, 0, minOf(buffer.size, maximum - output.size() + 1))
            if (n < 0) return output.toByteArray()
            require(n != 0) { "Input stream made no progress" }
            require(output.size() + n <= maximum) { "Selected replay file is too large" }
            output.write(buffer, 0, n)
        }
    }

    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun validatePath(name: String) {
        require(name.isNotEmpty() && !name.startsWith('/') && '\\' !in name && ':' !in name &&
            name.none { it.code < 32 } && name.removeSuffix("/").split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Unsafe ZIP entry path"
        }
    }

    private data class ZipRecord(val size: Long, val compressedSize: Long, val crc: Long)

    // ZipInputStream alone accepts missing central directories. Require a complete, single-disk,
    // non-ZIP64 directory and cross-check it against the fully consumed (CRC-checked) entries.
    private fun zipDirectory(bytes: ByteArray): Map<String, ZipRecord> {
        fun u16(at: Int): Int {
            require(at >= 0 && at + 2 <= bytes.size) { "Truncated ZIP" }
            return (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
        }
        fun u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 22 - 65535)).firstOrNull {
            u32(it) == 0x06054b50L && it + 22 + u16(it + 20) == bytes.size
        } ?: error("ZIP is incomplete or malformed")
        require(u16(end + 4) == 0 && u16(end + 6) == 0) { "Multi-disk ZIP is unsupported" }
        val count = u16(end + 10)
        require(count <= MAX_ENTRIES && count == u16(end + 8)) { "ZIP contains too many or inconsistent entries" }
        val start = u32(end + 16)
        require(start + u32(end + 12) == end.toLong()) { "Invalid ZIP directory" }
        var cursor = start.toInt()
        val result = linkedMapOf<String, ZipRecord>()
        repeat(count) {
            require(cursor >= 0 && cursor.toLong() + 46 <= end && u32(cursor) == 0x02014b50L) { "Invalid ZIP directory entry" }
            val flags = u16(cursor + 8)
            require(flags and 1 == 0 && u16(cursor + 10) in setOf(0, 8) && u16(cursor + 34) == 0) {
                "Encrypted or unsupported ZIP entry"
            }
            val nameSize = u16(cursor + 28)
            val next = cursor + 46 + nameSize + u16(cursor + 30) + u16(cursor + 32)
            require(next <= end) { "Truncated ZIP directory entry" }
            val name = utf8(bytes.copyOfRange(cursor + 46, cursor + 46 + nameSize))
            validatePath(name)
            val local = u32(cursor + 42)
            require(local + 30 <= start && u32(local.toInt()) == 0x04034b50L) { "Invalid ZIP local entry" }
            require(u16(local.toInt() + 6) == flags && u16(local.toInt() + 8) == u16(cursor + 10)) {
                "ZIP local and directory encoding differ"
            }
            val localNameSize = u16(local.toInt() + 26)
            require(local + 30 + localNameSize + u16(local.toInt() + 28) <= start) { "Invalid ZIP local header" }
            require(utf8(bytes.copyOfRange(local.toInt() + 30, local.toInt() + 30 + localNameSize)) == name) {
                "ZIP local and directory filenames differ"
            }
            val record = ZipRecord(u32(cursor + 24), u32(cursor + 20), u32(cursor + 16))
            require(record.size <= MAX_UNCOMPRESSED_BYTES && record.compressedSize <= MAX_INPUT_BYTES) { "ZIP entry is too large" }
            require(result.put(name, record) == null) { "ZIP contains duplicate entries" }
            cursor = next
        }
        require(cursor == end) { "Invalid ZIP directory length" }
        return result
    }
}
