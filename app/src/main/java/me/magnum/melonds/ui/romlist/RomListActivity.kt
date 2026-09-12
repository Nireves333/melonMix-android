package me.magnum.melonds.ui.romlist

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.magnum.melonds.R
import me.magnum.melonds.common.KhAssetsFolderManager
import me.magnum.melonds.domain.model.ConsoleType
import me.magnum.melonds.domain.model.DownloadProgress
import me.magnum.melonds.domain.model.RomScanningStatus
import me.magnum.melonds.domain.model.appupdate.AppUpdate
import me.magnum.melonds.domain.model.rom.Rom
import me.magnum.melonds.parcelables.RomParcelable
import me.magnum.melonds.ui.common.melonTextButtonColors
import me.magnum.melonds.ui.common.rom.EmulatorLaunchValidatorDelegate
import me.magnum.melonds.ui.emulator.EmulatorActivity
import me.magnum.melonds.ui.romdetails.RomDetailsActivity
import me.magnum.melonds.ui.romlist.ui.DownloadProgressDialog
import me.magnum.melonds.ui.romlist.ui.NightlyUpdateDialog
import me.magnum.melonds.ui.romlist.ui.ProdUpdateAvailableDialog
import me.magnum.melonds.ui.romlist.ui.RomListScreen
import me.magnum.melonds.ui.settings.SettingsActivity
import me.magnum.melonds.ui.theme.MelonTheme
import javax.inject.Inject

@AndroidEntryPoint
class RomListActivity : AppCompatActivity() {

    // [KHMM] One-time setup prompt for the shared-storage Melon Mix asset folder: offer the
    // move for pre-1.0.1 app-scoped assets, or explain the folder + permission on a fresh
    // install. Answered once; the same controls live in Settings under ROMs.
    private sealed class KhAssetsPrompt {
        data object Migrate : KhAssetsPrompt()
        data object Fresh : KhAssetsPrompt()
    }

    private val viewModel: RomListViewModel by viewModels()
    private val updatesViewModel: UpdatesViewModel by viewModels()

    @Inject lateinit var khAssetsFolderManager: KhAssetsFolderManager

    private var khAssetsPrompt by mutableStateOf<KhAssetsPrompt?>(null)
    private var isMigratingKhAssets by mutableStateOf(false)
    private var migrateKhAssetsAfterGrant = false

