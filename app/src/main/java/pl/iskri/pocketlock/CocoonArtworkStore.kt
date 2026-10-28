package pl.iskri.pocketlock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.provider.DocumentsContract
import java.text.Normalizer

/**
 * Optional, strictly opt-in Cocoon artwork library. Android's Storage Access
 * Framework is used: no unrestricted file access or private-app paths.
 *
 * Automatic linking is conservative. Only an EXACT known title in a Cocoon
 * accessibility selection event followed by an emulator app switch can bind
 * an image. If this information is unavailable, retain the live screenshot.
 * Nothing scans or decodes while the display sleeps.
 */
object CocoonArtworkStore {
    private data class Artwork(val imageUri: Uri, val kind: String)
    private val lock = Any()
    private var index: Map<String, Map<String, Artwork>> = emptyMap()
    // Retain all locally discovered image locations for a sleep-interrupted
    // fingerprint build. No pixels or source files are persisted.
    private var visualReferences: List<CocoonVisualMatcher.ArtworkReference> = emptyList()
    private var selectedTitle: String? = null
    private var selectedAt: Long = 0L
    private var assignedPackage: String? = null
    private var assignedTitle: String? = null
    private var imagePackage: String? = null
    private var imageTitle: String? = null
    private var imageMode: String? = null
    private var cachedImage: Bitmap? = null
    private var indexing = false
    private var attemptedIndex = false
    private var lastCocoonLabels = "No game title received from Cocoon"
    private var lastEmulatorLabels = "No game title received from emulator"
    private var lastForeground = "None"
    private var indexedExamples = "No index yet"
    private var lastDecision = "Waiting for game identity from Cocoon or emulator"
    @Volatile var status: String = "No Cocoon artwork folder selected"
        private set

    private val assetExtensions = setOf("png", "jpg", "jpeg", "webp")
    private const val MAX_IMAGES = 3500
    private const val MAX_DEPTH = 7
    private const val SELECTION_LIFETIME_MS = 45_000L

