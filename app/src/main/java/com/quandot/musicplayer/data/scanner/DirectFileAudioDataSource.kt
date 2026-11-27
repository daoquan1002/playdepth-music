/**
 * DirectFileAudioDataSource — Tier 2 direct filesystem (`Files.walkFileTree`) and SAF
 * (`DocumentsContract`) audio discovery.
 *
 * @remarks
 * Complements Tier 1 MediaStore discovery by capturing audiophile formats ignored by Android's
 * MediaScanner (`.dsf`, `.dff`, `.ape`, `.wv`), user-added custom directories, and removable SD
 * card / USB OTG volumes while strictly skipping directories marked with `.nomedia`. Uses
 * `Files.walkFileTree` so `BasicFileAttributes` supplies file size and modification time directly
 * from directory-entry `stat` without extra per-file I/O syscalls, and isolates
 * `IOException`/`SecurityException` per directory so sudden SD/USB removal never aborts the scan.
 *
 * @see MediaStoreAudioDataSource
 * @see HybridLibraryScanner
 */
package com.quandot.musicplayer.data.scanner

// External SDK / Jetpack / third-party libraries
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// ═══ Constants ═══
private const val MAX_VISITED_DIRS_PER_ROOT = 12_000
private val SYSTEM_SKIP_DIR_NAMES = setOf(
    "android",
    "lost.dir",
    "system volume information",
    ".thumbnails",
    ".trash",
    ".trashed",
    "\$recycle.bin"
)

// ═══ Tier 2 Data Source ═══
class DirectFileAudioDataSource(private val context: Context) {

    /**
     * Traverses eligible direct filesystem roots (user custom folders, removable SD/USB volumes,
     * and primary storage when direct read access is granted) plus persisted SAF `DocumentsContract`
     * trees.
     */
    suspend fun traverse(
        normalizedExcludedFolders: List<String>,
        customScanFolders: Set<String>,
        safTreeUris: Set<String>
    ): List<ScannedFileCandidate> {
        val results = ArrayList<ScannedFileCandidate>(256)
        val visitedRootPaths = HashSet<String>()
        val nomediaCache = HashMap<String, Boolean>(256)

        // 1. User-explicit custom scan folders (highest priority — still respects .nomedia)
        for (rawFolder in customScanFolders) {
            currentCoroutineContext().ensureActive()
            val norm = normalizePathKey(rawFolder)
            if (norm.isBlank() || isPathExcluded(norm, normalizedExcludedFolders)) continue
            val dir = File(norm)
            if (dir.exists() && dir.isDirectory && dir.canRead()) {
                val canonicalRoot = resolveCanonicalPathKey(dir)
                if (isDirectoryHiddenByNomedia(canonicalRoot, nomediaCache)) continue
                if (visitedRootPaths.add(canonicalRoot)) {
                    walkDirectRoot(
                        rootDir = dir,
                        allowHiddenRoot = true,
                        normalizedExcludedFolders = normalizedExcludedFolders,
                        nomediaCache = nomediaCache,
                        out = results
                    )
                }
            }
        }

        // 2. Mounted removable SD / USB OTG volumes + primary external storage (when accessible)
        val storageRoots = discoverReadableStorageRoots()
        for (rootDir in storageRoots) {
            currentCoroutineContext().ensureActive()
            val canonicalRoot = resolveCanonicalPathKey(rootDir)
            if (isPathExcluded(canonicalRoot, normalizedExcludedFolders)) continue
            if (isDirectoryHiddenByNomedia(canonicalRoot, nomediaCache)) continue
            if (!visitedRootPaths.add(canonicalRoot)) continue
            walkDirectRoot(
                rootDir = rootDir,
                allowHiddenRoot = false,
                normalizedExcludedFolders = normalizedExcludedFolders,
                nomediaCache = nomediaCache,
                out = results
            )
        }

        // 3. Persisted Storage Access Framework (SAF) document trees (works without MANAGE_EXTERNAL_STORAGE)
        for (rawTreeUri in safTreeUris) {
            currentCoroutineContext().ensureActive()
            val treeUri = runCatching { Uri.parse(rawTreeUri) }.getOrNull() ?: continue
            traverseSafTree(
                treeUri = treeUri,
                normalizedExcludedFolders = normalizedExcludedFolders,
                nomediaCache = nomediaCache,
                out = results
            )
        }

        return results
    }

