package pl.iskri.pocketlock

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast

/**
 * Optional Cocoon -> Novalock -> emulator handoff.
 *
 * Cocoon's public platform JSON allows a player with an explicit Android
 * activity and a {file.uri} argument. We use that ACTUAL selected ROM URI,
 * not emulator window titles, image hashes, OCR or Accessibility guessing.
 *
 * The relay is used only by users who import a Novalock-patched platform JSON
 * and select the "Novalock -> emulator" player once for that platform.
 * Original Cocoon players remain selectable as an instant rollback.
 */
class CocoonRelayActivity : Activity() {
    private data class Route(
        val targetPackage: String,
        val activity: String,
        val action: String,
        val dataBased: Boolean,
        val extraKey: String? = null,
        val restartTask: Boolean = false
    )

    private fun routeFor(name: String): Route? = when (name) {
        "dolphin_gc" -> Route(
            "org.dolphinemu.dolphinemu",
            "org.dolphinemu.dolphinemu.ui.main.MainActivity",
            Intent.ACTION_VIEW, false, "AutoStartFile")
        "dolphin_wii" -> Route(
            "org.dolphinemu.dolphinemu",
            "org.dolphinemu.dolphinemu.ui.main.MainActivity",
            Intent.ACTION_MAIN, false, "AutoStartFile")
        "armsx2" -> Route(
            "com.armsx2", "com.armsx2.MainActivity",
            Intent.ACTION_VIEW, true, restartTask = true)
        "eden" -> Route(
            "dev.eden.eden_emulator",
            "org.yuzu.yuzu_emu.activities.EmulationActivity",
            "android.nfc.action.TECH_DISCOVERED", true)
        "azahar" -> Route(
            "org.azahar_emu.azahar",
            "org.citra.citra_emu.activities.EmulationActivity",
            Intent.ACTION_VIEW, true, restartTask = true)
        else -> null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        processLaunch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        processLaunch(intent)
    }

    private fun processLaunch(source: Intent?) {
        try {
            if (source == null) {
                finish()
                return
            }
            val routeName = source.getStringExtra("nl_target") ?: ""
            val route = routeFor(routeName)
            val rom = source.data
            if (route == null || rom == null ||
                rom.scheme !in setOf("content", "file")) {
                Toast.makeText(this, "Novalock: unsupported Cocoon launch", Toast.LENGTH_LONG).show()
                finish()
                return
            }

            // The URI is supplied by Cocoon as {file.uri}, so it remains
            // available even if the emulator exposes only its app label.
            val fromPath = source.getStringExtra("nl_path")?.let { path ->
                val leaf = Uri.decode(path).substringAfterLast('/')
                    .substringAfterLast(':').substringBeforeLast('.')
                leaf.takeIf { it.length >= 3 }
            }
            val romName = fromPath ?: safeRomName(rom)
            CocoonArtworkStore.observeRomHandoff(
                this, route.targetPackage, routeName, romName)

            val emulatorIntent = Intent(route.action).apply {
                setClassName(route.targetPackage, route.activity)
                if (route.dataBased) data = rom
                route.extraKey?.let { putExtra(it, rom.toString()) }
                // Pass Cocoon's temporary URI grant to the emulator. String
                // extras alone do NOT propagate content grants on Android.
                if (rom.scheme == "content") {
                    clipData = ClipData.newUri(contentResolver, "Cocoon game", rom)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if (route.restartTask) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            }
            startActivity(emulatorIntent)
            Log.i("NovalockCocoonRelay", "Cocoon ROM handed to ${route.targetPackage}")
        } catch (e: Exception) {
            Log.w("NovalockCocoonRelay", "Emulator launch handoff failed", e)
            Toast.makeText(this, "Novalock relay: emulator could not launch", Toast.LENGTH_LONG).show()
        } finally {
            finish()
        }
    }

    private fun safeRomName(uri: Uri): String {
        // For SAF DocumentsProvider URIs, lastPathSegment is often a document
        // ID such as primary:Games/gc/Super Mario Sunshine (USA).rvz.
        val raw = try {
            DocumentsContract.getDocumentId(uri)
        } catch (_: Exception) {
            uri.lastPathSegment ?: ""
        }
        val fromPath = Uri.decode(raw)
            .substringAfterLast('/').substringAfterLast(':')
            .substringBeforeLast('.', missingDelimiterValue = Uri.decode(raw)
                .substringAfterLast('/').substringAfterLast(':'))
        val fallback = Uri.decode(uri.lastPathSegment ?: "")
            .substringAfterLast('/').substringAfterLast(':')
            .substringBeforeLast('.')
        return fromPath.takeIf { it.length >= 3 } ?: fallback
    }
}