    fun normalizeTitle(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim().replace(Regex("\\s+"), " ")

    /**
     * Cocoon art exports may append "cocoon edit 48 <image-hash>" to every
     * *filename*. Those are image variants, not separate games. Preserve the
     * game title/region, while stripping only generated suffixes.
     */
    internal fun gameKey(rawTitle: String): String {
        var key = normalizeTitle(rawTitle)
        key = key.replace(
            Regex(" (?:cocoon )?edit [0-9]{1,4} [0-9a-f]{12,}$"), "")
        key = key.replace(
            Regex(" cocoon [0-9]{1,4} [0-9a-f]{12,}$"), "")
        key = key.replace(
            Regex(" [0-9a-f]{24,}$"), "")
        return key.trim()
    }

    private val genericFolderNames = setOf(
        "cocoon", "edit", "images", "image", "artwork", "art", "media",
        "hero", "heroes", "icon", "icons", "logo", "logos", "cover",
        "covers", "grid", "grids", "boxart", "backgrounds", "background"
    )

    private fun canonicalGameKey(basename: String, directoryPath: List<String>): String {
        val fromFile = gameKey(basename)
        if (fromFile.length >= 3 && !fromFile.matches(Regex("[0-9a-f]{12,}")) &&
            fromFile !in genericFolderNames && !fromFile.matches(Regex("[0-9 ]+"))) {
            return fromFile
        }
        return directoryPath.asReversed().map(::gameKey).firstOrNull { candidate ->
            candidate.length >= 3 && candidate !in genericFolderNames &&
                !candidate.matches(Regex("[0-9a-f]{12,}")) &&
                !candidate.matches(Regex("[0-9 ]+"))
        } ?: fromFile
    }

    /** Called only for Cocoon-provided Accessibility click/focus labels. */
    fun observeCocoonSelection(context: Context, texts: List<String>) {
        if (Prefs.cocoonImageMode(context) == Prefs.COCOON_MODE_LIVE) return
        val candidates = texts.map(::normalizeTitle).filter { it.length >= 3 }
        synchronized(lock) {
            if (candidates.isNotEmpty()) {
                lastCocoonLabels = candidates.joinToString(" | ").take(240)
            }
            val hit = candidates.firstOrNull { index.containsKey(it) }
            if (hit == null) {
                lastDecision = "Cocoon selection did not exactly match an indexed title"
                return
            }
            selectedTitle = hit
            selectedAt = SystemClock.elapsedRealtime()
            status = "Selected game recognized in Cocoon"
            lastDecision = "Cocoon selected title: $hit"
        }
    }

    /** Called on actual foreground app switches; never assigns arbitrary art. */
    fun observeForeground(context: Context, packageName: String) {
        if (Prefs.cocoonImageMode(context) == Prefs.COCOON_MODE_LIVE) return
        if (NovaAppKinds.isCocoon(context, packageName)) {
            synchronized(lock) {
                lastForeground = packageName
                assignedPackage = null
                assignedTitle = null
                clearCached()
            }
            return
        }
        if (NovaAppKinds.isSettings(context, packageName)) return
        var toLoad: Pair<String, String>? = null
        synchronized(lock) {
            lastForeground = packageName
            if (assignedPackage == packageName) return
            val title = selectedTitle
            val recent = SystemClock.elapsedRealtime() - selectedAt < SELECTION_LIFETIME_MS
            if (title != null && recent && index.containsKey(title)) {
                assignedPackage = packageName
                assignedTitle = title
                selectedTitle = null
                selectedAt = 0L
                clearCached()
                toLoad = title to packageName
            } else {
                assignedPackage = null
                assignedTitle = null
                clearCached()
            }
        }
        toLoad?.let { (title, pkg) -> loadFor(context.applicationContext, title, pkg) }
    }

    /** Secondary evidence: an emulator's own window title may name the ROM. */
    fun observeEmulatorWindowTitle(
        context: Context, packageName: String, texts: List<String>
    ) {
        if (Prefs.cocoonImageMode(context) == Prefs.COCOON_MODE_LIVE ||
            NovaAppKinds.isCocoon(context, packageName) ||
            NovaAppKinds.isSettings(context, packageName)) return
        val matched = synchronized(lock) {
            val candidates = texts.map(::normalizeTitle).filter { it.length >= 3 }
            if (candidates.isNotEmpty()) {
                lastEmulatorLabels = candidates.joinToString(" | ").take(240)
            }
            val title = candidates.firstOrNull { index.containsKey(it) }
            if (title == null && candidates.isNotEmpty()) {
                lastDecision = "Emulator window label did not match indexed titles"
            }
            title
        } ?: return
        var shouldLoad = false
        synchronized(lock) {
            if (assignedPackage != packageName || assignedTitle != matched) {
                assignedPackage = packageName
                assignedTitle = matched
                selectedTitle = null
                selectedAt = 0L
                clearCached()
                shouldLoad = true
            }
        }
        if (shouldLoad) loadFor(context.applicationContext, matched, packageName)
    }

    /**
     * Identified by comparing a pre-launch Cocoon frame against downloaded
     * artwork. Requires a unique, high-confidence visual fingerprint.
     */
    fun observeVisualMatch(context: Context, key: String) {
        if (Prefs.cocoonImageMode(context) == Prefs.COCOON_MODE_LIVE ||
            GamePreviewStore.isLocked) return
        var shouldLoad: Pair<String, String>? = null
        synchronized(lock) {
            if (!index.containsKey(key)) return
            selectedTitle = key
            selectedAt = SystemClock.elapsedRealtime()
            lastDecision = "Visual match for Cocoon artwork key: $key"
            status = "Game image recognized automatically"
            val pkg = GamePreviewStore.currentPackage
            if (pkg != null && !NovaAppKinds.isCocoon(context, pkg) &&
                !NovaAppKinds.isSettings(context, pkg)) {
                assignedPackage = pkg
                assignedTitle = key
                clearCached()
                shouldLoad = key to pkg
            }
        }
        shouldLoad?.let { (title, pkg) ->
            loadFor(context.applicationContext, title, pkg)
        }
    }

    fun activeArtFor(context: Context, packageName: String?): Bitmap? {
        if (Prefs.cocoonImageMode(context) == Prefs.COCOON_MODE_LIVE) return null
        synchronized(lock) {
            if (assignedPackage != packageName || imagePackage != packageName ||
                imageMode != Prefs.cocoonImageMode(context) || imageTitle != assignedTitle) return null
            return cachedImage?.takeUnless { it.isRecycled }
        }
    }

    fun diagnostics(context: Context): String = synchronized(lock) {
        listOf(
            "Novalock Cocoon artwork diagnostics",
            "Mode: ${Prefs.cocoonImageMode(context)}",
            "Folder selected: ${selectedFolder(context)}",
            "Canonical game keys in index: ${index.size}",
            "Example indexed keys: $indexedExamples",
            "Last Cocoon labels: $lastCocoonLabels",
            "Last emulator labels: $lastEmulatorLabels",
            "Last foreground package: $lastForeground",
            "Assigned title: ${assignedTitle ?: "(none)"}",
            "Match decision: $lastDecision",
            "Visual matcher: ${CocoonVisualMatcher.report}",
            "Last status: $status"
        ).joinToString("\n")
    }

    fun selectedFolder(context: Context): Boolean = Prefs.cocoonFolderUri(context) != null

    fun connectFolder(context: Context, uri: Uri, done: (String) -> Unit) {
        Prefs.setCocoonFolderUri(context, uri.toString())
        rebuildIndex(context, done)
    }

    /** Rebuild once per process, on wake while interactive, if enabled. */
    fun ensureIndexed(context: Context) {
        if (Prefs.cocoonImageMode(context) == Prefs.COCOON_MODE_LIVE) return
        if (!selectedFolder(context)) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive || GamePreviewStore.isLocked) return
        if (CocoonVisualMatcher.needsBuild()) {
            val currentImages = synchronized(lock) { visualReferences }
            if (currentImages.isNotEmpty()) {
                CocoonVisualMatcher.rebuild(context, currentImages)
                return
            }
        }
        val required = synchronized(lock) {
            if (attemptedIndex || indexing || index.isNotEmpty()) false
            else {
                attemptedIndex = true
                true
            }
        }
        if (required) rebuildIndex(context)
    }