    // ═══ Direct File Tree Traversal ═══
    private fun discoverReadableStorageRoots(): List<File> {
        val roots = LinkedHashSet<File>()
        val hasAllFilesAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

        // Include primary external storage when direct traversal is allowed so `.nomedia` folders
        // and audiophile containers (.dsf/.dff/.ape/.wv) on internal storage are never missed.
        if (hasAllFilesAccess) {
            runCatching {
                val primary = Environment.getExternalStorageDirectory()
                if (primary.exists() && primary.isDirectory && primary.canRead()) {
                    roots.add(primary)
                }
            }
        }

        // Always inspect mounted removable volumes (SD Card / USB OTG)
        runCatching {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return@runCatching
            for (vol in sm.storageVolumes) {
                if (vol.isPrimary) continue
                if (vol.state != Environment.MEDIA_MOUNTED && vol.state != Environment.MEDIA_MOUNTED_READ_ONLY) {
                    continue
                }
                val rawDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    vol.directory
                } else {
                    runCatching {
                        val getPath = vol.javaClass.getMethod("getPath")
                        (getPath.invoke(vol) as? String)?.let { File(it) }
                    }.getOrNull()
                }
                val uuid = vol.uuid?.trim().orEmpty()
                val resolvedDir = if (rawDir != null &&
                    rawDir.absolutePath.startsWith("/mnt/media_rw/") &&
                    uuid.isNotBlank()
                ) {
                    val publicDir = File("/storage/$uuid")
                    if (publicDir.exists() && publicDir.canRead()) publicDir else rawDir
                } else {
                    rawDir
                }
                if (resolvedDir != null && resolvedDir.exists() && resolvedDir.isDirectory && resolvedDir.canRead()) {
                    roots.add(resolvedDir)
                }
            }
        }

