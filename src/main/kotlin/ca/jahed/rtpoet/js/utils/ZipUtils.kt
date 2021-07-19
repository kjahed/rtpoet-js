package ca.jahed.rtpoet.js.utils

import net.lingala.zip4j.io.inputstream.ZipInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

internal object ZipUtils {
    fun extractZipResource(resource: String, destination: File) {
        if(!destination.exists())
            destination.mkdirs()

        val zip = this::class.java.classLoader.getResourceAsStream(resource)!!
        extractWithZipInputStream(zip, destination)
    }

    private fun extractWithZipInputStream(zipFile: InputStream, destination: File) {
        var readLen: Int
        val readBuffer = ByteArray(4096)

        val zipInputStream = ZipInputStream(zipFile)
        var localFileHeader = zipInputStream.nextEntry

        while (localFileHeader != null) {
            val extractedFile = File(destination, localFileHeader.fileName)
            if (localFileHeader.isDirectory) {
                extractedFile.mkdirs()
            } else {
                FileOutputStream(extractedFile).use { outputStream ->
                    while (zipInputStream.read(readBuffer).also { readLen = it } != -1) {
                        outputStream.write(readBuffer, 0, readLen)
                    }
                }
            }

            localFileHeader = zipInputStream.nextEntry
        }
    }
}