    /** Explicit refresh: safe to call from settings UI, not from screen-off. */
    fun rebuildIndex(context: Context, done: (String) -> Unit = {}) {
        val tree = Prefs.cocoonFolderUri(context)?.let { Uri.parse(it) }
        if (tree == null) {
            status = "Select your Cocoon artwork folder"
            done(status)
            return
        }
        synchronized(lock) {
            if (indexing) {
                done("Artwork scan already running")
                return
            }
            indexing = true
            attemptedIndex = true
            status = "Scanning permitted Cocoon folder..."
        }
        val app = context.applicationContext
        Thread({
            var count = 0
            var message: String
            val mutable = mutableMapOf<String, MutableMap<String, Artwork>>()
            // Keep ALL image variants in visual comparison, but group their
            // filename hashes under a single canonical game identity.
            val allVisualArt = ArrayList<CocoonVisualMatcher.ArtworkReference>()
            try {
                val root = DocumentsContract.getTreeDocumentId(tree)
                fun walk(folderId: String, depth: Int, directoryPath: List<String>) {
                    if (depth > MAX_DEPTH || count >= MAX_IMAGES) return
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
                    val columns = arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    )
                    app.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                        val docCol = cursor.getColumnIndexOrThrow(columns[0])
                        val nameCol = cursor.getColumnIndexOrThrow(columns[1])
                        val typeCol = cursor.getColumnIndexOrThrow(columns[2])
                        while (cursor.moveToNext() && count < MAX_IMAGES) {
                            val id = cursor.getString(docCol) ?: continue
                            val name = cursor.getString(nameCol) ?: continue
                            val mime = cursor.getString(typeCol) ?: ""
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                walk(id, depth + 1, directoryPath + name)
                            } else {
                                val ext = name.substringAfterLast('.', "").lowercase()
                                if (ext !in assetExtensions) continue
                                count++
                                val basename = name.substringBeforeLast('.')
                                val source = (directoryPath.joinToString(" ") + " " +
                                    basename).lowercase()
                                val kind = when {
                                    Regex("(^|[^a-z])hero(?:es)?([^a-z]|$)").containsMatchIn(source) -> "hero"
                                    Regex("(^|[^a-z])logos?([^a-z]|$)").containsMatchIn(source) -> "logo"
                                    Regex("(^|[^a-z])(icons?|covers?|grids?)([^a-z]|$)").containsMatchIn(source) -> "icon"
                                    else -> "any"
                                }
                                val stripped = basename
                                    .replace(Regex("(?i)([_\\- ](?:hero|logo|icon|cover|grid|art|image))+$"), "")
                                val key = canonicalGameKey(stripped, directoryPath)
                                if (key.length < 3) continue
                                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                                val entry = mutable.getOrPut(key) { mutableMapOf() }
                                if (kind !in entry) entry[kind] = Artwork(uri, kind)
                                // Multiple edit hashes now share the SAME gameKey.
                                // Competing images of one title are not different games.
                                allVisualArt.add(
                                    CocoonVisualMatcher.ArtworkReference(key, kind, uri))
                            }
                        }
                    }
                }
                walk(root, 0, emptyList())
                synchronized(lock) {
                    index = mutable.mapValues { it.value.toMap() }
                    visualReferences = allVisualArt.toList()
                    selectedTitle = null
                    assignedPackage = null
                    assignedTitle = null
                    clearCached()
                    indexedExamples = index.keys.sorted().take(12).joinToString(" | ").take(380)
                }
                CocoonVisualMatcher.rebuild(app, allVisualArt)
                message = "Indexed ${index.size} artwork keys from $count image files; building visual matches"
            } catch (e: Exception) {
                message = "Cannot read folder: ${e.javaClass.simpleName}. Select the Cocoon data folder."
            } finally {
                synchronized(lock) { indexing = false }
            }
            status = message
            done(message)
        }, "Novalock-Cocoon-Index").start()
    }

    private fun loadFor(context: Context, title: String, pkg: String) {
        val mode = Prefs.cocoonImageMode(context)
        val artwork = synchronized(lock) {
            val choices = index[title] ?: return
            choices[mode] ?: choices["any"] ?: return
        }
        Thread({
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isInteractive) return@Thread
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(artwork.imageUri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                val sample = generateSequence(1) { it * 2 }
                    .takeWhile { maxOf(bounds.outWidth, bounds.outHeight) / it > 1000 }
                    .lastOrNull() ?: 1
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample.coerceAtLeast(1)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = context.contentResolver.openInputStream(artwork.imageUri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                } ?: return@Thread
                synchronized(lock) {
                    if (assignedPackage == pkg && assignedTitle == title &&
                        Prefs.cocoonImageMode(context) == mode && pm.isInteractive) {
                        clearCached()
                        cachedImage = bitmap
                        imagePackage = pkg
                        imageTitle = title
                        imageMode = mode
                        status = "Matched Cocoon artwork for current game"
                    } else bitmap.recycle()
                }
            } catch (_: Exception) {
                status = "Artwork unavailable; screenshot fallback active"
            }
        }, "Novalock-Cocoon-Image").start()
    }

    private fun clearCached() {
        cachedImage = null
        imagePackage = null
        imageTitle = null
        imageMode = null
    }
}