        return roots.toList()
    }

    private suspend fun walkDirectRoot(
        rootDir: File,
        allowHiddenRoot: Boolean,
        normalizedExcludedFolders: List<String>,
        nomediaCache: MutableMap<String, Boolean>,
        out: MutableList<ScannedFileCandidate>
    ) {
        val coroutineCtx = currentCoroutineContext()
        val rootPath = rootDir.toPath()
        var visitedDirs = 0

        try {
            Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    coroutineCtx.ensureActive()
                    if (visitedDirs >= MAX_VISITED_DIRS_PER_ROOT) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    val isRoot = dir == rootPath
                    val dirName = dir.fileName?.toString().orEmpty()
                    if (!isRoot) {
                        val lowerName = dirName.lowercase(Locale.ROOT)
                        if (lowerName in SYSTEM_SKIP_DIR_NAMES) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        if (dirName.startsWith(".") && !allowHiddenRoot) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                    }

                    val normDirPath = normalizePathKey(dir.toAbsolutePath().toString())
                    if (isPathExcluded(normDirPath, normalizedExcludedFolders)) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    val hasNomedia = runCatching { Files.exists(dir.resolve(".nomedia")) }.getOrDefault(false)
                    if (hasNomedia) {
                        nomediaCache[normDirPath] = true
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    visitedDirs++
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    coroutineCtx.ensureActive()
                    if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                    val fileSize = attrs.size()
                    if (fileSize <= 0L) return FileVisitResult.CONTINUE

                    val fileName = file.fileName?.toString().orEmpty()
                    if (fileName.startsWith(".") || !isSupportedAudioName(fileName)) {
                        return FileVisitResult.CONTINUE
                    }

                    val rawAbsPath = normalizePathKey(file.toAbsolutePath().toString())
                    if (isPathExcluded(rawAbsPath, normalizedExcludedFolders)) {
                        return FileVisitResult.CONTINUE
                    }

                    val lastModifiedSec = attrs.lastModifiedTime().toMillis() / 1_000L
                    val javaFile = file.toFile()
                    out.add(
                        ScannedFileCandidate(
                            lookupKey = rawAbsPath,
                            path = rawAbsPath,
                            contentUri = Uri.fromFile(javaFile),
                            mediaStoreId = null,
                            displayName = fileName,
                            fileSize = fileSize,
                            dateModifiedSec = lastModifiedSec,
                            dateAddedSec = lastModifiedSec,
                            generationModified = 0L,
                            durationMs = 0L,
                            sourceTier = ScanSourceTier.DIRECT_FILE
                        )
                    )
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    // Tolerate unreadable entries or suddenly ejected SD/USB mounts
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Protect caller if volume unmounts mid-traversal or permission is revoked
        }
    }

    // ═══ SAF DocumentsContract Traversal ═══
    private suspend fun traverseSafTree(
        treeUri: Uri,
        normalizedExcludedFolders: List<String>,
        nomediaCache: MutableMap<String, Boolean>,
        out: MutableList<ScannedFileCandidate>
    ) {
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return
        val resolvedRootPath = resolveDocumentIdToFilePath(rootDocId)
        if (!resolvedRootPath.isNullOrBlank() && isDirectoryHiddenByNomedia(resolvedRootPath, nomediaCache)) {
            return
        }
        val stack = ArrayDeque<String>()
        stack.addLast(rootDocId)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        var visitedDirs = 0
        while (stack.isNotEmpty() && visitedDirs < MAX_VISITED_DIRS_PER_ROOT) {
            currentCoroutineContext().ensureActive()
            val currentDocId = stack.removeFirst()
            visitedDirs++

            val childrenUri = runCatching {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentDocId)
            }.getOrNull() ?: continue

            try {
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val modCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                    val pendingDirs = ArrayList<String>()
                    val pendingFiles = ArrayList<ScannedFileCandidate>()
                    var dirHasNomedia = false

                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val docId = cursor.getString(idCol) ?: continue
                        val name = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                        val mime = if (mimeCol >= 0) cursor.getString(mimeCol).orEmpty() else ""

                        if (name.equals(".nomedia", ignoreCase = true)) {
                            dirHasNomedia = true
                            break
                        }

                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            val lower = name.lowercase(Locale.ROOT)
                            if (lower !in SYSTEM_SKIP_DIR_NAMES) {
                                val resolvedDirPath = resolveDocumentIdToFilePath(docId)
                                if (resolvedDirPath == null || !isPathExcluded(resolvedDirPath, normalizedExcludedFolders)) {
                                    pendingDirs.add(docId)
                                }
                            }
                            continue
                        }

                        if (!isSupportedAudioName(name)) continue
                        val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                        if (size <= 0L) continue

                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        val resolvedFilePath = resolveDocumentIdToFilePath(docId).orEmpty()
                        if (resolvedFilePath.isNotBlank() && isPathExcluded(resolvedFilePath, normalizedExcludedFolders)) {
                            continue
                        }

                        val modSec = if (modCol >= 0) cursor.getLong(modCol) / 1_000L else 0L
                        val lookupKey = resolvedFilePath.ifBlank { "saf://$docUri" }
                        pendingFiles.add(
                            ScannedFileCandidate(
                                lookupKey = lookupKey,
                                path = resolvedFilePath,
                                contentUri = docUri,
                                mediaStoreId = null,
                                displayName = name,
                                fileSize = size,
                                dateModifiedSec = modSec,
                                dateAddedSec = modSec,
                                generationModified = 0L,
                                durationMs = 0L,
                                sourceTier = ScanSourceTier.SAF_DOCUMENT
                            )
                        )
                    }

                    if (!dirHasNomedia) {
                        for (childDirId in pendingDirs) {
                            stack.addLast(childDirId)
                        }
                        out.addAll(pendingFiles)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Skip inaccessible SAF directory without failing the rest of the scan
            }
        }
    }

    private fun resolveDocumentIdToFilePath(docId: String): String? {
        val parts = docId.split(':', limit = 2)
        if (parts.size != 2) return null
        val volume = parts[0]
        val relative = parts[1].trim('/')
        val base = when {
            volume.equals("primary", ignoreCase = true) -> "/storage/emulated/0"
            volume.equals("home", ignoreCase = true) -> "/storage/emulated/0/Documents"
            volume.isNotBlank() -> "/storage/$volume"
            else -> return null
        }
        return normalizePathKey(if (relative.isEmpty()) base else "$base/$relative")
    }
}
