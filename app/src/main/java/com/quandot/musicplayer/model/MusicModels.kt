/**
 * MusicModels — Immutable domain models, sorting options, and fast A–Z index normalization.
 *
 * @remarks
 * All library entities are `@Immutable` with precomputed `indexLetter` and `formattedDuration`
 * fields so Compose `LazyColumn`/`LazyGrid` diffs skip recomposition and avoid per-frame string
 * allocations during fast scrolling. `fastNormalizeCharToLetter` resolves ASCII and accented
 * Vietnamese/Latin characters via a direct branch table, deferring `Normalizer` NFD decomposition
 * only to uncommon Latin-Extended code points.
 *
 * @see MusicRepository
 * @see MusicDbHelper
 */
package com.quandot.musicplayer.model

// External SDK / Jetpack / third-party libraries
import android.net.Uri
import androidx.compose.runtime.Immutable
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

// ═══ Constants ═══
private val INDEX_LETTER_CACHE = Array(26) { ('A' + it).toString() }
private val LOCALIZED_COLLATOR = ThreadLocal<Pair<String, Collator>>()
private const val MS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val DEFAULT_SONG_GRADIENT_START = 0xFF8A2387
private const val DEFAULT_SONG_GRADIENT_END = 0xFFE94057
private const val DEFAULT_ALBUM_GRADIENT_START = 0xFF667EEA
private const val DEFAULT_ALBUM_GRADIENT_END = 0xFF764BA2

// ═══ Index & Duration Helpers ═══

/**
 * Normalizes an ASCII or accented Latin/Vietnamese character to uppercase `A`–`Z` (or `'#'`)
 * without intermediate `String` or `Normalizer` allocations.
 */
fun fastNormalizeCharToLetter(c: Char): Char {
    if (c in 'A'..'Z') return c
    if (c in 'a'..'z') return ('A' + (c - 'a'))
    return when (c) {
        'à', 'á', 'ả', 'ã', 'ạ', 'ă', 'ằ', 'ắ', 'ẳ', 'ẵ', 'ặ', 'â', 'ầ', 'ấ', 'ẩ', 'ẫ', 'ậ',
        'À', 'Á', 'Ả', 'Ã', 'Ạ', 'Ă', 'Ằ', 'Ắ', 'Ẳ', 'Ẵ', 'Ặ', 'Â', 'Ầ', 'Ấ', 'Ẩ', 'Ẫ', 'Ậ',
        'ä', 'Ä', 'å', 'Å' -> 'A'
        'ç', 'Ç' -> 'C'
        'đ', 'Đ' -> 'D'
        'è', 'é', 'ẻ', 'ẽ', 'ẹ', 'ê', 'ề', 'ế', 'ể', 'ễ', 'ệ',
        'È', 'É', 'Ẻ', 'Ẽ', 'Ẹ', 'Ê', 'Ề', 'Ế', 'Ể', 'Ễ', 'Ệ' -> 'E'
        'ì', 'í', 'ỉ', 'ĩ', 'ị', 'Ì', 'Í', 'Ỉ', 'Ĩ', 'Ị', 'î', 'Î', 'ï', 'Ï' -> 'I'
        'ñ', 'Ñ' -> 'N'
        'ò', 'ó', 'ỏ', 'õ', 'ọ', 'ô', 'ồ', 'ố', 'ổ', 'ỗ', 'ộ', 'ơ', 'ờ', 'ớ', 'ở', 'ỡ', 'ợ',
        'Ò', 'Ó', 'Ỏ', 'Õ', 'Ọ', 'Ô', 'Ồ', 'Ố', 'Ổ', 'Ỗ', 'Ộ', 'Ơ', 'Ờ', 'Ớ', 'Ở', 'Ỡ', 'Ợ',
        'ö', 'Ö', 'ø', 'Ø' -> 'O'
        'ù', 'ú', 'ủ', 'ũ', 'ụ', 'ư', 'ừ', 'ứ', 'ử', 'ữ', 'ự',
        'Ù', 'Ú', 'Ủ', 'Ũ', 'Ụ', 'Ư', 'Ừ', 'Ứ', 'Ử', 'Ữ', 'Ự', 'ü', 'Ü' -> 'U'
        'ỳ', 'ý', 'ỷ', 'ỹ', 'ỵ', 'Ỳ', 'Ý', 'Ỷ', 'Ỹ', 'Ỵ', 'ÿ', 'Ÿ' -> 'Y'
        else -> '#'
    }
}

