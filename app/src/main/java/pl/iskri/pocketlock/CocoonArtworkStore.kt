package pl.iskri.pocketlock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.provider.DocumentsContract
import java.text.Normalizer
import java.util.Locale

/**
 * Local, opt-in Cocoon artwork resolver (V6.7).
 *
 * Ground truth: an actual game title exposed by the EMULATOR's Android
 * window-state event, never a guess from Cocoon's HOME grid or platform art.
 *
 * Assets: read-only Android Storage Access Framework folder selected once.
 * No network requests, no per-game interaction, no OCR, no image fingerprint
 * scanning, and no activity at all while the display sleeps.
 *
 * A missing or ambiguous title, image or package always falls back to the
 * existing live gameplay screenshot.
 */
object CocoonArtworkStore {
    private data class Artwork(val uri: Uri, val category: String)
    private val lock = Any()
    private var index: Map<String, Map<String, Artwork>> = emptyMap()
    private var scanning = false
    private var scanAttempted = false

    private var pendingCocoonGame: String? = null
    private var pendingCocoonAt: Long = 0L
    private var lastCocoonSelectionEvent = "(no selected Cocoon node yet)"
    private var lastCocoonSelectionLabels = "(none)"
    private var lastCocoonSelectionMatch = "(none)"
    private var cocoonSelectionEventCount = 0
    private var focusCoordinates = "(no Cocoon focused bounds captured)"
    private var focusEvents = 0
    private var assignedPackage: String? = null
    private var assignedTitle: String? = null
    private var imagePackage: String? = null
    private var imageTitle: String? = null
    private var imageMode: String? = null
    private var cachedImage: Bitmap? = null
    private var generation: Long = 0L

    private var lastEmulatorTitle = "(not received)"
    private var lastEmulatorPackage = "(none)"
    private var lastMatchingKeys = "(not checked)"
    private var lastAvailableImages = "(not checked)"
    private var lastDecision = "Waiting for emulator game title"
    private var indexedExamples = "(not indexed)"
    @Volatile var status: String = "Select the Cocoon artwork folder"
        private set

    private val extensions = setOf("png", "jpg", "jpeg", "webp")
    private val platformKeys = setOf(
        "3do", "amiga", "amstradcpc", "android", "megacd", "sega cd", "segacd",
        "mega drive", "megadrive", "genesis", "arcade", "atari", "gameboy",
        "gb", "gbc", "gba", "nes", "snes", "nintendo", "n64", "nds", "3ds",
        "switch", "gamecube", "wii", "wii u", "playstation", "ps1",
        "psx", "ps2", "psp", "psvita", "saturn", "dreamcast", "windows",
        "pc", "steam", "retroarch", "eden", "dolphin", "azahar",
        "emulators", "games", "systems", "favorites", "favourites"
    )
    internal fun isPlatformKey(key: String): Boolean = key in platformKeys

    private val genericFolders = setOf(
        "cocoon", "edit", "images", "image", "artwork", "art", "media",
        "hero", "heroes", "icon", "icons", "logo", "logos", "cover",
        "covers", "grid", "grids", "boxart", "backgrounds", "background"
    )

    private const val COCOON_SELECTION_MAX_AGE_MS = 20_000L
    private const val MAX_IMAGES = 3500
    private const val MAX_DEPTH = 7
    private val generatedSuffix = Regex(
        " (?:cocoon )?edit [0-9]{1,4} [0-9a-f]{12,}$")
    private val versionSuffix = Regex(
        " (?:rev [0-9]+|v[0-9]+(?: [0-9]+)*|ver [0-9]+|disc [0-9]+|disk [0-9]+)$")
    private val regionSuffix = Regex(
        " (?:the )?(?:usa|us|europe|eur|japan|jp|world|asia|australia|uk|pal|ntsc|korea|china)$")
    private val languageSuffix = Regex(
        " (?:en|eng|fr|de|es|it|pt|nl)(?: (?:en|eng|fr|de|es|it|pt|nl))*$")

