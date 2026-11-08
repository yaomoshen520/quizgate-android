package com.quizgate.spike.diagnostics

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Only fixed filenames in the app's private directory; replace the oldest at the limit. */
class BoundedLogStore(
    private val directory: File,
    private val segmentBytes: Int = 256 * 1_024,
    private val segmentCount: Int = 3
) {
    init { require(segmentBytes >= 32 && segmentCount in 1..5) }
    private fun segment(index: Int) = File(directory, "app-$index.log")

    @Synchronized
    fun append(message: String) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create log directory" }
        var line = LogSanitizer.clean(message)
        while (line.toByteArray(Charsets.UTF_8).size + 1 > segmentBytes) line = line.dropLast(1)
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        if (segment(0).length() + bytes.size > segmentBytes) rotate()
        segment(0).appendBytes(bytes)
    }

    private fun rotate() {
        Files.deleteIfExists(segment(segmentCount - 1).toPath())
        for (index in segmentCount - 2 downTo 0) {
            val source = segment(index)
            if (source.exists()) Files.move(source.toPath(), segment(index + 1).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Bounded copy under the lock; user-selected storage is written after releasing it. */
    @Synchronized
    fun snapshot(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        for (index in segmentCount - 1 downTo 0) {
            val source = segment(index)
            if (source.isFile) source.inputStream().use { input ->
                val buffer = ByteArray(4_096)
                var remaining = segmentBytes
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
        }
        return output.toByteArray()
    }
}