/**
 * Resolves the section index bucket for fast alphabetical scrolling (`A`–`Z`, non-Latin letters
 * such as CJK/Cyrillic, or `"#"` for digits and symbols).
 */
fun computeIndexLetter(text: String): String {
    if (text.length == 1) {
        val single = text[0]
        if (single == '#') return "#"
        if (single in 'A'..'Z') return INDEX_LETTER_CACHE[single - 'A']
        if (Character.isLetter(single) && single > '\u024F') return single.uppercaseChar().toString()
    }
    for (i in text.indices) {
        val c = text[i]
        if (c.isWhitespace()) continue
        val normalized = fastNormalizeCharToLetter(c)
        if (normalized in 'A'..'Z') return INDEX_LETTER_CACHE[normalized - 'A']
        if (c in '\u00C0'..'\u024F') {
            val decomposed = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
            val base = decomposed.firstOrNull()?.let(::fastNormalizeCharToLetter) ?: '#'
            if (base in 'A'..'Z') return INDEX_LETTER_CACHE[base - 'A']
        }
        return if (Character.isLetter(c)) c.uppercaseChar().toString() else "#"
    }
    return "#"
}

private fun foldCharForSort(c: Char): Int {
    if (c in 'A'..'Z') return 'a'.code + (c - 'A')
    if (c in 'a'..'z') return c.code
    val fast = fastNormalizeCharToLetter(c)
    if (fast in 'A'..'Z') return 'a'.code + (fast - 'A')
    if (c in '\u00C0'..'\u024F') {
        val decomposed = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
        val base = decomposed.firstOrNull()?.let(::fastNormalizeCharToLetter)
        if (base != null && base in 'A'..'Z') return 'a'.code + (base - 'A')
    }
    return c.lowercaseChar().code
}

private fun localizedCollator(): Collator {
    val locale = Locale.getDefault()
    val tag = locale.toLanguageTag()
    val cached = LOCALIZED_COLLATOR.get()
    if (cached != null && cached.first == tag) return cached.second
    val collator = Collator.getInstance(locale).apply {
        strength = Collator.SECONDARY
    }
    LOCALIZED_COLLATOR.set(tag to collator)
    return collator
}

/**
 * Case-insensitive and Vietnamese/Latin diacritic-aware comparator that keeps uppercase/lowercase
 * titles (e.g. `Apple` and `apple`) and accented letters (e.g. `Đ` inside `D`) aligned with
 * [computeIndexLetter] fast-scroll buckets without per-comparison string allocations.
 */
val NATURAL_TEXT_COMPARATOR: Comparator<String> = Comparator { rawA, rawB ->
    if (rawA === rawB) return@Comparator 0
    var i = 0
    var j = 0
    while (i < rawA.length && rawA[i].isWhitespace()) i++
    while (j < rawB.length && rawB[j].isWhitespace()) j++

    while (i < rawA.length && j < rawB.length) {
        val fa = foldCharForSort(rawA[i])
        val fb = foldCharForSort(rawB[j])
        if (fa != fb) return@Comparator fa - fb
        i++
        j++
    }
    val remA = rawA.length - i
    val remB = rawB.length - j
    if (remA != remB) return@Comparator remA - remB

    val secondary = localizedCollator().compare(rawA, rawB)
    if (secondary != 0) secondary else rawA.compareTo(rawB)
}

