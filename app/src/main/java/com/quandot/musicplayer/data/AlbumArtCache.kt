/**
 * AlbumArtCache — Embedded artwork extractor, folder-cover resolver, and Coil disk-cache cleaner.
 *
 * @remarks
 * Prioritizes direct file-path `MediaMetadataRetriever` access to avoid `ContentResolver` IPC
 * overhead, then falls back to `AssetFileDescriptor`/`ParcelFileDescriptor` and finally to built-in
 * binary parsers for DSF trailing ID3v2 `APIC` (at `metadata_offset` with unsynchronization),
 * DFF `ID3 ` chunks, FLAC `METADATA_BLOCK_PICTURE` (including ID3v2-wrapped FLAC), ID3v2 `APIC`
 * (`.mp3`, AIFF, WAV), Ogg/Opus Vorbis comments, and MP4/M4A `covr` atoms. Folder-cover discovery
 * ignores shared root and generic media directories so unrelated images do not bleed onto tracks.
 *
 * @see AudioCoverFetcher
 * @see com.quandot.musicplayer.MusicApplication
 */
package com.quandot.musicplayer.data

// External SDK / Jetpack / third-party libraries
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Locale

// Internal modules
import com.quandot.musicplayer.debug.PDLog

class AlbumArtCache(private val context: Context) {

    // ═══ Constants ═══
    private companion object {
        const val IMAGE_CACHE_DIR_NAME = "image_cache"
        const val STREAM_BUFFER_BYTES = 64 * 1024
        const val MAX_FLAC_PICTURE_BYTES = 30 * 1024 * 1024
        const val MAX_ID3_TAG_BYTES = 25 * 1024 * 1024
        const val MAX_OGG_PACKET_BYTES = 10 * 1024 * 1024
        const val MAX_OGG_UNTAGGED_SCAN_BYTES = 256 * 1024
        const val MAX_OGG_UNTAGGED_PAGES = 32
        const val MAX_MP4_PICTURE_BYTES = 25 * 1024 * 1024
        const val MAX_TEMP_SPOOL_BYTES = 64L * 1024L * 1024L
        const val MAX_CONTAINER_WALK_STEPS = 4096
        const val DSF_HEADER_BYTES = 28
        const val DFF_FORM_HEADER_BYTES = 16
        const val DFF_CHUNK_HEADER_BYTES = 12
        const val FLAC_BLOCK_PICTURE = 6

        val OPUS_TAGS_MAGIC = "OpusTags".toByteArray(Charsets.US_ASCII)
        val VORBIS_TAGS_MAGIC = byteArrayOf(
            0x03, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(),
            'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte()
        )
        val PIC_TAG_PREFIX = "METADATA_BLOCK_PICTURE=".toByteArray(Charsets.US_ASCII)
        val COVER_ART_TAG_PREFIX = "COVERART=".toByteArray(Charsets.US_ASCII)
        val STANDARD_COVER_NAMES = listOf(
            "cover.jpg", "folder.jpg", "front.jpg", "album.jpg",
            "cover.png", "folder.png", "front.png", "album.png",
            "cover.jpeg", "folder.jpeg", "front.jpeg", "album.jpeg",
            "cover.webp", "folder.webp", "front.webp", "album.webp",
            "art.jpg", "art.png", "art.jpeg"
        )
    }

    // ═══ Public API ═══

    /**
     * Retained for source compatibility; `cacheDir/album_art_cache` is now managed directly as a
     * bounded WebP L2 disk cache by [AlbumArtManager].
     */
    fun purgeOldBloatedCache() {
        // No-op: album_art_cache now holds bounded WebP-compressed L2 artwork managed by AlbumArtManager.
    }

    /**
     * Extracts raw embedded picture bytes via file path, ContentResolver file descriptors, or
     * direct binary container parsing (DSF trailing ID3v2 `APIC`, DFF `ID3 `, FLAC, ID3v2, Ogg/Opus, MP4).
     */
    fun extractEmbeddedBytes(contentUri: Uri?, songPath: String?): ByteArray? {
        // Fast-path DSF/DFF straight to O(1) FileChannel seek so MediaMetadataRetriever never wastes
        // hundreds of milliseconds scanning multi-hundred-MB 1-bit DSD files it cannot decode.
        val extLower = songPath?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        if (extLower == "dsf") {
            return extractPictureFromSeekableContainer(contentUri, songPath, SeekableContainerType.DSF)
        }
        if (extLower == "dff") {
            return extractPictureFromSeekableContainer(contentUri, songPath, SeekableContainerType.DFF)
        }

        // 1. Prefer direct file path to skip ContentResolver Binder IPC
        if (!songPath.isNullOrBlank()) {
            val file = File(songPath)
            if (file.exists() && file.canRead()) {
                extractWithMmr { it.setDataSource(songPath) }?.let { return it }
            }
        }

        // 2. Fallback via ContentResolver Context + Uri, AssetFileDescriptor & ParcelFileDescriptor
        if (contentUri != null) {
            extractWithMmr { it.setDataSource(context, contentUri) }?.let { return it }

            try {
                context.contentResolver.openAssetFileDescriptor(contentUri, "r")?.use { afd ->
                    extractWithMmr { mmr ->
                        if (afd.declaredLength == AssetFileDescriptor.UNKNOWN_LENGTH && afd.startOffset == 0L) {
                            mmr.setDataSource(afd.fileDescriptor)
                        } else {
                            mmr.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        }
                    }?.let { return it }
                }
            } catch (_: Exception) {}

            try {
                context.contentResolver.openFileDescriptor(contentUri, "r")?.use { pfd ->
                    extractWithMmr { it.setDataSource(pfd.fileDescriptor) }?.let { return it }
                }
            } catch (_: Exception) {}
        }

        // 3. Binary container parser fallback (.dsf, .dff, .opus, .ogg, .flac, .mp3, .m4a, .aiff, .wav)
        return extractPictureFromStream(contentUri, songPath)
    }

