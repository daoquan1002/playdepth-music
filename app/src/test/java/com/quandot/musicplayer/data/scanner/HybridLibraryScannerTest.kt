/**
 * HybridLibraryScannerTest — JVM unit tests for incremental delta diffing, two-tier deduplication,
 * and single-open byte-level [AudioMetadataParser] container decoding.
 *
 * @remarks
 * Verifies core invariants without requiring an Android emulator: unchanged tracks skip I/O,
 * modified/new/deleted tracks classify accurately, Tier 1 and Tier 2 merge cleanly by normalized
 * path, Vietnamese UTF-8/ID3v2/Vorbis/APEv2 tags decode cleanly, Hi-Res FLAC/WAV/DSF/WavPack
 * headers resolve exact sample rate/bit depth/duration, and 0-byte or corrupted files fail safely.
 *
 * @see HybridLibraryScanner
 * @see AudioMetadataParser
 */
package com.quandot.musicplayer.data.scanner

// External SDK / Jetpack / third-party libraries
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Internal modules
import com.quandot.musicplayer.data.ArtResolutionBucket
import com.quandot.musicplayer.data.computeSongArtworkKey
import com.quandot.musicplayer.data.isStandaloneTrackAlbum

// ═══ Unit Tests ═══
class HybridLibraryScannerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ═══ Delta Diffing & Two-Tier Deduplication ═══

    @Test
    fun mergeTwoTierCandidates_deduplicatesAndEnrichesMissingStats() {
        val tier1 = listOf(
            ScannedFileCandidate(
                lookupKey = "/storage/emulated/0/Music/track1.flac",
                path = "/storage/emulated/0/Music/track1.flac",
                contentUri = null,
                mediaStoreId = 101L,
                displayName = "track1.flac",
                fileSize = 0L,
                dateModifiedSec = 0L,
                dateAddedSec = 1000L,
                generationModified = 5L,
                durationMs = 240_000L,
                sourceTier = ScanSourceTier.MEDIA_STORE
            )
        )
        val tier2 = listOf(
            ScannedFileCandidate(
                lookupKey = "/storage/emulated/0/Music/track1.flac",
                path = "/storage/emulated/0/Music/track1.flac",
                contentUri = null,
                mediaStoreId = null,
                displayName = "track1.flac",
                fileSize = 35_000_000L,
                dateModifiedSec = 1700000100L,
                dateAddedSec = 1700000100L,
                generationModified = 0L,
                durationMs = 0L,
                sourceTier = ScanSourceTier.DIRECT_FILE
            ),
            ScannedFileCandidate(
                lookupKey = "/storage/emulated/0/Hidden/.nomedia_album/dsd.dsf",
                path = "/storage/emulated/0/Hidden/.nomedia_album/dsd.dsf",
                contentUri = null,
                mediaStoreId = null,
                displayName = "dsd.dsf",
                fileSize = 95_000_000L,
                dateModifiedSec = 1700000200L,
                dateAddedSec = 1700000200L,
                generationModified = 0L,
                durationMs = 0L,
                sourceTier = ScanSourceTier.DIRECT_FILE
            )
        )

        val merged = mergeTwoTierCandidates(tier1, tier2)
        assertEquals(2, merged.size)

        val track1 = merged["/storage/emulated/0/Music/track1.flac"]!!
        assertEquals(101L, track1.mediaStoreId)
        assertEquals(35_000_000L, track1.fileSize)
        assertEquals(1700000100L, track1.dateModifiedSec)
        assertEquals(ScanSourceTier.MEDIA_STORE, track1.sourceTier)

        val dsf = merged["/storage/emulated/0/Hidden/.nomedia_album/dsd.dsf"]!!
        assertEquals(ScanSourceTier.DIRECT_FILE, dsf.sourceTier)
    }

    @Test
    fun classifyScanDelta_identifiesUnchangedNewModifiedAndDeleted() {
        val existing = mapOf(
            "/music/unchanged.flac" to fingerprint(id = 1L, path = "/music/unchanged.flac", size = 1000L, mod = 500L),
            "/music/modified.flac" to fingerprint(id = 2L, path = "/music/modified.flac", size = 2000L, mod = 600L),
            "/music/deleted.mp3" to fingerprint(id = 3L, path = "/music/deleted.mp3", size = 3000L, mod = 700L)
        )
        val merged = mapOf(
            "/music/unchanged.flac" to candidate(path = "/music/unchanged.flac", id = 1L, size = 1000L, mod = 500L),
            "/music/modified.flac" to candidate(path = "/music/modified.flac", id = 2L, size = 2048L, mod = 610L),
            "/music/new.dsf" to candidate(path = "/music/new.dsf", id = null, size = 5000L, mod = 800L)
        )

        val delta = classifyScanDelta(
            mergedCandidates = merged,
            existingFingerprints = existing,
            minDurationMs = 10_000L,
            forceFullMetadataRefresh = false
        )

        assertEquals(listOf("/music/unchanged.flac"), delta.unchangedKeys)
        assertEquals(2, delta.toProcess.size)
        assertTrue(delta.toProcess.any { it.first.path == "/music/modified.flac" && it.second?.id == 2L })
        assertTrue(delta.toProcess.any { it.first.path == "/music/new.dsf" && it.second == null })
        assertEquals(1, delta.deleted.size)
        assertEquals(3L, delta.deleted.first().id)
    }

    @Test
    fun isSupportedAudioName_acceptsAllAudiophileFormatsAndRejectsNonAudio() {
        val expectedTrue = listOf(
            "song.mp3", "album.FLAC", "master.dsf", "sacd.DFF", "lossless.ape",
            "pack.wv", "hi_res.opus", "apple.alac", "studio.aiff", "track.m4a", "audio.wav"
        )
        for (name in expectedTrue) {
            assertTrue("Expected supported: $name", isSupportedAudioName(name))
        }
        assertTrue(!isSupportedAudioName("cover.jpg"))
        assertTrue(!isSupportedAudioName(".nomedia"))
        assertTrue(!isSupportedAudioName("notes.txt"))
    }

    // ═══ Byte-Level Metadata & Hi-Res Parsing ═══

    @Test
    fun parseFileDirect_decodesFlacHiResAndVietnameseVorbisComments() {
        val file = tempFolder.newFile("tiếng_việt.flac")
        file.writeBytes(buildSyntheticFlacBytes(
            sampleRate = 96_000,
            bitsPerSample = 24,
            totalSamples = 96_000L * 185L,
            comments = listOf(
                "TITLE=Chiều Hôm Ấy",
                "ARTIST=Nghệ Sĩ Việt",
                "ALBUM=Tuyển Tập Hi-Res",
                "ALBUMARTIST=Various Vietnamese Artists",
                "GENRE=Acoustic",
                "DATE=2024-05-12",
                "TRACKNUMBER=03/12",
                "DISCNUMBER=2/2"
            )
        ))

        val meta = AudioMetadataParser.parseFileDirect(file)!!
        assertEquals("FLAC", meta.codec)
        assertEquals(96_000, meta.sampleRate)
        assertEquals(24, meta.bitDepth)
        assertEquals(185_000L, meta.durationMs)
        assertEquals("Chiều Hôm Ấy", meta.title)
        assertEquals("Nghệ Sĩ Việt", meta.artist)
        assertEquals("Tuyển Tập Hi-Res", meta.album)
        assertEquals("Various Vietnamese Artists", meta.albumArtist)
        assertEquals("Acoustic", meta.genre)
        assertEquals(2024, meta.year)
        assertEquals(3, meta.trackNumber)
        assertEquals(2, meta.discNumber)
    }

    @Test
    fun parseFileDirect_decodesDsfHiResAndTrailingId3v2() {
        val file = tempFolder.newFile("sacd_track.dsf")
        file.writeBytes(buildSyntheticDsfBytes(
            sampleRate = 2_822_400,
            sampleCount = 2_822_400L * 60L,
            title = "Đêm Đông DSD",
            artist = "Audiophile Ensemble"
        ))

        val meta = AudioMetadataParser.parseFileDirect(file)!!
        assertEquals("DSD64", meta.codec)
        assertEquals(2_822_400, meta.sampleRate)
        assertEquals(1, meta.bitDepth)
        assertEquals(60_000L, meta.durationMs)
        assertEquals("Đêm Đông DSD", meta.title)
        assertEquals("Audiophile Ensemble", meta.artist)
    }

    @Test
    fun parseFileDirect_decodesWavPackWithApeV2Tags() {
        val file = tempFolder.newFile("master.wv")
        file.writeBytes(buildSyntheticWavPackBytes(
            sampleRateIdx = 13, // 96000 Hz
            bytesPerSampleMinus1 = 2, // 24-bit
            totalSamples = 96_000L * 45L,
            title = "WavPack Hi-Res",
            albumArtist = "Studio Master"
        ))

        val meta = AudioMetadataParser.parseFileDirect(file)!!
        assertEquals("WV", meta.codec)
        assertEquals(96_000, meta.sampleRate)
        assertEquals(24, meta.bitDepth)
        assertEquals(45_000L, meta.durationMs)
        assertEquals("WavPack Hi-Res", meta.title)
        assertEquals("Studio Master", meta.albumArtist)
    }

    @Test
    fun parseFileDirect_handlesZeroByteAndCorruptedFilesSafely() {
        val zeroByte = tempFolder.newFile("empty.flac")
        assertNull(AudioMetadataParser.parseFileDirect(zeroByte))

        val corrupted = tempFolder.newFile("corrupted.mp3")
        corrupted.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06))
        assertNull(AudioMetadataParser.parseFileDirect(corrupted))
    }

    @Test
    fun artworkKeyIsolation_separatesEmptyAndGenericAlbumTracksButDeduplicatesRealAlbums() {
        assertEquals(224, ArtResolutionBucket.THUMBNAIL.maxSizePx)
        assertEquals(512, ArtResolutionBucket.CARD.maxSizePx)
        assertEquals(1024, ArtResolutionBucket.HIGH_RES.maxSizePx)

        // Two singles in /Download with empty/placeholder album sharing MediaStore albumId=99
        assertTrue(isStandaloneTrackAlbum("", "/storage/emulated/0/Download/a.mp3"))
        assertTrue(isStandaloneTrackAlbum("Album không tên", "/storage/emulated/0/Download/a.mp3"))
        assertTrue(isStandaloneTrackAlbum("Download", "/storage/emulated/0/Download/a.mp3"))

        val singleAKey = computeSongArtworkKey(
            songId = 1001L,
            albumId = 99L,
            albumName = "Album không tên",
            albumArtist = "Artist A",
            songPath = "/storage/emulated/0/Download/a.mp3"
        )
        val singleBKey = computeSongArtworkKey(
            songId = 1002L,
            albumId = 99L,
            albumName = "Download",
            albumArtist = "Artist B",
            songPath = "/storage/emulated/0/Download/b.mp3"
        )
        assertNotEquals(singleAKey, singleBKey)
        assertEquals("trk_1001", singleAKey)
        assertEquals("trk_1002", singleBKey)

        // Two tracks in a real album still share a single album-level deduplication key
        assertFalse(isStandaloneTrackAlbum("Random Access Memories", "/storage/emulated/0/Music/Daft Punk/01.flac"))
        val albumTrack1 = computeSongArtworkKey(
            songId = 2001L,
            albumId = 555L,
            albumName = "Random Access Memories",
            albumArtist = "Daft Punk",
            songPath = "/storage/emulated/0/Music/Daft Punk/01.flac"
        )
        val albumTrack2 = computeSongArtworkKey(
            songId = 2002L,
            albumId = 555L,
            albumName = "Random Access Memories",
            albumArtist = "Daft Punk",
            songPath = "/storage/emulated/0/Music/Daft Punk/02.flac"
        )
        assertEquals("alb_555", albumTrack1)
        assertEquals(albumTrack1, albumTrack2)
    }

    @Test
    fun parseFileDirect_flacJoinsMultiArtistAndExcludesEmbeddedArtFromBitrate() {
        val file = tempFolder.newFile("multi_artist_with_art.flac")
        val flacHeader = buildSyntheticFlacBytesWithPicture(
            sampleRate = 44_100,
            bitsPerSample = 16,
            totalSamples = 44_100L * 10L, // 10 seconds
            comments = listOf(
                "TITLE=Duet Song",
                "ARTIST=Artist One",
                "ARTIST=Artist Two",
                "GENRE=Pop",
                "GENRE=Electronic"
            ),
            fakePictureBytesSize = 500_000, // 500 KB embedded cover
            fakeAudioPayloadSize = 125_000  // 125 KB audio -> ~100 kbps
        )
        file.writeBytes(flacHeader)

        val meta = AudioMetadataParser.parseFileDirect(file)!!
        assertEquals("Artist One, Artist Two", meta.artist)
        assertEquals("Pop, Electronic", meta.genre)
        assertEquals(10_000L, meta.durationMs)
        // Must reflect ~100 kbps audio payload, not ~500 kbps inflated by cover art
        assertTrue("Expected ~100 kbps bitrate, got ${meta.bitrate}", meta.bitrate in 95_000..110_000)
    }

    @Test
    fun parseFileDirect_decodesId3v24NonSynchsafeAndUtf16MultiValueAndValidatesMp3Sync() {
        val file = tempFolder.newFile("id3v24_utf16.mp3")
        val out = ByteArrayOutputStream()

        // Build an ID3v2.4 tag with:
        // 1) A 256-byte TPE1 frame encoded with non-synchsafe big-endian size (0x00 0x00 0x01 0x00)
        //    containing UTF-16LE with BOM and two null-separated artists
        // 2) A TIT2 frame encoded with UTF-16LE without BOM
        val frames = ByteArrayOutputStream()

        val artistsUtf16 = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x01, 0xFF.toByte(), 0xFE.toByte())) // encoding=1 (UTF-16 w/ BOM)
            write("Ca Sĩ A\u0000Ca Sĩ B".toByteArray(Charsets.UTF_16LE))
            // Pad to exact 256 bytes so non-synchsafe BE size = 0x00000100 (bit 7 is 0!)
            while (size() < 256) write(0)
        }.toByteArray()
        frames.write("TPE1".toByteArray(Charsets.ISO_8859_1))
        frames.write(beInt(artistsUtf16.size)) // non-synchsafe BE 256
        frames.write(byteArrayOf(0, 0))
        frames.write(artistsUtf16)

        val titlePayload = ByteArrayOutputStream().apply {
            write(0x01) // encoding=1, intentionally omitting BOM (UTF-16LE)
            write("Bài Hát Test".toByteArray(Charsets.UTF_16LE))
        }.toByteArray()
        frames.write("TIT2".toByteArray(Charsets.ISO_8859_1))
        frames.write(beInt(titlePayload.size))
        frames.write(byteArrayOf(0, 0))
        frames.write(titlePayload)

        val frameBytes = frames.toByteArray()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(4, 0, 0)) // ID3v2.4
        out.write(byteArrayOf(
            ((frameBytes.size ushr 21) and 0x7F).toByte(),
            ((frameBytes.size ushr 14) and 0x7F).toByte(),
            ((frameBytes.size ushr 7) and 0x7F).toByte(),
            (frameBytes.size and 0x7F).toByte()
        ))
        out.write(frameBytes)

        // Inject garbage false sync bytes before two consecutive valid MPEG1 Layer3 128kbps 44.1kHz frames
        // Frame size = (144 * 128000) / 44100 = 417 bytes
        out.write(byteArrayOf(0x00, 0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00, 0x11, 0x22))
        val validMp3Frame = ByteArray(417)
        validMp3Frame[0] = 0xFF.toByte()
        validMp3Frame[1] = 0xFB.toByte() // MPEG1, Layer III, no CRC
        validMp3Frame[2] = 0x90.toByte() // 128 kbps, 44100 Hz, padding=0
        validMp3Frame[3] = 0x00
        out.write(validMp3Frame)
        out.write(validMp3Frame)

        file.writeBytes(out.toByteArray())

        val meta = AudioMetadataParser.parseFileDirect(file)!!
        assertEquals("Ca Sĩ A, Ca Sĩ B", meta.artist)
        assertEquals("Bài Hát Test", meta.title)
        assertEquals("MP3", meta.codec)
        assertEquals(44_100, meta.sampleRate)
        assertEquals(128_000, meta.bitrate)
        assertTrue("MP3 without Xing/VBRI should mark durationEstimated=true", meta.durationEstimated)
    }

    // ═══ Synthetic Binary Builders ═══

    private fun fingerprint(id: Long, path: String, size: Long, mod: Long) = SongFingerprint(
        id = id,
        path = path,
        fileSize = size,
        dateModifiedSec = mod,
        generationModified = 0L,
        durationMs = 180_000L,
        albumId = 10L,
        album = "Album",
        artist = "Artist",
        albumArtist = "Artist",
        contentUriStr = "file://$path",
        sampleRate = 44100,
        bitDepth = 16,
        bitrate = 1000000,
        codec = "FLAC"
    )

    private fun candidate(path: String, id: Long?, size: Long, mod: Long) = ScannedFileCandidate(
        lookupKey = path,
        path = path,
        contentUri = null,
        mediaStoreId = id,
        displayName = File(path).name,
        fileSize = size,
        dateModifiedSec = mod,
        dateAddedSec = mod,
        generationModified = 0L,
        durationMs = 180_000L
    )

    private fun buildSyntheticFlacBytes(
        sampleRate: Int,
        bitsPerSample: Int,
        totalSamples: Long,
        comments: List<String>
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))

        // STREAMINFO block (34 bytes, not last)
        out.write(byteArrayOf(0x00, 0x00, 0x00, 34))
        val streamInfo = ByteArray(34)
        val channelsMinus1 = 1L // stereo
        val bpsMinus1 = (bitsPerSample - 1).toLong()
        val packed = (sampleRate.toLong() shl 44) or
            (channelsMinus1 shl 41) or
            (bpsMinus1 shl 36) or
            (totalSamples and 0x0000000FFFFFFFFFL)
        for (i in 0..7) {
            streamInfo[10 + i] = ((packed ushr (56 - i * 8)) and 0xFFL).toByte()
        }
        out.write(streamInfo)

        // VORBIS_COMMENT block (last block = 0x84)
        val vc = ByteArrayOutputStream()
        val vendor = "PlayDepthTest".toByteArray(Charsets.UTF_8)
        vc.write(leInt(vendor.size))
        vc.write(vendor)
        vc.write(leInt(comments.size))
        for (c in comments) {
            val bytes = c.toByteArray(Charsets.UTF_8)
            vc.write(leInt(bytes.size))
            vc.write(bytes)
        }
        val vcBytes = vc.toByteArray()
        out.write(byteArrayOf(
            0x84.toByte(),
            ((vcBytes.size ushr 16) and 0xFF).toByte(),
            ((vcBytes.size ushr 8) and 0xFF).toByte(),
            (vcBytes.size and 0xFF).toByte()
        ))
        out.write(vcBytes)
        return out.toByteArray()
    }

    private fun buildSyntheticFlacBytesWithPicture(
        sampleRate: Int,
        bitsPerSample: Int,
        totalSamples: Long,
        comments: List<String>,
        fakePictureBytesSize: Int,
        fakeAudioPayloadSize: Int
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))

        // STREAMINFO block (34 bytes, not last)
        out.write(byteArrayOf(0x00, 0x00, 0x00, 34))
        val streamInfo = ByteArray(34)
        val packed = (sampleRate.toLong() shl 44) or
            (1L shl 41) or
            ((bitsPerSample - 1).toLong() shl 36) or
            (totalSamples and 0x0000000FFFFFFFFFL)
        for (i in 0..7) {
            streamInfo[10 + i] = ((packed ushr (56 - i * 8)) and 0xFFL).toByte()
        }
        out.write(streamInfo)

        // VORBIS_COMMENT block (not last = 0x04)
        val vc = ByteArrayOutputStream()
        val vendor = "PlayDepthTest".toByteArray(Charsets.UTF_8)
        vc.write(leInt(vendor.size))
        vc.write(vendor)
        vc.write(leInt(comments.size))
        for (c in comments) {
            val bytes = c.toByteArray(Charsets.UTF_8)
            vc.write(leInt(bytes.size))
            vc.write(bytes)
        }
        val vcBytes = vc.toByteArray()
        out.write(byteArrayOf(
            0x04,
            ((vcBytes.size ushr 16) and 0xFF).toByte(),
            ((vcBytes.size ushr 8) and 0xFF).toByte(),
            (vcBytes.size and 0xFF).toByte()
        ))
        out.write(vcBytes)

        // PICTURE block (last = 0x86) simulating large embedded album art
        out.write(byteArrayOf(
            0x86.toByte(),
            ((fakePictureBytesSize ushr 16) and 0xFF).toByte(),
            ((fakePictureBytesSize ushr 8) and 0xFF).toByte(),
            (fakePictureBytesSize and 0xFF).toByte()
        ))
        out.write(ByteArray(fakePictureBytesSize))

        // Fake audio frames payload
        out.write(ByteArray(fakeAudioPayloadSize))
        return out.toByteArray()
    }

    private fun buildSyntheticDsfBytes(
        sampleRate: Int,
        sampleCount: Long,
        title: String,
        artist: String
    ): ByteArray {
        val id3 = buildSyntheticId3v23(title = title, artist = artist)
        val metaOffset = 80L + 12L // DSD(28) + fmt(52) + minimal data(12)
        val totalSize = metaOffset + id3.size

        val out = ByteArrayOutputStream()
        // DSD chunk (28 bytes)
        out.write("DSD ".toByteArray(Charsets.US_ASCII))
        out.write(leLong(28L))
        out.write(leLong(totalSize))
        out.write(leLong(metaOffset))

        // fmt chunk (52 bytes)
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(leLong(52L))
        out.write(leInt(1)) // format version
        out.write(leInt(0)) // format ID (DSD raw)
        out.write(leInt(2)) // channel type (stereo)
        out.write(leInt(2)) // channel count
        out.write(leInt(sampleRate))
        out.write(leInt(1)) // bits per sample
        out.write(leLong(sampleCount))
        out.write(leInt(4096)) // block size per channel
        out.write(leInt(0)) // reserved

        // Minimal data chunk header (12 bytes)
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(leLong(12L))

        // Trailing ID3v2 tag
        out.write(id3)
        return out.toByteArray()
    }

    private fun buildSyntheticId3v23(title: String, artist: String): ByteArray {
        val frames = ByteArrayOutputStream()
        fun addTextFrame(id: String, value: String) {
            val textBytes = value.toByteArray(Charsets.UTF_8)
            val payload = ByteArray(1 + textBytes.size)
            payload[0] = 3 // UTF-8
            System.arraycopy(textBytes, 0, payload, 1, textBytes.size)
            frames.write(id.toByteArray(Charsets.ISO_8859_1))
            frames.write(beInt(payload.size))
            frames.write(byteArrayOf(0, 0))
            frames.write(payload)
        }
        addTextFrame("TIT2", title)
        addTextFrame("TPE1", artist)
        val frameBytes = frames.toByteArray()
        val size = frameBytes.size
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(3, 0, 0))
        out.write(byteArrayOf(
            ((size ushr 21) and 0x7F).toByte(),
            ((size ushr 14) and 0x7F).toByte(),
            ((size ushr 7) and 0x7F).toByte(),
            (size and 0x7F).toByte()
        ))
        out.write(frameBytes)
        return out.toByteArray()
    }

    private fun buildSyntheticWavPackBytes(
        sampleRateIdx: Int,
        bytesPerSampleMinus1: Int,
        totalSamples: Long,
        title: String,
        albumArtist: String
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // 32-byte WavPack block header
        out.write("wvpk".toByteArray(Charsets.US_ASCII))
        out.write(leInt(24)) // block size
        out.write(byteArrayOf(0x07, 0x04)) // version 0x0407
        out.write(byteArrayOf(0, 0)) // block_index_u8, total_samples_u8
        out.write(leInt(totalSamples.toInt()))
        out.write(leInt(0)) // block_index
        out.write(leInt(1024)) // block_samples
        val flags = (bytesPerSampleMinus1 and 0x03) or ((sampleRateIdx and 0x0F) shl 23)
        out.write(leInt(flags))
        out.write(leInt(0)) // crc

        // APEv2 items + 32-byte footer
        val items = ByteArrayOutputStream()
        fun addApeItem(key: String, value: String) {
            val valBytes = value.toByteArray(Charsets.UTF_8)
            items.write(leInt(valBytes.size))
            items.write(leInt(0)) // UTF-8 text flags
            items.write(key.toByteArray(Charsets.US_ASCII))
            items.write(0)
            items.write(valBytes)
        }
        addApeItem("Title", title)
        addApeItem("Album Artist", albumArtist)
        val itemBytes = items.toByteArray()
        out.write(itemBytes)

        // APEv2 footer (32 bytes)
        out.write("APETAGEX".toByteArray(Charsets.US_ASCII))
        out.write(leInt(2000)) // version
        out.write(leInt(itemBytes.size + 32)) // tag size including footer
        out.write(leInt(2)) // item count
        out.write(leInt(0)) // flags
        out.write(ByteArray(8)) // reserved
        return out.toByteArray()
    }

    private fun leInt(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun beInt(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array()

    private fun leLong(v: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
}