private fun formatDurationMs(durationMs: Long): String {
    val totalSeconds = (durationMs / MS_PER_SECOND).coerceAtLeast(0L)
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    val sb = StringBuilder(6)
    if (minutes < 10L) sb.append('0')
    sb.append(minutes)
    sb.append(':')
    if (seconds < 10L) sb.append('0')
    sb.append(seconds)
    return sb.toString()
}

// ═══ Domain Models ═══
@Immutable
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long, // in milliseconds
    val contentUri: Uri = Uri.EMPTY,
    val albumArtUri: Uri? = null,
    val albumGradientColorStart: Long = DEFAULT_SONG_GRADIENT_START,
    val albumGradientColorEnd: Long = DEFAULT_SONG_GRADIENT_END,
    val path: String = "",
    val dateAdded: Long = 0,
    val albumId: Long = 0,
    val trackNumber: Int = 0,
    val year: Int = 0,
    val genre: String = "",
    val instanceId: Long = 0L,
    val formattedDuration: String = formatDurationMs(duration),
    val indexLetter: String = computeIndexLetter(title),
    val albumArtist: String = "",
    val discNumber: Int = 0,
    val fileSize: Long = 0L,
    val dateModified: Long = 0L, // epoch seconds (matches MediaStore.DATE_MODIFIED)
    val generationModified: Long = 0L,
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val bitrate: Int = 0,
    val codec: String = ""
)

// ═══ Library Scan State ═══

/**
 * Observable lifecycle state of PlayDepth's hybrid incremental library scanner.
 */
@Immutable
sealed interface LibraryScanState {
    data object Idle : LibraryScanState

    @Immutable
    data class Scanning(
        val scannedCount: Int,
        val totalCount: Int,
        val currentFolder: String = ""
    ) : LibraryScanState

    @Immutable
    data class Completed(
        val added: Int,
        val updated: Int,
        val removed: Int,
        val durationMs: Long
    ) : LibraryScanState
}

@Immutable
data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val songCount: Int,
    val albumArtUri: Uri? = null,
    val colorStart: Long = DEFAULT_ALBUM_GRADIENT_START,
    val colorEnd: Long = DEFAULT_ALBUM_GRADIENT_END,
    val year: Int = 0,
    val indexLetter: String = computeIndexLetter(title),
    val sampleSongUri: Uri? = null,
    val sampleSongPath: String = ""
)

@Immutable
data class Artist(
    val id: Long,
    val name: String,
    val songCount: Int,
    val albumCount: Int,
    val indexLetter: String = computeIndexLetter(name)
)

@Immutable
data class Folder(
    val name: String,
    val path: String,
    val songCount: Int,
    val indexLetter: String = computeIndexLetter(name)
)

@Immutable
data class Genre(
    val name: String,
    val songCount: Int,
    val indexLetter: String = computeIndexLetter(name)
)

@Immutable
data class YearGroup(
    val year: Int,
    val songCount: Int
)

@Immutable
data class Playlist(
    val id: Long,
    val name: String,
    val songCount: Int,
    val isSystem: Boolean = false,
    val createdAt: Long = 0L,
    val indexLetter: String = computeIndexLetter(name)
)

// ═══ Sort Options ═══
enum class AlbumSortOption(val displayName: String) {
    TRACK_NUMBER("Thứ tự bài hát"),
    TITLE_AZ("Tên bài hát (A-Z)"),
    DURATION("Thời lượng"),
    ARTIST("Tên nghệ sĩ")
}

enum class SongSortOption(val displayName: String) {
    TITLE_AZ("Tên bài hát (A-Z)"),
    DATE_ADDED("Mới thêm gần đây"),
    DURATION("Thời lượng"),
    ARTIST("Tên nghệ sĩ"),
    TRACK_NUMBER("Số thứ tự track")
}

enum class SortOption(val displayName: String) {
    TITLE_AZ("Tên bài hát (A-Z)"),
    DATE_ADDED("Mới thêm gần đây"),
    DURATION("Thời lượng"),
    ARTIST("Tên nghệ sĩ")
}
