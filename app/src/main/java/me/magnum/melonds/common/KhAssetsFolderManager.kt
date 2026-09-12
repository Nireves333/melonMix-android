package me.magnum.melonds.common

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// [KHMM] Central resolver for the Melon Mix asset-pack location. Since 1.0.1 the packs live
// in a user-visible shared-storage folder (default: <internal storage>/MelonMix/assets) so
// they can be managed with any file manager and survive an uninstall. Reading it requires
// the all-files-access permission (Android 11+) or the legacy storage permission (below).
// The pre-1.0.1 app-scoped location (Android/data/<pkg>/files/assets) is no longer read at
// runtime; it is only probed to offer a one-time migration.
@Singleton
class KhAssetsFolderManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: SharedPreferences,
) {
    companion object {
        const val PREF_CUSTOM_ROOT = "kh_assets_custom_root"
        const val PREF_SETUP_PROMPT_HANDLED = "kh_assets_setup_prompt_handled"
        private const val SPACE_MARGIN_BYTES = 128L * 1024 * 1024
    }

    fun defaultMelonMixRoot(): File = File(Environment.getExternalStorageDirectory(), "MelonMix")

    fun customMelonMixRoot(): File? {
        return preferences.getString(PREF_CUSTOM_ROOT, null)?.takeIf { it.isNotBlank() }?.let { File(it) }
    }

    /** The user-facing Melon Mix folder; asset packs go in "<this>/assets/<game>/...". */
    fun melonMixRoot(): File = customMelonMixRoot() ?: defaultMelonMixRoot()

    /** The folder handed to native code (MELON_MIX_ASSETS); the plugin appends "<game>/...". */
    fun assetsRoot(): File = File(melonMixRoot(), "assets")

    fun setCustomMelonMixRoot(path: String?) {
        preferences.edit { putString(PREF_CUSTOM_ROOT, path.orEmpty()) }
    }

    /** Whether the one-time setup prompt on the ROM list has been answered (or dismissed);
     *  the same controls stay reachable from Settings, so it never re-nags. */
    fun isSetupPromptHandled(): Boolean = preferences.getBoolean(PREF_SETUP_PROMPT_HANDLED, false)

    fun setSetupPromptHandled() {
        preferences.edit { putBoolean(PREF_SETUP_PROMPT_HANDLED, true) }
    }

    fun hasStorageAccess(): Boolean {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) return false
        // Some firmwares report the permission as granted while the storage layer still
        // denies real access (device-verified on the RG505: isExternalStorageManager()
        // returns true with the All-files toggle OFF), so verify with an actual write
        // probe. Creating the folder as a side effect is deliberate: the user should be
        // able to find it in a file manager as soon as access exists.
        return runCatching {
            val root = melonMixRoot()
            root.mkdirs()
            val probe = File(root, ".melonmix-probe")
            probe.exists() || (probe.createNewFile() && probe.delete())
        }.getOrDefault(false)
    }

    /** Converts a SAF folder-picker result to a plain filesystem path. The system picker is
     *  only used for its UX; the native side needs a real path, which all-files access can
     *  then read. Returns null for locations that don't map to one (e.g. cloud providers). */
    fun treeUriToPath(treeUri: Uri): String? {
        if (treeUri.authority != "com.android.externalstorage.documents") return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val split = docId.split(":", limit = 2)
        val base = if (split[0].equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/${split[0]}"
        }
        val relativePath = split.getOrElse(1) { "" }
        return if (relativePath.isEmpty()) base else "$base/$relativePath"
    }

    fun legacyAssetsRoot(): File? = context.getExternalFilesDir(null)?.let { File(it, "assets") }

    // NOTE: both probes stat known subfolders instead of listing the directory. A tree
    // pushed over adb is owned by the shell user on the lower filesystem and FUSE then
    // refuses to LIST it for the app, even though stat and file opens by path work fine
    // (device-verified on the RG505) — and the plugin only ever opens files by path.
    fun hasLegacyAssets(): Boolean {
        val legacy = legacyAssetsRoot() ?: return false
        return File(legacy, "days").exists() || File(legacy, "recoded").exists()
    }

    fun hasAssets(): Boolean {
        return runCatching {
            File(assetsRoot(), "days").exists() || File(assetsRoot(), "recoded").exists()
        }.getOrDefault(false)
    }

    /** True when the pre-1.0.1 app-scoped assets are present and nothing usable exists at the
     *  new location yet, i.e. the one-time move should be offered. */
    fun needsMigration(): Boolean = hasLegacyAssets() && !hasAssets()

    sealed class MigrationResult {
        data object Success : MigrationResult()
        data object NothingToMigrate : MigrationResult()
        data class NotEnoughSpace(val requiredBytes: Long) : MigrationResult()
        data class Failed(val reason: String?) : MigrationResult()
    }

    /** Moves the app-scoped assets tree to [assetsRoot]. A rename is attempted first, but
     *  in the app's own mount view Android/data is usually a raw bind mount while shared
     *  storage goes through FUSE (device-verified on the RG505), so the rename fails
     *  cross-device and the real path is a copy plus delete — minutes for multi-GB packs,
     *  hence the free-space check up front. */
    suspend fun migrateLegacyAssets(): MigrationResult = withContext(Dispatchers.IO) {
        val legacy = legacyAssetsRoot() ?: return@withContext MigrationResult.NothingToMigrate
        if (!hasLegacyAssets()) return@withContext MigrationResult.NothingToMigrate
        val target = assetsRoot()
        try {
            target.parentFile?.mkdirs()
            // An empty leftover target blocks the rename; clear it (delete() only removes
            // empty directories, so real content is never at risk here).
            if (target.exists() && target.listFiles().isNullOrEmpty()) {
                target.delete()
            }
            if (!target.exists() && legacy.renameTo(target)) {
                android.util.Log.i("KhAssets", "migrated legacy assets via rename")
                return@withContext MigrationResult.Success
            }
            val requiredBytes = legacy.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val availableBytes = StatFs(target.parentFile!!.absolutePath).availableBytes
            if (availableBytes < requiredBytes + SPACE_MARGIN_BYTES) {
                android.util.Log.w("KhAssets", "migration needs $requiredBytes bytes, only $availableBytes free")
                return@withContext MigrationResult.NotEnoughSpace(requiredBytes)
            }
            legacy.copyRecursively(target, overwrite = true)
            legacy.deleteRecursively()
            android.util.Log.i("KhAssets", "migrated legacy assets via copy ($requiredBytes bytes)")
            MigrationResult.Success
        } catch (e: Exception) {
            android.util.Log.w("KhAssets", "legacy asset migration failed", e)
            MigrationResult.Failed(e.message)
        }
    }
}
