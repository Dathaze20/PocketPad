package com.dathaze.pocketpad.ui

import java.io.File

/**
 * Reads a file out of `app/src/main` so a JVM test can check something that only exists in a
 * layout, a resource or the shape of the source.
 *
 * Gradle runs unit tests from different working directories depending on how it was invoked, so
 * the candidates below cover the ones this project is actually run from.
 */
object TestSources {

    fun read(relative: String): String {
        val candidates = listOf(
            "src/main/$relative",
            "app/src/main/$relative",
            "../app/src/main/$relative",
        )
        val found = candidates.map(::File).firstOrNull { it.isFile }
        requireNotNull(found) { "$relative not found from ${File(".").absolutePath}" }
        return found.readText()
    }
}