    /**
     * Returns `true` if [name] identifies a generic root/library folder or MediaStore placeholder album
     * whose folder images or album-wide art should not bleed across unrelated tracks.
     */
    fun isGenericFolderOrAlbumName(name: String?): Boolean {
        return isGenericAlbumOrFolderName(name)
    }

    /**
     * Locates a cover image in the track's parent directory (skipping shared root/library folders),
     * matching only standard cover filenames (`cover.jpg`, `folder.jpg`, `front.jpg`, `album.jpg`,
     * `.png`, etc.) case-insensitively so unrelated images in the directory never bleed onto tracks.
     */
    fun findFolderCoverFile(songPath: String?): File? {
        if (songPath.isNullOrBlank()) return null
        try {
            val songFile = File(songPath)
            val parentDir = songFile.parentFile ?: return null
            if (!parentDir.exists() || !parentDir.isDirectory) return null

            if (isGenericFolderOrAlbumName(parentDir.name) ||
                parentDir.parentFile?.name.equals("storage", ignoreCase = true)
            ) {
                return null
            }

            var targetImageFile = STANDARD_COVER_NAMES.map { File(parentDir, it) }
                .firstOrNull { it.exists() && it.length() > 0 }

            if (targetImageFile == null) {
                val imageFiles = parentDir.listFiles { f ->
                    val name = f.name.lowercase(Locale.ROOT)
                    f.isFile && f.length() > 0 &&
                        (name.endsWith(".jpg") || name.endsWith(".png") ||
                            name.endsWith(".jpeg") || name.endsWith(".webp"))
                }
                if (!imageFiles.isNullOrEmpty()) {
                    // Match standard cover names case-insensitively (e.g. Cover.JPG, FOLDER.PNG)
                    val byLowerName = imageFiles.associateBy { it.name.lowercase(Locale.ROOT) }
                    targetImageFile = STANDARD_COVER_NAMES.firstNotNullOfOrNull { byLowerName[it] }
                }
            }

            if (targetImageFile != null && targetImageFile.canRead()) {
                return targetImageFile
            }
        } catch (_: Exception) {}
        return null
    }

