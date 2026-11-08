package xyz.elouan.sobosubamodloader.axml

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object AxmlManifestPatcher {
    private const val STRING_POOL = 0x0001
    private const val START_TAG = 0x0102
    private const val UTF8_FLAG = 0x00000100
    private const val TYPE_BOOLEAN = 0x12
    private const val TYPE_STRING = 0x03

    data class Options(
        val packageName: String? = null,
        val appLabel: String? = null
    )

    fun patchApk(input: File, output: File, options: Options = Options()) {
        ZipFile(input).use { source ->
            ZipOutputStream(output.outputStream()).use { destination ->
                source.entries().asSequence().forEach { entry ->
                    // Skip existing signature files
                    if (entry.name.startsWith("META-INF/")) return@forEach

                    // Read entry data
                    val originalData = source.getInputStream(entry).use { it.readBytes() }
                    val entryData = if (entry.name == "AndroidManifest.xml") {
                        patchManifest(originalData, options)
                    } else {
                        originalData
                    }

                    val newEntry = ZipEntry(entry.name)

                    // CRITICAL: Preserve original compression method
                    // resources.arsc and .so files must be STORED (uncompressed) for Android 11+
                    newEntry.method = entry.method

                    if (entry.method == ZipEntry.STORED) {
                        // For STORED entries, we must set size, CRC, and compressedSize manually
                        val crc = java.util.zip.CRC32()
                        crc.update(entryData)
                        newEntry.crc = crc.value
                        newEntry.size = entryData.size.toLong()
                        newEntry.compressedSize = entryData.size.toLong()

                        // CRITICAL: Align to 4-byte boundary (required by Android 11+)
                        // The data offset = 30 (local header) + name_length + extra_length
                        // We add padding bytes via the extra field to align to 4
                        val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
                        val headerSize = 30 + nameBytes.size
                        val paddingNeeded = (4 - (headerSize % 4)) % 4
                        if (paddingNeeded > 0) {
                            newEntry.extra = ByteArray(paddingNeeded)
                        }
                    }

                    destination.putNextEntry(newEntry)
                    destination.write(entryData)
                    destination.closeEntry()
                }
            }
        }
    }

    private fun patchManifest(input: ByteArray, options: Options): ByteArray {
        var bytes = input.copyOf()
        val poolOffset = 8
        require(u16(bytes, poolOffset) == STRING_POOL) { "AndroidManifest.xml is not binary XML" }
        if (options.packageName != null && !readStrings(bytes, poolOffset).contains(options.packageName)) {
            bytes = addString(bytes, poolOffset, options.packageName)
        }
        if (options.appLabel != null && !readStrings(bytes, poolOffset).contains(options.appLabel)) {
            bytes = addString(bytes, poolOffset, options.appLabel)
        }

        var working = bytes
        if (!readStrings(working, poolOffset).contains("debuggable")) {
            working = addString(working, poolOffset, "debuggable")
        }
        if (options.appLabel != null && !readStrings(working, poolOffset).contains("label")) {
            working = addString(working, poolOffset, "label")
        }
        val strings = readStrings(working, poolOffset)
        val debuggableIndex = ensureStringIndex(working, "debuggable")
        val applicationIndex = strings.indexOf("application")
        val manifestIndex = strings.indexOf("manifest")
        val packageAttributeIndex = strings.indexOf("package")
        val labelAttributeIndex = strings.indexOf("label")
        require(applicationIndex >= 0) { "Manifest has no application element" }
        val androidNamespaceIndex = strings.indexOf("http://schemas.android.com/apk/res/android")
        require(androidNamespaceIndex >= 0) { "Manifest has no Android namespace" }
        if (options.packageName != null) {
            require(manifestIndex >= 0 && packageAttributeIndex >= 0) { "Manifest has no package attribute" }
        }

        var offset = chunkEnd(working, poolOffset)
        while (offset + 8 <= working.size) {
            when (u16(working, offset)) {
                START_TAG -> {
                    val nameIndex = i32(working, offset + 20)
                    val attributeCount = u16(working, offset + 28)
                    if (nameIndex == manifestIndex && options.packageName != null) {
                        val attribute = findAttribute(working, offset, attributeCount, packageAttributeIndex)
                        require(attribute >= 0) { "Manifest has no package attribute" }
                        patchStringAttribute(working, attribute, strings.indexOf(options.packageName))
                    }
                    if (nameIndex == applicationIndex) {
                        for (index in 0 until attributeCount) {
                            val attribute = offset + 36 + index * 20
                            if (i32(working, attribute + 4) == debuggableIndex) {
                                putI32(working, attribute + 16, -1)
                                putI32(working, attribute + 12, 0)
                                break
                            }
                        }
                        var result = working
                        var resultCount = attributeCount
                        if (findAttribute(result, offset, resultCount, debuggableIndex) < 0) {
                            result = addAttribute(result, offset, resultCount, androidNamespaceIndex, debuggableIndex, -1, TYPE_BOOLEAN)
                            resultCount++
                        }
                        if (options.appLabel != null) {
                            val labelIndex = strings.indexOf(options.appLabel)
                            val labelAttribute = findAttribute(result, offset, resultCount, labelAttributeIndex)
                            result = if (labelAttribute >= 0) {
                                patchStringAttribute(result, labelAttribute, labelIndex)
                                result
                            } else {
                                addAttribute(result, offset, resultCount, androidNamespaceIndex, labelAttributeIndex, labelIndex, TYPE_STRING)
                            }
                        }
                        return result
                    }
                }
            }
            val size = i32(working, offset + 4)
            require(size > 0) { "Malformed binary XML chunk" }
            offset += size
        }
        error("Application element not found")
    }

    private fun ensureStringIndex(bytes: ByteArray, value: String): Int {
        val index = readStrings(bytes, 8).indexOf(value)
        return if (index >= 0) index else error("Unable to add string: $value")
    }

    private fun findAttribute(bytes: ByteArray, startTagOffset: Int, count: Int, nameIndex: Int): Int {
        for (index in 0 until count) {
            val attribute = startTagOffset + 36 + index * 20
            if (i32(bytes, attribute + 4) == nameIndex) return attribute
        }
        return -1
    }

    private fun patchStringAttribute(bytes: ByteArray, attribute: Int, valueIndex: Int) {
        putI32(bytes, attribute + 8, valueIndex)
        putI32(bytes, attribute + 12, 0)
        bytes[attribute + 15] = TYPE_STRING.toByte()
        putI32(bytes, attribute + 16, valueIndex)
    }

    private fun addAttribute(
        input: ByteArray,
        startTagOffset: Int,
        attributeCount: Int,
        namespaceIndex: Int,
        nameIndex: Int,
        valueIndex: Int,
        type: Int
    ): ByteArray {
        val attribute = ByteArray(20)
        putI32(attribute, 0, namespaceIndex)
        putI32(attribute, 4, nameIndex)
        putI32(attribute, 8, valueIndex)
        attribute[12] = 8
        attribute[13] = 0
        attribute[14] = 0
        attribute[15] = type.toByte()
        putI32(attribute, 16, if (type == TYPE_BOOLEAN) -1 else valueIndex)

        val insertion = startTagOffset + 36 + attributeCount * 20
        val result = ByteArray(input.size + attribute.size)
        input.copyInto(result, 0, 0, insertion)
        attribute.copyInto(result, insertion)
        input.copyInto(result, insertion + attribute.size, insertion, input.size)
        putI32(result, startTagOffset + 4, i32(input, startTagOffset + 4) + attribute.size)
        putI32(result, 4, i32(input, 4) + attribute.size)
        putU16(result, startTagOffset + 28, attributeCount + 1)
        return result
    }

    private fun addString(input: ByteArray, poolOffset: Int, value: String): ByteArray {
        val oldChunkSize = i32(input, poolOffset + 4)
        val strings = readStrings(input, poolOffset) + value
        val dataOutput = ByteArrayOutputStream()
        val offsets = strings.map {
            val offset = dataOutput.size()
            dataOutput.write(encodeUtf8String(it))
            offset
        }
        val data = dataOutput.toByteArray()
        val paddedData = data + ByteArray((4 - data.size % 4) % 4)
        val headerSize = 28
        val newChunkSize = headerSize + offsets.size * 4 + paddedData.size
        val pool = ByteArray(newChunkSize)
        putU16(pool, 0, STRING_POOL)
        putU16(pool, 2, headerSize)
        putI32(pool, 4, newChunkSize)
        putI32(pool, 8, strings.size)
        putI32(pool, 12, 0)
        putI32(pool, 16, UTF8_FLAG)
        putI32(pool, 20, headerSize + offsets.size * 4)
        putI32(pool, 24, 0)
        offsets.forEachIndexed { index, valueOffset -> putI32(pool, 28 + index * 4, valueOffset) }
        paddedData.copyInto(pool, headerSize + offsets.size * 4)

        val result = ByteArray(input.size + newChunkSize - oldChunkSize)
        input.copyInto(result, 0, 0, poolOffset)
        pool.copyInto(result, poolOffset)
        input.copyInto(result, poolOffset + newChunkSize, poolOffset + oldChunkSize, input.size)
        putI32(result, 4, input.size + newChunkSize - oldChunkSize)
        return result
    }

    private fun encodeUtf8String(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream()
        writeLength(output, value.length)
        writeLength(output, bytes.size)
        output.write(bytes)
        output.write(0)
        return output.toByteArray()
    }

    private fun writeLength(output: ByteArrayOutputStream, length: Int) {
        if (length < 0x80) output.write(length)
        else {
            output.write((length shr 8) or 0x80)
            output.write(length and 0xff)
        }
    }

    private fun readStrings(bytes: ByteArray, offset: Int): List<String> {
        val count = i32(bytes, offset + 8)
        val flags = i32(bytes, offset + 16)
        val stringsStart = i32(bytes, offset + 20)
        val utf8 = flags and UTF8_FLAG != 0
        return (0 until count).map { index ->
            val stringOffset = i32(bytes, offset + 28 + index * 4)
            val start = offset + stringsStart + stringOffset
            if (utf8) {
                val utf16LengthBytes = if (bytes[start].toInt() and 0x80 == 0) 1 else 2
                val byteLengthOffset = start + utf16LengthBytes
                val byteLengthBytes = if (bytes[byteLengthOffset].toInt() and 0x80 == 0) 1 else 2
                val byteLength = if (byteLengthBytes == 1) {
                    bytes[byteLengthOffset].toInt() and 0xff
                } else {
                    ((bytes[byteLengthOffset].toInt() and 0x7f) shl 8) or
                        (bytes[byteLengthOffset + 1].toInt() and 0xff)
                }
                val dataStart = byteLengthOffset + byteLengthBytes
                String(bytes, dataStart, byteLength, Charsets.UTF_8)
            } else {
                val length = readUtf16Length(bytes, start)
                val lengthBytes = if (bytes[start].toInt() and 0x80 == 0) 2 else 4
                String(bytes, start + lengthBytes, length * 2, Charsets.UTF_16LE)
            }
        }
    }

    private fun readUtf16Length(bytes: ByteArray, offset: Int): Int {
        val first = u16(bytes, offset)
        return if (first and 0x8000 == 0) first
        else ((first and 0x7fff) shl 16) or u16(bytes, offset + 2)
    }

    private fun chunkEnd(bytes: ByteArray, offset: Int): Int = offset + i32(bytes, offset + 4)
    private fun u16(bytes: ByteArray, offset: Int): Int = ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
    private fun i32(bytes: ByteArray, offset: Int): Int = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun putI32(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }
    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
    }
}
