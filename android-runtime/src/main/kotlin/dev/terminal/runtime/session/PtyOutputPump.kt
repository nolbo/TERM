package dev.terminal.runtime.session

import java.io.IOException
import java.io.InputStream

/** Reads a PTY until EOF while containing the expected IOException caused by closing it. */
internal suspend fun pumpPtyOutput(
    inputStream: InputStream,
    onOutput: suspend (ByteArray) -> Unit,
    onReadFailure: (IOException) -> Unit,
) {
    try {
        inputStream.use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                onOutput(buffer.copyOf(count))
            }
        }
    } catch (error: IOException) {
        onReadFailure(error)
    }
}
