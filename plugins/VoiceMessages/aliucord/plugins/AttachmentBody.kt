package com.aliucord.plugins

class AttachmentBody(
    filename: String,
    size: Int
) {
    val files: MutableList<File> = mutableListOf()

    init {
        files.add(File(filename, size))
    }

    class File(
        val filename: String,
        val size: Int
    )
}
