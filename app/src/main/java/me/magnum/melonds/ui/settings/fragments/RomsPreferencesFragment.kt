package me.magnum.melonds.ui.settings.fragments

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import me.magnum.melonds.R
import me.magnum.melonds.common.DirectoryAccessValidator
import me.magnum.melonds.common.KhAssetsFolderManager
import me.magnum.melonds.common.UriPermissionManager
import me.magnum.melonds.ui.settings.PreferenceFragmentHelper
import me.magnum.melonds.ui.settings.PreferenceFragmentTitleProvider
import javax.inject.Inject

// [KHMM] Settings curation: only the ROM search directory remains (icon filtering and the
// ROM-cache controls were removed; the cache size is pinned in the settings repository).
// This screen also hosts the Melon Mix asset-folder controls (relocatable asset folder).
@AndroidEntryPoint
class RomsPreferencesFragment : BasePreferenceFragment(), PreferenceFragmentTitleProvider {

    private val helper by lazy { PreferenceFragmentHelper(this, uriPermissionManager, directoryAccessValidator) }
    @Inject lateinit var uriPermissionManager: UriPermissionManager
    @Inject lateinit var directoryAccessValidator: DirectoryAccessValidator
    @Inject lateinit var khAssetsFolderManager: KhAssetsFolderManager

    private lateinit var assetsFolderPreference: Preference
    private lateinit var grantAccessPreference: Preference
    private lateinit var migratePreference: Preference

    // [KHMM] The system folder picker is used only for its UX; the result is converted to a
    // plain filesystem path (the native plugin needs real paths, read via all-files access).
    private val assetsFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val path = khAssetsFolderManager.treeUriToPath(uri)
            if (path == null) {
                Toast.makeText(requireContext(), R.string.kh_assets_invalid_folder, Toast.LENGTH_LONG).show()
            } else {
                khAssetsFolderManager.setCustomMelonMixRoot(path)
                updateKhAssetsPreferences()
            }
        }
    }

    // The all-files-access screen reports nothing back; re-check the permission on return.
    private val allFilesAccessLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updateKhAssetsPreferences()
    }

    private val legacyStoragePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        updateKhAssetsPreferences()
    }

    override fun getTitle() = getString(R.string.category_roms)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_roms, rootKey)
        helper.setupStoragePickerPreference(findPreference("rom_search_dirs")!!)

        assetsFolderPreference = findPreference("kh_assets_folder")!!
        grantAccessPreference = findPreference("kh_assets_grant_access")!!
        migratePreference = findPreference("kh_assets_migrate")!!

        assetsFolderPreference.setOnPreferenceClickListener {
            showAssetsFolderDialog()
            true
        }
        grantAccessPreference.setOnPreferenceClickListener {
            requestStorageAccess()
            true
        }
        migratePreference.setOnPreferenceClickListener {
            if (khAssetsFolderManager.hasStorageAccess()) {
                runAssetsMigration()
            } else {
                requestStorageAccess()
            }
            true
        }
        updateKhAssetsPreferences()
    }

    override fun onResume() {
        super.onResume()
        updateKhAssetsPreferences()
    }

    private fun updateKhAssetsPreferences() {
        val root = khAssetsFolderManager.melonMixRoot().absolutePath
        val rootText = if (khAssetsFolderManager.customMelonMixRoot() == null) {
            getString(R.string.kh_assets_folder_summary_default, root)
        } else {
            root
        }
        val status = when {
            !khAssetsFolderManager.hasStorageAccess() -> getString(R.string.kh_assets_no_access)
            khAssetsFolderManager.hasAssets() -> getString(R.string.kh_assets_found)
            else -> getString(R.string.kh_assets_none_found)
        }
        assetsFolderPreference.summary = "$rootText\n$status"
        grantAccessPreference.isVisible = !khAssetsFolderManager.hasStorageAccess()
        migratePreference.isVisible = khAssetsFolderManager.needsMigration()
        migratePreference.summary = getString(R.string.kh_assets_migrate_summary, khAssetsFolderManager.assetsRoot().absolutePath)
    }

    private fun showAssetsFolderDialog() {
        val defaultRoot = khAssetsFolderManager.defaultMelonMixRoot().absolutePath
        val options = arrayOf(
            getString(R.string.kh_assets_choose_folder),
            getString(R.string.kh_assets_use_default, defaultRoot),
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.kh_assets_folder)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> assetsFolderPicker.launch(null)
                    1 -> {
                        khAssetsFolderManager.setCustomMelonMixRoot(null)
                        updateKhAssetsPreferences()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${requireContext().packageName}"))
            try {
                allFilesAccessLauncher.launch(intent)
            } catch (e: Exception) {
                allFilesAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyStoragePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun runAssetsMigration() {
        val progressDialog = AlertDialog.Builder(requireContext())
            .setMessage(R.string.kh_assets_migrating)
            .setCancelable(false)
            .show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = khAssetsFolderManager.migrateLegacyAssets()
            progressDialog.dismiss()
            val message = when (result) {
                is KhAssetsFolderManager.MigrationResult.Failed ->
                    getString(R.string.kh_assets_migration_failed, result.reason.orEmpty())
                is KhAssetsFolderManager.MigrationResult.NotEnoughSpace ->
                    getString(R.string.kh_assets_migration_no_space, Formatter.formatFileSize(requireContext(), result.requiredBytes))
                else ->
                    getString(R.string.kh_assets_migration_done, khAssetsFolderManager.assetsRoot().absolutePath)
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            updateKhAssetsPreferences()
        }
    }
}