    // The all-files-access settings screen reports nothing back; re-check on return.
    private val allFilesAccessLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onStorageAccessRequestReturned()
    }

    private val legacyStoragePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        onStorageAccessRequestReturned()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)

        // [KHMM]
        if (savedInstanceState == null && !khAssetsFolderManager.isSetupPromptHandled()) {
            khAssetsPrompt = when {
                khAssetsFolderManager.needsMigration() -> KhAssetsPrompt.Migrate
                !khAssetsFolderManager.hasStorageAccess() -> KhAssetsPrompt.Fresh
                else -> null
            }
        }

        val emulatorLauncherValidatorDelegate = EmulatorLaunchValidatorDelegate(this, object : EmulatorLaunchValidatorDelegate.Callback {
            override fun onRomValidated(rom: Rom) {
                val intent = EmulatorActivity.getRomEmulatorActivityIntent(this@RomListActivity, rom)
                startActivity(intent)
            }

            override fun onFirmwareValidated(consoleType: ConsoleType) {
                val intent = EmulatorActivity.getFirmwareEmulatorActivityIntent(this@RomListActivity, consoleType)
                startActivity(intent)
            }

            override fun onValidationAborted() {
                // Do nothing
            }
        })

        setContent {
            val roms by viewModel.roms.collectAsStateWithLifecycle()
            val romScanningStatus by viewModel.romScanningStatus.collectAsStateWithLifecycle(initialValue = RomScanningStatus.NOT_SCANNING)
            val hasSearchDirectories by viewModel.hasSearchDirectories.collectAsStateWithLifecycle(initialValue = true)

            var currentUpdate by remember { mutableStateOf<AppUpdate?>(null) }
            var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }
            var showInvalidDirectoryDialog by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                updatesViewModel.appUpdate.collectLatest {
                    currentUpdate = it
                }
            }

            LaunchedEffect(Unit) {
                updatesViewModel.updateDownloadProgressEvent.collectLatest { progress ->
                    when (progress) {
                        is DownloadProgress.DownloadUpdate -> {
                            downloadProgress = progress
                        }
                        is DownloadProgress.DownloadComplete -> {
                            downloadProgress = null
                        }
                        is DownloadProgress.DownloadFailed -> {
                            downloadProgress = null
                            Toast.makeText(this@RomListActivity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            LaunchedEffect(Unit) {
                viewModel.invalidDirectoryAccessEvent.collectLatest {
                    showInvalidDirectoryDialog = true
                }
            }

            MelonTheme {
                RomListScreen(
                    roms = roms,
                    isRefreshing = romScanningStatus == RomScanningStatus.SCANNING,
                    hasSearchDirectories = hasSearchDirectories,
                    onSearchQueryChange = viewModel::setRomSearchQuery,
                    onSortChange = viewModel::setRomSorting,
                    onRomSelected = { rom ->
                        viewModel.setRomLastPlayedNow(rom)
                        emulatorLauncherValidatorDelegate.validateRom(rom)
                    },
                    onRomConfigClick = { rom ->
                        val intent = Intent(this@RomListActivity, RomDetailsActivity::class.java).apply {
                            putExtra(RomDetailsActivity.KEY_ROM, RomParcelable(rom))
                        }
                        startActivity(intent)
                    },
                    onRefresh = viewModel::refreshRoms,
                    onDirectorySelected = viewModel::addRomSearchDirectory,
                    onNavigateToSettings = {
                        val intent = Intent(this@RomListActivity, SettingsActivity::class.java)
                        startActivity(intent)
                    },
                    retrieveRomIcon = { rom ->
                        viewModel.getRomIcon(rom)
                    },
                )

                currentUpdate?.let { update ->
                    when (update.type) {
                        AppUpdate.Type.PRODUCTION -> {
                            ProdUpdateAvailableDialog(
                                update = update,
                                onUpdate = {
                                    currentUpdate = null
                                    downloadProgress = DownloadProgress.DownloadUpdate(0, 0)
                                    updatesViewModel.downloadUpdate(update)
                                },
                                onSkip = {
                                    updatesViewModel.skipUpdate(update)
                                    currentUpdate = null
                                },
                                onDismiss = {
                                    currentUpdate = null
                                },
                            )
                        }
                        AppUpdate.Type.NIGHTLY -> {
                            NightlyUpdateDialog(
                                onUpdate = {
                                    currentUpdate = null
                                    downloadProgress = DownloadProgress.DownloadUpdate(0, 0)
                                    updatesViewModel.downloadUpdate(update)
                                },
                                onDismiss = {
                                    updatesViewModel.skipUpdate(update)
                                    currentUpdate = null
                                },
                            )
                        }
                    }
                }

                (downloadProgress as? DownloadProgress.DownloadUpdate)?.let { progress ->
                    DownloadProgressDialog(
                        downloadProgress = progress,
                        onMoveToBackground = {
                            downloadProgress = null
                        },
                    )
                }

                if (showInvalidDirectoryDialog) {
                    AlertDialog(
                        onDismissRequest = { showInvalidDirectoryDialog = false },
                        title = { Text(stringResource(R.string.error_invalid_directory)) },
                        text = { Text(stringResource(R.string.error_invalid_directory_description)) },
                        confirmButton = {
                            TextButton(
                                onClick = { showInvalidDirectoryDialog = false },
                                colors = melonTextButtonColors(),
                            ) {
                                Text(stringResource(R.string.ok).uppercase())
                            }
                        },
                    )
                }

                // [KHMM] asset-folder setup prompt (migration or fresh-install explainer)
                khAssetsPrompt?.let { prompt ->
                    val assetsRootPath = khAssetsFolderManager.assetsRoot().absolutePath
                    AlertDialog(
                        onDismissRequest = { dismissKhAssetsPrompt() },
                        title = { Text(stringResource(R.string.kh_assets_setup_title)) },
                        text = {
                            val message = when (prompt) {
                                is KhAssetsPrompt.Migrate -> stringResource(R.string.kh_assets_migration_prompt, assetsRootPath)
                                is KhAssetsPrompt.Fresh -> stringResource(R.string.kh_assets_fresh_prompt, assetsRootPath)
                            }
                            Text(message)
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    when (prompt) {
                                        is KhAssetsPrompt.Migrate -> {
                                            if (khAssetsFolderManager.hasStorageAccess()) {
                                                startKhAssetsMigration()
                                            } else {
                                                migrateKhAssetsAfterGrant = true
                                                requestStorageAccess()
                                            }
                                        }
                                        is KhAssetsPrompt.Fresh -> requestStorageAccess()
                                    }
                                },
                                colors = melonTextButtonColors(),
                            ) {
                                val label = when (prompt) {
                                    is KhAssetsPrompt.Migrate -> stringResource(R.string.kh_assets_move_now)
                                    is KhAssetsPrompt.Fresh -> stringResource(R.string.kh_assets_grant)
                                }
                                Text(label.uppercase())
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { dismissKhAssetsPrompt() },
                                colors = melonTextButtonColors(),
                            ) {
                                Text(stringResource(R.string.kh_assets_later).uppercase())
                            }
                        },
                    )
                }

                if (isMigratingKhAssets) {
                    AlertDialog(
                        onDismissRequest = { },
                        text = { Text(stringResource(R.string.kh_assets_migrating)) },
                        confirmButton = { },
                    )
                }
            }
        }
    }

    // [KHMM]
    private fun dismissKhAssetsPrompt() {
        khAssetsFolderManager.setSetupPromptHandled()
        khAssetsPrompt = null
    }

    // [KHMM]
    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            try {
                allFilesAccessLauncher.launch(intent)
            } catch (e: Exception) {
                allFilesAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyStoragePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // [KHMM]
    private fun onStorageAccessRequestReturned() {
        if (khAssetsFolderManager.hasStorageAccess()) {
            if (migrateKhAssetsAfterGrant) {
                startKhAssetsMigration()
            } else {
                dismissKhAssetsPrompt()
            }
        }
        migrateKhAssetsAfterGrant = false
    }

    // [KHMM]
    private fun startKhAssetsMigration() {
        khAssetsPrompt = null
        isMigratingKhAssets = true
        lifecycleScope.launch {
            val result = khAssetsFolderManager.migrateLegacyAssets()
            isMigratingKhAssets = false
            khAssetsFolderManager.setSetupPromptHandled()
            val message = when (result) {
                is KhAssetsFolderManager.MigrationResult.Failed ->
                    getString(R.string.kh_assets_migration_failed, result.reason.orEmpty())
                is KhAssetsFolderManager.MigrationResult.NotEnoughSpace ->
                    getString(R.string.kh_assets_migration_no_space, Formatter.formatFileSize(this@RomListActivity, result.requiredBytes))
                else ->
                    getString(R.string.kh_assets_migration_done, khAssetsFolderManager.assetsRoot().absolutePath)
            }
            Toast.makeText(this@RomListActivity, message, Toast.LENGTH_LONG).show()
        }
    }
}