    fun formatCacheSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return String.format(Locale.US, "%.1f %s", value, units[digitGroups])
    }

    fun clearCache() {
        try {
            AlbumArtManager.getInstance(context).clearAll()
            val coilCache = File(context.cacheDir, IMAGE_CACHE_DIR_NAME)
            if (coilCache.exists()) {
                coilCache.deleteRecursively()
                coilCache.mkdirs()
            }
        } catch (e: Exception) {
            PDLog.e(PDLog.ART_CACHE, "ART_CACHE_CLEAR_ERR", "err=${e.message}", e)
        }
    }

    // ═══ Stream & Container Parsers ═══
    private inline fun extractWithMmr(configure: (MediaMetadataRetriever) -> Unit): ByteArray? {
        val mmr = MediaMetadataRetriever()
        return try {
            configure(mmr)
            mmr.embeddedPicture?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        } finally {
            try {
                mmr.release()
            } catch (_: Throwable) {}
        }
    }

    private fun extractPictureFromStream(contentUri: Uri?, songPath: String?): ByteArray? {
        var input: InputStream? = null
        var seekableType: SeekableContainerType? = null
        try {
            val localFile = songPath?.takeIf { it.isNotBlank() }?.let { File(it) }
            input = if (localFile != null && localFile.exists() && localFile.canRead()) {
                runCatching { FileInputStream(localFile) }.getOrNull()
            } else {
                null
            }
            if (input == null && contentUri != null) {
                input = context.contentResolver.openInputStream(contentUri)
            }

            if (input != null) {
                val buffered = input.buffered(STREAM_BUFFER_BYTES)
                buffered.mark(20)
                val header = ByteArray(16)
                val read = readFully(buffered, header)
                buffered.reset()

                if (read >= 4) {
                    if (isAsciiTag(header, 0, "fLaC")) {
                        return parseFlacPicture(buffered)
                    } else if (header[0] == 0x49.toByte() && header[1] == 0x44.toByte() &&
                        header[2] == 0x33.toByte()
                    ) {
                        parseId3v2Picture(buffered)?.let { return it }
                        // Fall through to trailing fLaC metadata when a FLAC track starts with an APIC-less ID3v2 tag
                        buffered.mark(8)
                        val trailing = ByteArray(4)
                        val trailingRead = readFully(buffered, trailing)
                        buffered.reset()
                        if (trailingRead == 4 && isAsciiTag(trailing, 0, "fLaC")) {
                            return parseFlacPicture(buffered)
                        }
                        return null
                    } else if (isAsciiTag(header, 0, "OggS")) {
                        return parseOggOpusPicture(buffered)
                    } else if (isAsciiTag(header, 0, "DSD ")) {
                        seekableType = SeekableContainerType.DSF
                    } else if (read >= 16 && isAsciiTag(header, 0, "FRM8") && isAsciiTag(header, 12, "DSD ")) {
                        seekableType = SeekableContainerType.DFF
                    } else if (read >= 8 && isAsciiTag(header, 4, "ftyp")) {
                        seekableType = SeekableContainerType.MP4
                    } else if (read >= 12 && isAsciiTag(header, 0, "FORM") &&
                        (isAsciiTag(header, 8, "AIFF") || isAsciiTag(header, 8, "AIFC"))
                    ) {
                        seekableType = SeekableContainerType.AIFF
                    } else if (read >= 12 && isAsciiTag(header, 0, "RIFF") &&
                        isAsciiTag(header, 8, "WAVE")
                    ) {
                        seekableType = SeekableContainerType.WAV
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                input?.close()
            } catch (_: Exception) {}
        }

        if (seekableType != null) {
            return extractPictureFromSeekableContainer(contentUri, songPath, seekableType)
        }
        return null
    }

    private enum class SeekableContainerType {
        MP4,
        AIFF,
        WAV,
        DSF,
        DFF
    }

    private data class Mp4Box(
        val contentStart: Long,
        val end: Long
    )

    private fun extractPictureFromSeekableContainer(
        contentUri: Uri?,
        songPath: String?,
        type: SeekableContainerType
    ): ByteArray? {
        val localFile = songPath?.takeIf { it.isNotBlank() }?.let { File(it) }
        if (localFile != null && localFile.exists() && localFile.canRead()) {
            val result = runCatching {
                FileInputStream(localFile).use { fis ->
                    val channel = fis.channel
                    parseSeekableContainer(channel, channel.size(), type)
                }
            }.getOrNull()
            if (result != null) return result
        }

        if (contentUri != null) {
            val pfdResult = runCatching {
                context.contentResolver.openFileDescriptor(contentUri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { fis ->
                        val channel = fis.channel
                        val size = channel.size()
                        if (size > 0L) parseSeekableContainer(channel, size, type) else null
                    }
                }
            }.getOrNull()
            if (pfdResult != null) return pfdResult

            // Never spool multi-hundred-MB DSF/DFF containers into cacheDir when non-seekable;
            // only fall back to bounded temp spooling for compact MP4/AIFF/WAV pipes.
            if (type == SeekableContainerType.DSF || type == SeekableContainerType.DFF) {
                return null
            }

            // Streamed ContentProviders (e.g. DocumentProvider pipes) may not expose a seekable FileChannel size;
            // spool bounded audio container to a temporary cache file to parse MP4/AIFF/WAV cover atoms cleanly.
            val tempFile = File(context.cacheDir, "ext_cover_probe.tmp")
            return try {
                val copied = context.contentResolver.openInputStream(contentUri)?.use { input ->
                    tempFile.outputStream().use { out ->
                        val buf = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            total += n
                            if (total > MAX_TEMP_SPOOL_BYTES) return@use false
                            out.write(buf, 0, n)
                        }
                    }
                    true
                } ?: false
                if (copied && tempFile.exists() && tempFile.length() > 0L) {
                    FileInputStream(tempFile).use { fis ->
                        val channel = fis.channel
                        parseSeekableContainer(channel, channel.size(), type)
                    }
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            } finally {
                runCatching { tempFile.delete() }
            }
        }
        return null
    }

    private fun parseSeekableContainer(
        channel: FileChannel,
        fileSize: Long,
        type: SeekableContainerType
    ): ByteArray? {
        return when (type) {
            SeekableContainerType.MP4 -> parseMp4CoverPicture(channel, fileSize)
            SeekableContainerType.AIFF -> parseAiffId3Picture(channel, fileSize)
            SeekableContainerType.WAV -> parseWavId3Picture(channel, fileSize)
            SeekableContainerType.DSF -> parseDsfId3Picture(channel, fileSize)
            SeekableContainerType.DFF -> parseDffId3Picture(channel, fileSize)
        }
    }

    /**
     * Reads Sony DSF's 8-byte little-endian `metadata_offset` at header bytes `20..27`, seeks
     * directly to the trailing ID3v2.3/v2.4 tag in O(1), and extracts embedded `APIC` artwork.
     */
    private fun parseDsfId3Picture(channel: FileChannel, fileSize: Long): ByteArray? {
        if (fileSize < DSF_HEADER_BYTES.toLong() + 10L) return null
        val dsdHdr = readChannelBytes(channel, 0L, DSF_HEADER_BYTES) ?: return null
        if (!isAsciiTag(dsdHdr, 0, "DSD ")) return null

        val metadataOffset = ByteBuffer.wrap(dsdHdr, 20, 8).order(ByteOrder.LITTLE_ENDIAN).long
        if (metadataOffset < 80L || metadataOffset + 10L > fileSize) return null

        val id3Hdr = readChannelBytes(channel, metadataOffset, 10) ?: return null
        if (id3Hdr[0] != 0x49.toByte() || id3Hdr[1] != 0x44.toByte() || id3Hdr[2] != 0x33.toByte()) {
            return null
        }
        val tagSize = readSynchsafeInt(id3Hdr, 6)
        if (tagSize <= 0 || tagSize > MAX_ID3_TAG_BYTES) return null

        val readableLen = minOf(fileSize - metadataOffset, 10L + tagSize.toLong()).toInt()
        if (readableLen <= 10) return null
        val id3Bytes = readChannelBytes(channel, metadataOffset, readableLen) ?: return null
        return parseId3v2Picture(ByteArrayInputStream(id3Bytes))
    }

    /**
     * Walks top-level big-endian DSDIFF (`FRM8`/`DSD `) chunks in O(chunks) via direct channel
     * seeks to extract artwork from an optional embedded `ID3 ` chunk if present.
     */
    private fun parseDffId3Picture(channel: FileChannel, fileSize: Long): ByteArray? {
        if (fileSize < DFF_FORM_HEADER_BYTES.toLong() + DFF_CHUNK_HEADER_BYTES.toLong()) return null
        val formHdr = readChannelBytes(channel, 0L, DFF_FORM_HEADER_BYTES) ?: return null
        if (!isAsciiTag(formHdr, 0, "FRM8") || !isAsciiTag(formHdr, 12, "DSD ")) return null

        var offset = DFF_FORM_HEADER_BYTES.toLong()
        var steps = 0
        while (offset + DFF_CHUNK_HEADER_BYTES <= fileSize && steps < MAX_CONTAINER_WALK_STEPS) {
            steps++
            val hdr = readChannelBytes(channel, offset, DFF_CHUNK_HEADER_BYTES) ?: break
            val chunkId = String(hdr, 0, 4, Charsets.ISO_8859_1)
            val chunkSize = ByteBuffer.wrap(hdr, 4, 8).order(ByteOrder.BIG_ENDIAN).long
            val contentStart = offset + DFF_CHUNK_HEADER_BYTES
            if (chunkSize < 0L || contentStart > fileSize) break

            if ((chunkId == "ID3 " || chunkId == "id3 ") &&
                chunkSize in 10L..MAX_ID3_TAG_BYTES.toLong() &&
                contentStart + chunkSize <= fileSize
            ) {
                val id3Bytes = readChannelBytes(channel, contentStart, chunkSize.toInt())
                if (id3Bytes != null) {
                    parseId3v2Picture(ByteArrayInputStream(id3Bytes))?.let { return it }
                }
            }

            val padded = chunkSize + (chunkSize and 1L)
            val next = contentStart + padded
            if (next <= offset) break
            offset = next
        }
        return null
    }

    private fun parseMp4CoverPicture(channel: FileChannel, fileSize: Long): ByteArray? {
        val moov = findMp4ChildBox(channel, 0L, fileSize, "moov") ?: return null
        val udta = findMp4ChildBox(channel, moov.contentStart, moov.end, "udta")
        val meta = if (udta != null) {
            findMp4ChildBox(channel, udta.contentStart, udta.end, "meta")
                ?: findMp4ChildBox(channel, moov.contentStart, moov.end, "meta")
        } else {
            findMp4ChildBox(channel, moov.contentStart, moov.end, "meta")
        } ?: return null

        // ISO BMFF `meta` includes a 4-byte FullBox version/flags header before child boxes,
        // whereas legacy QuickTime `meta` begins child atoms immediately at `contentStart`.
        val metaHeader = readChannelBytes(channel, meta.contentStart, 8) ?: return null
        val startsWithChild = isAsciiTag(metaHeader, 4, "hdlr") || isAsciiTag(metaHeader, 4, "ilst")
        val childrenStart = if (startsWithChild) meta.contentStart else meta.contentStart + 4L
        if (childrenStart >= meta.end) return null

        val ilst = findMp4ChildBox(channel, childrenStart, meta.end, "ilst")
            ?: (if (!startsWithChild) findMp4ChildBox(channel, meta.contentStart, meta.end, "ilst") else null)
            ?: return null
        val covr = findMp4ChildBox(channel, ilst.contentStart, ilst.end, "covr") ?: return null
        val dataBox = findMp4ChildBox(channel, covr.contentStart, covr.end, "data") ?: return null

        // Apple `data` atom prefixes the image payload with 4-byte type indicator + 4-byte locale
        val payloadStart = dataBox.contentStart + 8L
        val payloadLen = dataBox.end - payloadStart
        if (payloadLen !in 16L..MAX_MP4_PICTURE_BYTES.toLong()) return null

        return readChannelBytes(channel, payloadStart, payloadLen.toInt())
    }

    private fun findMp4ChildBox(
        channel: FileChannel,
        rangeStart: Long,
        rangeEnd: Long,
        targetType: String
    ): Mp4Box? {
        var offset = rangeStart
        var steps = 0
        while (offset + 8L <= rangeEnd && steps < MAX_CONTAINER_WALK_STEPS) {
            steps++
            val header = readChannelBytes(channel, offset, 8) ?: break
            val size32 = readBigEndianInt(header, 0).toLong() and 0xFFFFFFFFL
            val boxType = String(header, 4, 4, Charsets.ISO_8859_1)

            val boxSize: Long
            val contentStart: Long
            when {
                size32 == 1L -> {
                    if (offset + 16L > rangeEnd) break
                    val extHeader = readChannelBytes(channel, offset + 8L, 8) ?: break
                    val largeSize = ByteBuffer.wrap(extHeader).order(ByteOrder.BIG_ENDIAN).long
                    if (largeSize < 16L) break
                    boxSize = largeSize
                    contentStart = offset + 16L
                }
                size32 == 0L -> {
                    boxSize = rangeEnd - offset
                    contentStart = offset + 8L
                }
                size32 < 8L -> break
                else -> {
                    boxSize = size32
                    contentStart = offset + 8L
                }
            }

            val boxEnd = minOf(rangeEnd, offset + boxSize)
            if (boxType == targetType && contentStart <= boxEnd) {
                return Mp4Box(contentStart = contentStart, end = boxEnd)
            }

            val nextOffset = offset + boxSize
            if (nextOffset <= offset) break
            offset = nextOffset
        }
        return null
    }

    private fun parseAiffId3Picture(channel: FileChannel, fileSize: Long): ByteArray? {
        return parseIffId3Picture(channel, fileSize, bigEndian = true)
    }

    private fun parseWavId3Picture(channel: FileChannel, fileSize: Long): ByteArray? {
        return parseIffId3Picture(channel, fileSize, bigEndian = false)
    }

    private fun parseIffId3Picture(
        channel: FileChannel,
        fileSize: Long,
        bigEndian: Boolean
    ): ByteArray? {
        var offset = 12L
        var steps = 0
        val byteOrder = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN

        while (offset + 8L <= fileSize && steps < MAX_CONTAINER_WALK_STEPS) {
            steps++
            val header = readChannelBytes(channel, offset, 8) ?: break
            val chunkId = String(header, 0, 4, Charsets.ISO_8859_1)
            val chunkSize = ByteBuffer.wrap(header, 4, 4).order(byteOrder).int.toLong() and 0xFFFFFFFFL
            val contentStart = offset + 8L

            if ((chunkId == "ID3 " || chunkId == "id3 ") &&
                chunkSize in 10L..MAX_ID3_TAG_BYTES.toLong() &&
                contentStart + chunkSize <= fileSize
            ) {
                val id3Bytes = readChannelBytes(channel, contentStart, chunkSize.toInt())
                if (id3Bytes != null) {
                    parseId3v2Picture(ByteArrayInputStream(id3Bytes))?.let { return it }
                }
            }

            val paddedSize = chunkSize + (chunkSize and 1L)
            val nextOffset = contentStart + paddedSize
            if (nextOffset <= offset) break
            offset = nextOffset
        }
        return null
    }

    private fun parseFlacPicture(input: InputStream): ByteArray? {
        val magic = ByteArray(4)
        if (readFully(input, magic) != 4) return null
        if (magic[0] != 0x66.toByte() || magic[1] != 0x4C.toByte() ||
            magic[2] != 0x61.toByte() || magic[3] != 0x43.toByte()
        ) return null

        val header = ByteArray(4)
        while (true) {
            if (readFully(input, header) != 4) break
            val isLast = (header[0].toInt() and 0x80) != 0
            val blockType = header[0].toInt() and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)

            if (blockType == FLAC_BLOCK_PICTURE) {
                if (length < 32 || length > MAX_FLAC_PICTURE_BYTES) return null
                val blockData = ByteArray(length)
                if (readFully(input, blockData) != length) return null

                var offset = 0
                offset += 4 // picture type
                val mimeLen = readBigEndianInt(blockData, offset)
                offset += 4
                offset += mimeLen
                if (offset + 4 > length) return null
                val descLen = readBigEndianInt(blockData, offset)
                offset += 4
                offset += descLen
                offset += 16 // width(4), height(4), depth(4), colors(4)
                if (offset + 4 > length) return null
                val dataLen = readBigEndianInt(blockData, offset)
                offset += 4
                if (dataLen > 0 && offset + dataLen <= length) {
                    return blockData.copyOfRange(offset, offset + dataLen)
                }
                return null
            } else {
                skipFully(input, length.toLong())
            }
            if (isLast) break
        }
        return null
    }

    private fun parseId3v2Picture(input: InputStream): ByteArray? {
        val header = ByteArray(10)
        if (readFully(input, header) != 10) return null
        if (header[0] != 0x49.toByte() || header[1] != 0x44.toByte() || header[2] != 0x33.toByte()) {
            return null
        }
        val major = header[3].toInt() and 0xFF
        val flags = header[5].toInt() and 0xFF
        val tagSize = readSynchsafeInt(header, 6)
        if (tagSize <= 0 || tagSize > MAX_ID3_TAG_BYTES) return null

        val rawBuf = ByteArray(tagSize)
        val bytesRead = readFully(input, rawBuf)
        if (bytesRead <= 0) return null
        // Tolerate trailing ID3v2 tags (common in .dsf files) whose declared tagSize slightly
        // overshoots physical EOF by clamping to the actual readable bytes rather than aborting.
        val rawTagData = if (bytesRead == tagSize) rawBuf else rawBuf.copyOf(bytesRead)

        // Skip optional 10-byte ID3v2.4 footer so the caller remains aligned at the audio header
        if (major == 4 && bytesRead == tagSize && (flags and 0x10) != 0) {
            skipFully(input, 10L)
        }

        val tagUnsync = (flags and 0x80) != 0
        // In ID3v2.3 (and v2.2), tag-level unsynchronization encodes the entire tag body after the 10-byte header.
        // In ID3v2.4, tag-level unsynchronization signals that individual frames are unsynchronized.
        val tagData = if (tagUnsync && major < 4) removeUnsynchronization(rawTagData) else rawTagData
        val effectiveTagSize = tagData.size

        var offset = 0
        if (major >= 3 && (flags and 0x40) != 0 && offset + 4 <= effectiveTagSize) {
            if (major == 3) {
                val extSize = readBigEndianInt(tagData, offset)
                if (extSize >= 0 && offset + 4 + extSize <= effectiveTagSize) {
                    offset += 4 + extSize
                }
            } else if (major == 4) {
                val extSize = readSynchsafeInt(tagData, offset)
                if (extSize >= 6 && offset + extSize <= effectiveTagSize) {
                    offset += extSize
                }
            }
        }

        val frameHeaderLen = if (major == 2) 6 else 10
        var fallbackPicture: ByteArray? = null
        while (offset + frameHeaderLen < effectiveTagSize) {
            if (tagData[offset] == 0.toByte()) break

            val frameId: String
            val frameSize: Int
            var frameFlagsLow = 0
            if (major == 2) {
                frameId = String(tagData, offset, 3, Charsets.ISO_8859_1)
                frameSize = ((tagData[offset + 3].toInt() and 0xFF) shl 16) or
                    ((tagData[offset + 4].toInt() and 0xFF) shl 8) or
                    (tagData[offset + 5].toInt() and 0xFF)
            } else {
                frameId = String(tagData, offset, 4, Charsets.ISO_8859_1)
                frameSize = if (major == 4) {
                    val b4 = tagData[offset + 4].toInt() and 0xFF
                    val b5 = tagData[offset + 5].toInt() and 0xFF
                    val b6 = tagData[offset + 6].toInt() and 0xFF
                    val b7 = tagData[offset + 7].toInt() and 0xFF
                    // Tolerate taggers that emit raw 32-bit big-endian sizes inside ID3v2.4 frames
                    if ((b4 or b5 or b6 or b7) and 0x80 != 0) {
                        readBigEndianInt(tagData, offset + 4)
                    } else {
                        readSynchsafeInt(tagData, offset + 4)
                    }
                } else {
                    readBigEndianInt(tagData, offset + 4)
                }
                frameFlagsLow = tagData[offset + 9].toInt() and 0xFF
            }

            offset += frameHeaderLen
            if (frameSize <= 0 || offset >= effectiveTagSize) break

            val isPictureFrame = (major == 2 && frameId == "PIC") || (major >= 3 && frameId == "APIC")
            val frameTruncated = offset + frameSize > effectiveTagSize
            if (frameTruncated && !isPictureFrame) break

            val frameEnd = if (frameTruncated) effectiveTagSize else (offset + frameSize)
            if (isPictureFrame) {
                var payloadStart = offset
                if (major == 4) {
                    if ((frameFlagsLow and 0x40) != 0 && frameEnd - payloadStart > 1) {
                        payloadStart += 1 // Group identifier byte
                    }
                    // ID3v2.4 Data Length Indicator flag (bit 0) prepends a 4-byte synchsafe integer
                    if ((frameFlagsLow and 0x01) != 0 && frameEnd - payloadStart > 4) {
                        payloadStart += 4
                    }
                }
                if (payloadStart < frameEnd) {
                    var picData = tagData.copyOfRange(payloadStart, frameEnd)
                    if (major == 4 && (tagUnsync || (frameFlagsLow and 0x02) != 0)) {
                        picData = removeUnsynchronization(picData)
                    }
                    val parsed = if (major == 2) extractImageFromPic(picData) else extractImageFromApic(picData)
                    if (parsed != null) {
                        if (parsed.second == 3) return parsed.first // Prefer Front Cover (type 0x03)
                        if (fallbackPicture == null) fallbackPicture = parsed.first
                    }
                }
            }
            if (frameTruncated) break
            offset = frameEnd
        }
        return fallbackPicture
    }

    /**
     * Removes ID3v2 unsynchronization (`0xFF 0x00` -> `0xFF`) without allocating when absent.
     */
    private fun removeUnsynchronization(src: ByteArray): ByteArray {
        var unsyncCount = 0
        for (i in 0 until src.size - 1) {
            if (src[i] == 0xFF.toByte() && src[i + 1] == 0x00.toByte()) {
                unsyncCount++
            }
        }
        if (unsyncCount == 0) return src
        val dst = ByteArray(src.size - unsyncCount)
        var outIdx = 0
        var i = 0
        while (i < src.size) {
            dst[outIdx++] = src[i]
            if (src[i] == 0xFF.toByte() && i + 1 < src.size && src[i + 1] == 0x00.toByte()) {
                i += 2
            } else {
                i++
            }
        }
        return dst
    }

    private fun extractImageFromApic(apic: ByteArray): Pair<ByteArray, Int>? {
        if (apic.size < 5) return null
        val encoding = apic[0].toInt() and 0xFF
        var pos = 1
        while (pos < apic.size && apic[pos] != 0.toByte()) {
            pos++
        }
        pos++ // skip MIME null terminator
        if (pos >= apic.size) return null
        val pictureType = apic[pos].toInt() and 0xFF
        pos++ // skip picture type
        if (pos >= apic.size) return null

        if (encoding == 1 || encoding == 2) {
            while (pos + 1 < apic.size) {
                if (apic[pos] == 0.toByte() && apic[pos + 1] == 0.toByte()) {
                    pos += 2
                    break
                }
                pos += 2
            }
        } else {
            while (pos < apic.size && apic[pos] != 0.toByte()) {
                pos++
            }
            pos++
        }

        if (pos < apic.size) {
            val payload = apic.copyOfRange(pos, apic.size)
            if (hasSupportedImageMagic(payload)) {
                return payload to pictureType
            }
        }

        // Fallback: scan APIC payload for embedded JPEG (FF D8 FF) or PNG (89 50 4E 47) signature
        val magicOffset = findEmbeddedImageMagicOffset(apic, 1)
        if (magicOffset in 1 until apic.size) {
            return apic.copyOfRange(magicOffset, apic.size) to pictureType
        }
        return null
    }

    /**
     * Extracts image bytes and picture type from an ID3v2.2 `PIC` frame payload:
     * `[encoding:1][imageFormat:3 (e.g. JPG/PNG)][pictureType:1][description\0][imageData]`.
     */
    private fun extractImageFromPic(pic: ByteArray): Pair<ByteArray, Int>? {
        if (pic.size < 6) return null
        val encoding = pic[0].toInt() and 0xFF
        val pictureType = pic[4].toInt() and 0xFF
        var pos = 5
        if (encoding == 1 || encoding == 2) {
            while (pos + 1 < pic.size) {
                if (pic[pos] == 0.toByte() && pic[pos + 1] == 0.toByte()) {
                    pos += 2
                    break
                }
                pos += 2
            }
        } else {
            while (pos < pic.size && pic[pos] != 0.toByte()) {
                pos++
            }
            pos++
        }

        if (pos < pic.size) {
            val payload = pic.copyOfRange(pos, pic.size)
            if (hasSupportedImageMagic(payload)) {
                return payload to pictureType
            }
        }

        val magicOffset = findEmbeddedImageMagicOffset(pic, 5)
        if (magicOffset in 5 until pic.size) {
            return pic.copyOfRange(magicOffset, pic.size) to pictureType
        }
        return null
    }

    private fun hasSupportedImageMagic(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val isJpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        val isPng = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        val isWebp = bytes.size >= 12 && isAsciiTag(bytes, 0, "RIFF") && isAsciiTag(bytes, 8, "WEBP")
        return isJpeg || isPng || isWebp
    }

    private fun findEmbeddedImageMagicOffset(bytes: ByteArray, startOffset: Int): Int {
        val searchLimit = minOf(bytes.size - 4, startOffset + 256)
        for (i in startOffset..searchLimit) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD8.toByte() && bytes[i + 2] == 0xFF.toByte()) {
                return i
            }
            if (bytes[i] == 0x89.toByte() && bytes[i + 1] == 0x50.toByte() &&
                bytes[i + 2] == 0x4E.toByte() && bytes[i + 3] == 0x47.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    private fun parseOggOpusPicture(input: InputStream): ByteArray? {
        try {
            val packetData = ByteArrayOutputStream()
            var foundTags = false
            var pagesScanned = 0
            var bytesScanned = 0
            val pageHeader = ByteArray(27)

            while (pagesScanned < MAX_CONTAINER_WALK_STEPS) {
                pagesScanned++
                if (readFully(input, pageHeader) != 27) break
                if (pageHeader[0] != 0x4F.toByte() || pageHeader[1] != 0x67.toByte() ||
                    pageHeader[2] != 0x67.toByte() || pageHeader[3] != 0x53.toByte()
                ) {
                    break
                }

                val numSegments = pageHeader[26].toInt() and 0xFF
                val segTable = ByteArray(numSegments)
                if (readFully(input, segTable) != numSegments) break

                var pagePayloadLen = 0
                for (b in segTable) {
                    pagePayloadLen += (b.toInt() and 0xFF)
                }

                val pagePayload = ByteArray(pagePayloadLen)
                if (readFully(input, pagePayload) != pagePayloadLen) break
                bytesScanned += 27 + numSegments + pagePayloadLen

                if (!foundTags) {
                    val opusTagsIndex = indexOf(pagePayload, OPUS_TAGS_MAGIC)
                    val vorbisIndex = if (opusTagsIndex == -1) indexOf(pagePayload, VORBIS_TAGS_MAGIC) else -1
                    if (opusTagsIndex != -1 || vorbisIndex != -1) {
                        foundTags = true
                        val tagStart = if (opusTagsIndex != -1) opusTagsIndex else vorbisIndex
                        packetData.write(pagePayload, tagStart, pagePayloadLen - tagStart)
                        if (segTable.isNotEmpty() && (segTable.last().toInt() and 0xFF) < 255) {
                            break
                        }
                    } else if (pagesScanned >= MAX_OGG_UNTAGGED_PAGES || bytesScanned >= MAX_OGG_UNTAGGED_SCAN_BYTES) {
                        break
                    }
                } else {
                    packetData.write(pagePayload, 0, pagePayloadLen)
                    if (segTable.isNotEmpty() && (segTable.last().toInt() and 0xFF) < 255) {
                        break
                    }
                }

                if (packetData.size() > MAX_OGG_PACKET_BYTES) break
            }

            if (packetData.size() == 0) return null
            return parseVorbisCommentPicture(packetData.toByteArray())
        } catch (_: Exception) {
            return null
        }
    }

    private fun parseVorbisCommentPicture(packet: ByteArray): ByteArray? {
        try {
            var offset = 0
            if (packet.size < 8) return null

            if (startsWith(packet, 0, OPUS_TAGS_MAGIC)) {
                offset = 8
            } else if (startsWith(packet, 0, VORBIS_TAGS_MAGIC)) {
                offset = 7
            } else {
                val idx = indexOf(packet, OPUS_TAGS_MAGIC)
                if (idx != -1) offset = idx + 8
                else return null
            }

            if (offset + 4 > packet.size) return null
            val vendorLen = readInt32LE(packet, offset)
            offset += 4 + vendorLen
            if (offset + 4 > packet.size || vendorLen < 0) return null

            val commentCount = readInt32LE(packet, offset)
            offset += 4
            if (commentCount < 0) return null

            for (i in 0 until commentCount) {
                if (offset + 4 > packet.size) break
                val commentLen = readInt32LE(packet, offset)
                offset += 4
                if (commentLen <= 0 || offset + commentLen > packet.size) break

                if (startsWithIgnoreCase(packet, offset, PIC_TAG_PREFIX)) {
                    val b64Offset = offset + PIC_TAG_PREFIX.size
                    val b64Len = commentLen - PIC_TAG_PREFIX.size
                    val b64String = String(packet, b64Offset, b64Len, Charsets.US_ASCII)
                    val flacBlock = Base64.decode(b64String, Base64.DEFAULT)
                    return parseFlacPictureFromBlock(flacBlock)
                } else if (startsWithIgnoreCase(packet, offset, COVER_ART_TAG_PREFIX)) {
                    val b64Offset = offset + COVER_ART_TAG_PREFIX.size
                    val b64Len = commentLen - COVER_ART_TAG_PREFIX.size
                    val b64String = String(packet, b64Offset, b64Len, Charsets.US_ASCII)
                    return Base64.decode(b64String, Base64.DEFAULT)
                }

                offset += commentLen
            }
        } catch (_: Exception) {}
        return null
    }

    private fun parseFlacPictureFromBlock(block: ByteArray): ByteArray? {
        try {
            if (block.size < 32) return null
            val mimeLen = readBigEndianInt(block, 4)
            if (mimeLen < 0 || 8 + mimeLen + 4 > block.size) return null
            val descLen = readBigEndianInt(block, 8 + mimeLen)
            val dataOffset = 12 + mimeLen + descLen + 16
            if (dataOffset + 4 > block.size || descLen < 0) return null
            val picLen = readBigEndianInt(block, dataOffset)
            if (picLen <= 0 || dataOffset + 4 + picLen > block.size) return null
            return block.copyOfRange(dataOffset + 4, dataOffset + 4 + picLen)
        } catch (_: Exception) {
            return null
        }
    }

    // ═══ Binary I/O Helpers ═══
    private fun readSynchsafeInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun readBigEndianInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun readChannelBytes(channel: FileChannel, offset: Long, length: Int): ByteArray? {
        if (offset < 0L || length <= 0) return null
        val data = ByteArray(length)
        val buffer = ByteBuffer.wrap(data)
        var pos = offset
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, pos)
            if (read <= 0) return null
            pos += read
        }
        return data
    }

    private fun isAsciiTag(bytes: ByteArray, offset: Int, tag: String): Boolean {
        if (offset + tag.length > bytes.size) return false
        for (i in tag.indices) {
            if (bytes[offset + i] != tag[i].code.toByte()) return false
        }
        return true
    }

    private fun readInt32LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val read = input.read(buffer, totalRead, buffer.size - totalRead)
            if (read < 0) break
            totalRead += read
        }
        return totalRead
    }

    private fun skipFully(input: InputStream, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) break
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun indexOf(source: ByteArray, target: ByteArray): Int {
        if (target.isEmpty() || source.size < target.size) return -1
        val limit = source.size - target.size
        for (i in 0..limit) {
            var match = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    private fun startsWith(source: ByteArray, offset: Int, target: ByteArray): Boolean {
        if (offset + target.size > source.size) return false
        for (i in target.indices) {
            if (source[offset + i] != target[i]) return false
        }
        return true
    }

    private fun startsWithIgnoreCase(source: ByteArray, offset: Int, target: ByteArray): Boolean {
        if (offset + target.size > source.size) return false
        for (i in target.indices) {
            val a = source[offset + i].toInt().toChar().lowercaseChar()
            val b = target[i].toInt().toChar().lowercaseChar()
            if (a != b) return false
        }
        return true
    }
}