    fun normalizeTitle(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim().replace(Regex("\\s+"), " ")

    private fun fileGameKey(raw: String): String {
        var normalized = normalizeTitle(raw)
        normalized = normalized.replace(generatedSuffix, "")
            .replace(Regex(" cocoon [0-9]{1,4} [0-9a-f]{12,}$"), "")
            .replace(Regex(" [0-9a-f]{24,}$"), "")
        return normalized.trim()
    }

    /**
     * Remove ONLY known ROM edition metadata from the end. Do not use prefix
     * or fuzzy matching: "Mario" must never resolve to "Mario Kart".
     */
    internal fun gameTitleStem(raw: String): String {
        var result = fileGameKey(raw)
        repeat(6) {
            val next = result.replace(languageSuffix, "")
                .replace(versionSuffix, "")
                .replace(regionSuffix, "").trim()
            if (next == result) return result
            result = next
        }
        return result
    }

    private fun canonicalFileKey(filename: String, parents: List<String>): String {
        val own = fileGameKey(filename)
        if (own.length >= 3 && own !in genericFolders &&
            !own.matches(Regex("[0-9a-f]{12,}")) &&
            !own.matches(Regex("[0-9 ]+"))) return own
        return parents.asReversed().map(::fileGameKey).firstOrNull {
            it.length >= 3 && it !in genericFolders &&
                it !in platformKeys && !it.matches(Regex("[0-9a-f]{12,}")) &&
                !it.matches(Regex("[0-9 ]+"))
        } ?: own
    }

    private data class Choice(
        val gameKey: String, val available: Map<String, Artwork>,
        val note: String
    )

    /** A single known title, or multiple editions of that SAME title. */
    private fun resolveTitleLocked(raw: String, mode: String): Choice? {
        val title = normalizeTitle(raw)
        if (title.length < 4 || title in platformKeys) {
            lastMatchingKeys = "(label is not a game title)"
            return null
        }
        val exact = index[title]?.takeIf { title !in platformKeys }
        if (exact != null) {
            lastMatchingKeys = title
            return Choice(title, exact, "exact emulator title")
        }
        val base = gameTitleStem(title)
        if (base.length < 4 || base in platformKeys) return null
        val candidates = index.entries.filter { (key, _) ->
            key !in platformKeys && gameTitleStem(key) == base
        }.sortedWith(
            compareByDescending<Map.Entry<String, Map<String, Artwork>>> {
                it.value.containsKey(mode)
            }.thenByDescending {
                it.value.containsKey("any")
            }.thenBy {
                // Prefer unsuffixed/world title over regional choices.
                if (it.key == base) 0 else if (it.key.endsWith(" world")) 1
                else if (it.key.endsWith(" europe")) 2
                else if (it.key.endsWith(" usa")) 3 else 4
            }.thenBy { it.key }
        )
        lastMatchingKeys = if (candidates.isEmpty()) {
            val related = index.keys.filter {
                it.contains(base) || base.contains(it) && it.length >= 6
            }.take(6)
            if (related.isEmpty()) "(no local image for '$title')"
            else "No exact edition; related names: ${related.joinToString(" | ")}"
        } else candidates.take(6).joinToString(" | ") { it.key }
        val selected = candidates.firstOrNull() ?: return null
        return Choice(selected.key, selected.value,
            if (candidates.size == 1) "title plus ROM region/version metadata"
            else "${candidates.size} editions of same game; best available selected")
    }

    fun observeEmulatorWindowTitle(
        context: Context, packageName: String, texts: List<String>
    ) {
        if (Prefs.cocoonImageMode(context) == Prefs.COCOON_MODE_LIVE ||
            GamePreviewStore.isLocked ||
            NovaAppKinds.isCocoon(context, packageName) ||
            NovaAppKinds.isSettings(context, packageName) ||
            !NovaAppKinds.isSafeApp(packageName)) return

        val labels = texts.map(::normalizeTitle).filter { it.length >= 4 }.distinct()
        if (labels.isEmpty()) return
        val mode = Prefs.cocoonImageMode(context)
        var loading: Triple<String, String, Long>? = null
        synchronized(lock) {
            lastEmulatorTitle = labels.joinToString(" | ").take(240)
            lastEmulatorPackage = packageName
            val choices = labels.mapNotNull { label ->
                resolveTitleLocked(label, mode)?.let { label to it }
            }
            val uniqueGameStems = choices.map { gameTitleStem(it.second.gameKey) }.distinct()
            if (uniqueGameStems.size != 1 || choices.isEmpty()) {
                lastDecision = if (index.isEmpty()) "Image index is empty"
                    else if (uniqueGameStems.size > 1) "Ambiguous emulator titles"
                    else "No matching game artwork in selected folder"
                status = lastDecision
                return
            }
            val winner = choices.first().second
            lastAvailableImages = winner.available.keys.sorted().joinToString(", ")
            val chosen = winner.available[mode] ?: winner.available["any"]
            if (chosen == null) {
                lastDecision = "Title recognized (${winner.gameKey}), but no '$mode' image"
                status = lastDecision
                return
            }
            if (assignedPackage == packageName && assignedTitle == winner.gameKey &&
                cachedImage != null && imageMode == mode) return
            assignedPackage = packageName
            assignedTitle = winner.gameKey
            cachedImage = null
            imagePackage = null
            imageTitle = null
            imageMode = null
            generation += 1
            lastDecision = "Emulator title matched: ${winner.gameKey} (${winner.note})"
            status = "Loading Cocoon $mode for ${winner.gameKey}"
            loading = Triple(winner.gameKey, packageName, generation)
        }
        loading?.let { (key, pkg, token) ->
            loadFor(context.applicationContext, key, pkg, mode, token)
        }
    }

    /**
     * A genuine Cocoon accessibility focus/selection/click event provides
     * a candidate for the NEXT emulator launch. We never assign a title
     * based on all-visible tiles, fuzzy image scores or arbitrary HOME text.
     *
     * The event's focused or clicked node must uniquely resolve to one game
     * in the user's previously granted local artwork folder.
     */
    fun observeCocoonGameSelection(
        context: Context, eventType: String, labels: List<String>
    ) {
        if (Prefs.cocoonImageMode(context) == Prefs.COCOON_MODE_LIVE ||
            GamePreviewStore.isLocked || !selectedFolder(context)) return
        val normalized = labels.map(::normalizeTitle)
            .filter { it.length >= 4 }.distinct().take(18)
        synchronized(lock) {
            cocoonSelectionEventCount++
            lastCocoonSelectionEvent = eventType
            lastCocoonSelectionLabels = normalized.take(6)
                .joinToString(" | ").take(230).ifEmpty { "(no exposed text)" }
            // Match titles from the selected node, not other tiles in the grid.
            val matches = normalized.mapNotNull { label ->
                resolveTitleLocked(label, Prefs.cocoonImageMode(context))
            }
            val stems = matches.map { gameTitleStem(it.gameKey) }.distinct()
            if (stems.size != 1) {
                lastCocoonSelectionMatch = when {
                    normalized.isEmpty() -> "Selected Cocoon node has no readable game title"
                    stems.isEmpty() -> "Selected node text does not match a game image"
                    else -> "Selected Cocoon node contains multiple game titles"
                }
                // Clear previously selected title on a *new* focused/clicked
                // item that does not match; never reuse a stale game.
                // A generic "Play" click follows selection on some
                // launchers. Keep a recent focused title across that click,
                // but clear when focus has moved to an unrecognized item.
                if (eventType.contains("FOCUSED") ||
                    eventType.contains("SELECTED")) {
                    pendingCocoonGame = null
                    pendingCocoonAt = 0L
                }
                return
            }
            val best = matches.first()
            if (best.gameKey in platformKeys) return
            pendingCocoonGame = best.gameKey
            pendingCocoonAt = SystemClock.elapsedRealtime()
            lastCocoonSelectionMatch = "Selected game: ${best.gameKey}"
            lastDecision = "Cocoon selected game before emulator launch: ${best.gameKey}"
            status = lastDecision
        }
    }

    /**
     * Feedback about accessibility bounds helps distinguish real selected
     * game cards from fullscreen/opaque Cocoon Flutter containers.
     */
    fun noteFocusedBounds(type: String, bounds: android.graphics.Rect?, note: String) {
        synchronized(lock) {
            focusEvents++
            focusCoordinates = "$type: ${bounds?.flattenToString() ?: "(none)"}; $note"
            if (bounds == null) {
                pendingCocoonGame = null
                pendingCocoonAt = 0L
            }
        }
    }

    /**
     * Strong, unique pixel match from the actual focused Cocoon card only.
     * This does NOT choose artwork from unrelated HOME tiles.
     */
    fun observeFocusedGameImage(context: Context, key: String, score: Int, next: Int) {
        if (GamePreviewStore.isLocked || Prefs.cocoonImageMode(context) == Prefs.COCOON_MODE_LIVE)
            return
        synchronized(lock) {
            if (index[key] == null || isPlatformKey(key)) return
            pendingCocoonGame = key
            pendingCocoonAt = SystemClock.elapsedRealtime()
            lastCocoonSelectionEvent = "FOCUSED_CARD_IMAGE"
            lastCocoonSelectionMatch = "Game card matched: $key (score $score, runner $next)"
            lastDecision = lastCocoonSelectionMatch
            status = "Cocoon focused card recognized: $key"
        }
    }

    fun observeForeground(context: Context, packageName: String) {
        if (Prefs.cocoonImageMode(context) == Prefs.COCOON_MODE_LIVE) return
        var loading: Triple<String, String, Long>? = null
        val mode = Prefs.cocoonImageMode(context)
        synchronized(lock) {
            if (NovaAppKinds.isCocoon(context, packageName)) {
                resetAssignmentLocked()
                status = "Cocoon HOME; awaiting emulator game launch"
                return
            }
            if (NovaAppKinds.isSettings(context, packageName)) {
                resetAssignmentLocked()
                pendingCocoonGame = null
                pendingCocoonAt = 0L
                status = "Settings opened; game artwork cleared"
                return
            }
            if (assignedPackage != null && assignedPackage != packageName) {
                resetAssignmentLocked()
                lastDecision = "Emulator changed; waiting for new game title"
            }
            val selected = pendingCocoonGame
            val fresh = pendingCocoonAt != 0L &&
                SystemClock.elapsedRealtime() - pendingCocoonAt in 0..COCOON_SELECTION_MAX_AGE_MS
            if (selected != null && fresh && !GamePreviewStore.isLocked &&
                NovaAppKinds.isLikelyEmulator(context, packageName)) {
                val art = index[selected]?.let { it[mode] ?: it["any"] }
                if (art != null) {
                    resetAssignmentLocked()
                    assignedPackage = packageName
                    assignedTitle = selected
                    generation += 1
                    loading = Triple(selected, packageName, generation)
                    lastDecision = "Cocoon selected title bound to emulator: $selected"
                    status = "Loading Cocoon $mode for $selected"
                } else {
                    lastDecision = "Cocoon selected '$selected' but chosen $mode image unavailable"
                }
            }
            if (selected != null && fresh &&
                !NovaAppKinds.isLikelyEmulator(context, packageName)) {
                lastDecision = "Cocoon selection not assigned: next app is not an emulator"
            }
            pendingCocoonGame = null
            pendingCocoonAt = 0L
        }
        loading?.let { (key, pkg, token) ->
            loadFor(context.applicationContext, key, pkg, mode, token)
        }
    }

    fun activeArtFor(context: Context, packageName: String?): Bitmap? {
        if (Prefs.cocoonImageMode(context) == Prefs.COCOON_MODE_LIVE ||
            packageName.isNullOrBlank()) return null
        synchronized(lock) {
            if (assignedPackage != packageName || imagePackage != packageName ||
                assignedTitle != imageTitle ||
                imageMode != Prefs.cocoonImageMode(context)) return null
            return cachedImage?.takeUnless { it.isRecycled }
        }
    }

    private fun resetAssignmentLocked() {
        generation += 1
        assignedPackage = null
        assignedTitle = null
        imagePackage = null
        imageTitle = null
        imageMode = null
        cachedImage = null
    }

    private fun loadFor(
        context: Context, key: String, pkg: String, mode: String, token: Long
    ) {
        val art = synchronized(lock) {
            val variants = index[key] ?: return
            variants[mode] ?: variants["any"] ?: return
        }
        Thread({
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isInteractive || GamePreviewStore.isLocked) return@Thread
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(art.uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    synchronized(lock) {
                        if (generation == token) status = "Invalid image; screenshot fallback"
                    }
                    return@Thread
                }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1100 &&
                    sample < 32) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val image = context.contentResolver.openInputStream(art.uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                } ?: return@Thread
                synchronized(lock) {
                    if (generation == token && assignedPackage == pkg &&
                        assignedTitle == key && Prefs.cocoonImageMode(context) == mode &&
                        pm.isInteractive && !GamePreviewStore.isLocked) {
                        cachedImage = image
                        imagePackage = pkg
                        imageTitle = key
                        imageMode = mode
                        status = "Verified emulator title, image ready: $key"
                    } else image.recycle()
                }
            } catch (e: Exception) {
                synchronized(lock) {
                    if (generation == token) {
                        status = "Cocoon image unreadable (${e.javaClass.simpleName}); screenshot fallback"
                    }
                }
            }
        }, "Novalock-Game-Art").start()
    }

    fun selectedFolder(context: Context): Boolean =
        Prefs.cocoonFolderUri(context) != null

    fun connectFolder(context: Context, uri: Uri, done: (String) -> Unit) {
        Prefs.setCocoonFolderUri(context, uri.toString())
        rebuildIndex(context, done)
    }

    fun ensureIndexed(context: Context) {
        if (Prefs.cocoonImageMode(context) == Prefs.COCOON_MODE_LIVE ||
            !selectedFolder(context)) return
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!power.isInteractive || GamePreviewStore.isLocked) return
        CocoonCardMatcher.ensureBuilt(context)
        val needed = synchronized(lock) {
            !scanAttempted && !scanning && index.isEmpty()
        }
        if (needed) rebuildIndex(context)
    }

    fun rebuildIndex(context: Context, done: (String) -> Unit = {}) {
        val tree = Prefs.cocoonFolderUri(context)?.let { Uri.parse(it) }
        if (tree == null) {
            status = "Select Cocoon artwork folder"
            done(status)
            return
        }
        synchronized(lock) {
            if (scanning) {
                done("Already indexing Cocoon artwork")
                return
            }
            scanning = true
            scanAttempted = true
            status = "Indexing permitted Cocoon artwork folder..."
        }
        val app = context.applicationContext
        Thread({
            val images = mutableMapOf<String, MutableMap<String, Artwork>>()
            val visualRefs = ArrayList<CocoonCardMatcher.ArtworkRef>()
            var fileCount = 0
            var failure: String? = null
            try {
                val root = DocumentsContract.getTreeDocumentId(tree)
                fun walk(folder: String, parents: List<String>, depth: Int) {
                    if (depth > MAX_DEPTH || fileCount >= MAX_IMAGES) return
                    val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder)
                    val columns = arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    )
                    app.contentResolver.query(childUri, columns, null, null, null)?.use { cursor ->
                        val ci = cursor.getColumnIndexOrThrow(columns[0])
                        val ni = cursor.getColumnIndexOrThrow(columns[1])
                        val mi = cursor.getColumnIndexOrThrow(columns[2])
                        while (cursor.moveToNext() && fileCount < MAX_IMAGES) {
                            val id = cursor.getString(ci) ?: continue
                            val name = cursor.getString(ni) ?: continue
                            val mime = cursor.getString(mi) ?: ""
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                walk(id, parents + name, depth + 1)
                                continue
                            }
                            if (name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                                !in extensions) continue
                            fileCount++
                            val stem = name.substringBeforeLast('.')
                            val path = (parents.joinToString(" ") + " " + stem)
                                .lowercase(Locale.ROOT)
                            val category = when {
                                Regex("(^|[^a-z])heroes?([^a-z]|$)").containsMatchIn(path) -> "hero"
                                Regex("(^|[^a-z])logos?([^a-z]|$)").containsMatchIn(path) -> "logo"
                                Regex("(^|[^a-z])(icons?|covers?|grids?)([^a-z]|$)")
                                    .containsMatchIn(path) -> "icon"
                                else -> "any"
                            }
                            val key = canonicalFileKey(stem, parents)
                            if (key.length < 3 || key in platformKeys) continue
                            val imageUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                            // The image matcher needs every unique icon/hero
                            // variant, but only one visual identity per game.
                            visualRefs.add(CocoonCardMatcher.ArtworkRef(key, category, imageUri))
                            val variants = images.getOrPut(key) { mutableMapOf() }
                            // Stable first available variant for each kind.
                            variants.putIfAbsent(category, Artwork(imageUri, category))
                        }
                    }
                }
                walk(root, emptyList(), 0)
                CocoonCardMatcher.rebuild(app, visualRefs)
                synchronized(lock) {
                    index = images.mapValues { it.value.toMap() }
                    resetAssignmentLocked()
                    indexedExamples = index.keys.sorted().take(12).joinToString(" | ").take(400)
                    lastDecision = "Index ready; waiting for emulator title"
                }
            } catch (e: Exception) {
                failure = e.javaClass.simpleName
            } finally {
                synchronized(lock) { scanning = false }
                status = if (failure != null) {
                    "Folder indexing failed: $failure"
                } else {
                    "Indexed ${index.size} game keys from $fileCount image files"
                }
                done(status)
            }
        }, "Novalock-Game-Index").start()
    }

    fun diagnostics(context: Context): String = synchronized(lock) {
        listOf(
            "Novalock Cocoon artwork diagnostics (emulator title mode)",
            "Mode: ${Prefs.cocoonImageMode(context)}",
            "Folder selected: ${selectedFolder(context)}",
            "Game keys in index: ${index.size}",
            "Example indexed keys: $indexedExamples",
            "Cocoon selection events: $cocoonSelectionEventCount",
            "Focused-node event count: $focusEvents",
            "Focused-node bounds: $focusCoordinates",
            "Focused image index: ${CocoonCardMatcher.status}",
            "Focused image match: ${CocoonCardMatcher.lastMatch}",
            "Last Cocoon selection event: $lastCocoonSelectionEvent",
            "Selected-node text: $lastCocoonSelectionLabels",
            "Selected-node result: $lastCocoonSelectionMatch",
            "Pending Cocoon game: ${pendingCocoonGame ?: "(none)"}",
            "Last emulator title: $lastEmulatorTitle",
            "Last emulator package: $lastEmulatorPackage",
            "Matching indexed keys: $lastMatchingKeys",
            "Available images for matched game: $lastAvailableImages",
            "Assigned title: ${assignedTitle ?: "(none)"}",
            "Loaded image: ${imageTitle ?: "(none)"}",
            "Decision: $lastDecision",
            "Status: $status",
            "Source: emulator title OR uniquely matched focused Cocoon card (no global HOME guessing)"
        ).joinToString("\n")
    }
}
