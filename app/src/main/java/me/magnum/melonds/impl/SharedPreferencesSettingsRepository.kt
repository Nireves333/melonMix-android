package me.magnum.melonds.impl

import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import me.magnum.melonds.common.uridelegates.UriHandler
import me.magnum.melonds.domain.model.AudioBitrate
import me.magnum.melonds.domain.model.AudioInterpolation
import me.magnum.melonds.domain.model.AudioLatency
import me.magnum.melonds.domain.model.ConsoleType
import me.magnum.melonds.domain.model.ControllerConfiguration
import me.magnum.melonds.domain.model.EmulatorConfiguration
import me.magnum.melonds.domain.model.FirmwareConfiguration
import me.magnum.melonds.domain.model.FpsCounterPosition
import me.magnum.melonds.domain.model.MacAddress
import me.magnum.melonds.domain.model.MicSource
import me.magnum.melonds.domain.model.RendererConfiguration
import me.magnum.melonds.domain.model.rewind.RewindWindowPosition
import me.magnum.melonds.domain.model.RomIconFiltering
import me.magnum.melonds.domain.model.SaveStateLocation
import me.magnum.melonds.domain.model.SizeUnit
import me.magnum.melonds.domain.model.SortingMode
import me.magnum.melonds.domain.model.SortingOrder
import me.magnum.melonds.domain.model.VideoFiltering
import me.magnum.melonds.domain.model.VideoRenderer
import me.magnum.melonds.domain.model.camera.DSiCameraSourceType
import me.magnum.melonds.domain.model.input.SoftInputBehaviour
import me.magnum.melonds.domain.model.layout.LayoutConfiguration
import me.magnum.melonds.domain.model.render.RenderStrategy
import me.magnum.melonds.domain.model.rom.Rom
import me.magnum.melonds.domain.repositories.SettingsRepository
import me.magnum.melonds.impl.dtos.input.ControllerConfigurationDto
import me.magnum.melonds.impl.input.ControllerConfigurationFactory
import me.magnum.melonds.ui.Theme
import me.magnum.melonds.utils.enumValueOfIgnoreCase
import java.io.File
import java.util.UUID

class SharedPreferencesSettingsRepository(
    private val context: Context,
    private val preferences: SharedPreferences,
    private val controllerConfigurationFactory: ControllerConfigurationFactory,
    private val json: Json,
    private val uriHandler: UriHandler,
    preferencesCoroutineScope: CoroutineScope,
) : SettingsRepository, OnSharedPreferenceChangeListener {

    companion object {
        private const val TAG = "SPSettingsRepository"
        private const val CONTROLLER_CONFIG_FILE = "controller_config.json"
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val controllerConfiguration by lazy {
        val initialConfiguration = try {
            val configFile = File(context.filesDir, CONTROLLER_CONFIG_FILE)
            configFile.inputStream().use {
                val loadedConfiguration = json.decodeFromStream<ControllerConfigurationDto>(it)
                loadedConfiguration.toControllerConfiguration()
            }
        } catch (_: Exception) {
            controllerConfigurationFactory.buildDefaultControllerConfiguration()
        }

        MutableStateFlow(initialConfiguration)
    }
    private val preferenceSharedFlows = mutableMapOf<String, MutableSharedFlow<Unit>>()
    private val renderConfigurationFlow: SharedFlow<RendererConfiguration>

    init {
        preferences.registerOnSharedPreferenceChangeListener(this)
        setDefaultThemeIfRequired()
        setDefaultMacAddressIfRequired()
        migrateKhCameraSpeedIfRequired()

        // [KHMM] 6 sources exceed the typed combine overloads, so nest two typed combines
        renderConfigurationFlow = combine(
            combine(getVideoRenderer(), getVideoFiltering(), isThreadedRenderingEnabled()) { renderer, filtering, threaded ->
                Triple(renderer, filtering, threaded)
            },
            combine(getRenderStrategy(), getVideoInternalResolutionScaling(), isEnhancedGraphicsEnabled()) { strategy, scaling, enhanced ->
                Triple(strategy, scaling, enhanced)
            },
        ) { (renderer, filtering, threaded), (strategy, scaling, enhanced) ->
            RendererConfiguration(renderer, filtering, threaded, strategy, scaling, enhanced)
        }.conflate().shareIn(preferencesCoroutineScope, SharingStarted.Lazily, replay = 1)
    }

    private fun setDefaultThemeIfRequired() {
        if (preferences.getString("theme", null) != null)
            return

        val defaultTheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "system" else "light"
        preferences.edit {
            putString("theme", defaultTheme)
        }
    }

    private fun setDefaultMacAddressIfRequired() {
        if (preferences.getString("internal_mac_address", null) != null) {
            return
        }

        val macAddress = MacAddress.randomDsAddress()
        preferences.edit {
            putString("internal_mac_address", macAddress.toString())
        }
    }

    override suspend fun getEmulatorConfiguration(): EmulatorConfiguration {
        val consoleType = getDefaultConsoleType()
        val useCustomBios = useCustomBios()
        val dsBiosDirUri = getDsBiosDirectory()
        val dsiBiosDirUri = getDsiBiosDirectory()

        // Ensure all BIOS dirs are set. DSi requires both dirs to be set
        if ((consoleType == ConsoleType.DS && useCustomBios && dsBiosDirUri == null) || (consoleType == ConsoleType.DSi && (dsBiosDirUri == null || dsiBiosDirUri == null)))
            throw IllegalStateException("BIOS directory not set")

        val dsDirDocument = dsBiosDirUri?.let {
            DocumentFile.fromTreeUri(context, it)
        }
        val dsiDirDocument = dsiBiosDirUri?.let {
            DocumentFile.fromTreeUri(context, it)
        }

        return EmulatorConfiguration(
            useCustomBios = useCustomBios(),
            showBootScreen = showBootScreen(),
            dsBios7Uri = dsDirDocument?.findFile("bios7.bin")?.uri,
            dsBios9Uri = dsDirDocument?.findFile("bios9.bin")?.uri,
            dsFirmwareUri = dsDirDocument?.findFile("firmware.bin")?.uri,
            dsiBios7Uri = dsiDirDocument?.findFile("bios7.bin")?.uri,
            dsiBios9Uri = dsiDirDocument?.findFile("bios9.bin")?.uri,
            dsiFirmwareUri = dsiDirDocument?.findFile("firmware.bin")?.uri,
            dsiNandUri = dsiDirDocument?.findFile("nand.bin")?.uri,
            internalDirectory = context.filesDir.absolutePath,
            fastForwardSpeedMultiplier = getFastForwardSpeedMultiplier(),
            rewindEnabled = isRewindEnabled(),
            rewindPeriodSeconds = getRewindPeriod(),
            rewindWindowSeconds = getRewindWindow(),
            useJit = isJitEnabled(),
            consoleType = consoleType,
            soundEnabled = isSoundEnabled(),
            audioInterpolation = getAudioInterpolation(),
            audioBitrate = getAudioBitrate(),
            volume = getVolume(),
            audioLatency = AudioLatency.LOW,
            micSource = getMicSource(),
            firmwareConfiguration = getFirmwareConfiguration(),
            rendererConfiguration = renderConfigurationFlow.first(),
        )
    }

    override fun getTheme(): Theme {
        val themePreference = preferences.getString("theme", "light")!!
        return Theme.valueOf(themePreference.uppercase())
    }

    override fun getFastForwardSpeedMultiplier(): Float {
        val speedMultiplierPreference = preferences.getString("fast_forward_speed_multiplier", "-1")!!
        return speedMultiplierPreference.toFloat()
    }

    override fun isRewindEnabled(): Boolean {
        return preferences.getBoolean("enable_rewind", false)
    }

    override fun getRewindWindowPosition(): RewindWindowPosition {
        val positionPreference = preferences.getString("rewind_window_position", "bottom")!!
        return RewindWindowPosition.valueOf(positionPreference.uppercase())
    }

    override fun isSustainedPerformanceModeEnabled(): Boolean {
        return preferences.getBoolean("enable_sustained_performance", false)
    }

    override fun getRomSearchDirectories(): Array<Uri> {
        val dirPreference = preferences.getStringSet("rom_search_dirs", emptySet())
        return dirPreference?.map { it.toUri() }?.toTypedArray() ?: emptyArray()
    }

    override fun clearRomSearchDirectories() {
        preferences.edit {
            putStringSet("rom_search_dirs", null)
        }
    }

    // [KHMM] Settings curation for the two-game app: the preferences below lost their UI, so
    // their getters are pinned to the values every verified session ran on instead of reading
    // stale stored state (a user who once changed the old pref must not keep a hidden override).
    override fun getRomIconFiltering(): RomIconFiltering {
        return RomIconFiltering.NONE
    }

    override fun getRomCacheMaxSize(): SizeUnit {
        // Pinned to the old default: cache step 3 = 128MB * 2^3 = 1GB
        return SizeUnit.MB(128) * 8
    }

    override fun getDefaultConsoleType(): ConsoleType {
        // [KHMM] DS only — the KH plugin's RAM-address logic has never been validated on DSi
        return ConsoleType.DS
    }

    override fun getFirmwareConfiguration(): FirmwareConfiguration {
        val birthdayPreference = preferences.getString("firmware_settings_birthday", "01/01")!!
        val parts = birthdayPreference.split("/")
        val birthday = if (parts.size != 2) {
            Pair(1, 1)
        } else {
            val day = parts[0].toIntOrNull() ?: 1
            val month = parts[1].toIntOrNull() ?: 1
            Pair(day, month)
        }

        val useCustomBios = useCustomBios()
        var macAddress: String? = null
        val randomizeMacAddress = if (useCustomBios) {
            preferences.getBoolean("custom_randomize_mac_address", false)
        } else {
            var randomize = preferences.getBoolean("internal_randomize_mac_address", false)

            if (!randomize) {
                macAddress = preferences.getString("internal_mac_address", null)
                // If the MAC address is not defined, enable MAC address randomization
                if (macAddress == null) {
                    randomize = true
                }
            }
            randomize
        }

        return FirmwareConfiguration(
                preferences.getString("firmware_settings_nickname", "Player")!!,
                preferences.getString("firmware_settings_message", "Hello!")!!,
                preferences.getString("firmware_settings_language", "1")!!.toInt(),
                preferences.getInt("firmware_settings_colour", 0),
                birthday.second,
                birthday.first,
                randomizeMacAddress,
                macAddress
        )
    }

    override fun useCustomBios(): Boolean {
        // [KHMM] pinned: internal firmware only (custom BIOS UI removed)
        return false
    }

    override fun getDsBiosDirectory(): Uri? {
        val dirPreference = preferences.getStringSet("bios_dir", null)?.firstOrNull()
        return dirPreference?.toUri()
    }

    override fun getDsiBiosDirectory(): Uri? {
        val dirPreference = preferences.getStringSet("dsi_bios_dir", null)?.firstOrNull()
        return dirPreference?.toUri()
    }

    override fun showBootScreen(): Boolean {
        // [KHMM] pinned with the custom-BIOS removal (the boot screen needed a custom BIOS)
        return false
    }

    override fun isJitEnabled(): Boolean {
        // [KHMM] pinned on wherever the device supports it — every KH perf number assumes JIT;
        // turning it off was a pure footgun. The capability check stays.
        return Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
    }

    override fun getVideoRenderer(): Flow<VideoRenderer> {
        // [KHMM] pinned: the KH composite is OpenGL-only by design; the software and compute
        // renderers silently disabled the whole enhanced experience. The flow shape is kept so
        // observers behave exactly as before.
        return getOrCreatePreferenceSharedFlow("video_renderer") {
            VideoRenderer.OPENGL
        }
    }

    override fun getVideoInternalResolutionScaling(): Flow<Int> {
        return getOrCreatePreferenceSharedFlow("video_internal_resolution") {
            val internalResolutionPreference = preferences.getString("video_internal_resolution", "1")!!
            internalResolutionPreference.toIntOrNull() ?: 1
        }
    }

    override fun getVideoFiltering(): Flow<VideoFiltering> {
        return getOrCreatePreferenceSharedFlow("video_filtering") {
            val filteringPreference = preferences.getString("video_filtering", "none")!!
            VideoFiltering.valueOf(filteringPreference.uppercase())
        }
    }

    override fun isThreadedRenderingEnabled(): Flow<Boolean> {
        // [KHMM] pinned to the old default; only the software renderer read it, which is gone
        return getOrCreatePreferenceSharedFlow("enable_threaded_rendering") {
            true
        }
    }

    // [KHMM] master toggle for the KH Melon Mix single-screen enhanced-graphics path
    override fun isEnhancedGraphicsEnabled(): Flow<Boolean> {
        return getOrCreatePreferenceSharedFlow("enable_enhanced_graphics") {
            preferences.getBoolean("enable_enhanced_graphics", true)
        }
    }

    // [KHMM] remastered-BGM audio pack subfolders (under assets/<game>/audio/); "" = none
    override fun getKhBgmAudioPackDays(): String {
        return preferences.getString("kh_bgm_pack_days", "")!!
    }

    override fun getKhBgmAudioPackRecoded(): String {
        return preferences.getString("kh_bgm_pack_recoded", "")!!
    }

    // [KHMM] remastered-BGM volume (0-100; desktop Audio.BGMVolume)
    override fun getKhBgmVolume(): Flow<Int> {
        return getOrCreatePreferenceSharedFlow("kh_bgm_volume") {
            preferences.getInt("kh_bgm_volume", 100)
        }
    }

    // [KHMM] one-time carry-over of a stored camera speed from the old 1.0-4.0 scale onto
    // the 1.0-6.0 scale (same actual speed = +4 half-units). Done lazily here because the
    // versioned migration framework never runs in this fork (last_version defaults above
    // our restarted versionCodes).
    private fun migrateKhCameraSpeedIfRequired() {
        if (!preferences.contains("kh_camera_speed_2")) {
            preferences.getString("kh_camera_speed", null)?.toIntOrNull()?.let {
                preferences.edit {
                    putString("kh_camera_speed_2", (it + 4).coerceIn(2, 12).toString())
                }
            }
        }
    }

    // [KHMM] camera-stick speed in half-units (2-12 = 1.0-6.0 in 0.5 steps; speeds from 3.0
    // up are the desktop 1-4 shift scale, the rest are synthesized natively by scaling the
    // stick range). ListPreference stores strings; default "8" = speed 4.0 = the desktop
    // default's old speed
    override fun getKhCameraSensitivity(): Flow<Int> {
        return getOrCreatePreferenceSharedFlow("kh_camera_speed_2") {
            preferences.getString("kh_camera_speed_2", "8")!!.toInt()
        }
    }

    // [KHMM] touch-stick deadzone in percent of the stick radius (applies to the movement
    // and camera sticks; ListPreference stores strings)
    override fun getKhStickDeadzone(): Flow<Int> {
        return getOrCreatePreferenceSharedFlow("kh_stick_deadzone") {
            preferences.getString("kh_stick_deadzone", "10")!!.toInt()
        }
    }

    // [KHMM] subtitles over HD replacement cutscenes; served to the plugin as DisableSubtitles
    override fun getKhShowSubtitles(): Flow<Boolean> {
        return getOrCreatePreferenceSharedFlow("kh_show_subtitles") {
            preferences.getBoolean("kh_show_subtitles", true)
        }
    }

    // [KHMM] single-screen mode; served to the plugin as DisableSingleScreenMode. Off keeps
    // bottom-screen content on the native bottom screen (dual-screen devices, e.g. AYN Thor)
    override fun getKhSingleScreenMode(): Flow<Boolean> {
        return getOrCreatePreferenceSharedFlow("kh_single_screen_mode") {
            preferences.getBoolean("kh_single_screen_mode", true)
        }
    }

    override fun getRenderStrategy(): Flow<RenderStrategy> {
        return getOrCreatePreferenceSharedFlow("front_rendering") {
            if (preferences.getBoolean("front_rendering", false)) {
                RenderStrategy.FRONT_BUFFER_RENDERING
            } else {
                RenderStrategy.BACK_BUFFER_RENDERING
            }
        }
    }

    override fun getFpsCounterPosition(): FpsCounterPosition {
        val fpsCounterPreference = preferences.getString("fps_counter_position", "hidden")!!
        return FpsCounterPosition.valueOf(fpsCounterPreference.uppercase())
    }

    override fun getDSiCameraSource(): DSiCameraSourceType {
        // [KHMM] pinned: DSi mode is gone and neither KH game uses the camera
        return DSiCameraSourceType.BLACK_SCREEN
    }

    override fun getDSiCameraStaticImage(): Uri? {
        return null
    }

    override fun isSoundEnabled(): Boolean {
        return preferences.getBoolean("sound_enabled", true)
    }

    private fun getRewindPeriod(): Int {
        return preferences.getInt("rewind_period", 10)
    }

    private fun getRewindWindow(): Int {
        return preferences.getInt("rewind_window", 6) * 10
    }

    private fun getVolume(): Int {
        return preferences.getInt("volume", 256).coerceIn(0, 256)
    }

    private fun getAudioInterpolation(): AudioInterpolation {
        val interpolationPreference = preferences.getString("audio_interpolation", "none")!!
        return enumValueOfIgnoreCase(interpolationPreference)
    }

    private fun getAudioBitrate(): AudioBitrate {
        val bitratePreference = preferences.getString("audio_bitrate", "auto")!!
        return enumValueOfIgnoreCase(bitratePreference)
    }

    override fun getAudioLatency(): AudioLatency {
        val audioLatencyPreference = preferences.getString("audio_latency", "medium")!!
        return enumValueOfIgnoreCase(audioLatencyPreference)
    }

    override fun getMicSource(): MicSource {
        // [KHMM] pinned: neither KH game uses the microphone
        return MicSource.NONE
    }

    override fun getRomSortingMode(): SortingMode {
        val sortingMode = preferences.getString("rom_sorting_mode", "alphabetically")!!
        return SortingMode.valueOf(sortingMode.uppercase())
    }

    override fun getRomSortingOrder(): SortingOrder {
        val sortingOrder = preferences.getString("rom_sorting_order", null)
        return if (sortingOrder == null)
            getRomSortingMode().defaultOrder
        else
            SortingOrder.valueOf(sortingOrder.uppercase())
    }

    override fun saveNextToRomFile(): Boolean {
        return preferences.getBoolean("use_rom_dir", true)
    }

    override fun getSaveFileDirectory(): Uri? {
        val dirPreference = preferences.getStringSet("sram_dir", null)?.firstOrNull()
        return dirPreference?.toUri()
    }

    override fun getSaveFileDirectory(rom: Rom): Uri {
        return if (!saveNextToRomFile() && getSaveFileDirectory() != null) {
            getSaveFileDirectory()!!
        } else {
            if (rom.parentTreeUri != null) {
                getRomParentDirectory(rom)
            } else {
                // We don't know the ROM's directory, so we can't save next to it. Put save file in an app folder
                val externalFilesDir = context.getExternalFilesDir(null)
                val saveFileDirectory = File(externalFilesDir, "saves")
                if (!saveFileDirectory.isDirectory && !saveFileDirectory.mkdirs()) {
                    throw Exception("Could not create internal save directory")
                }

                Uri.fromFile(saveFileDirectory)
            }
        }
    }

    override fun getSaveStateLocation(rom: Rom): SaveStateLocation {
        val locationPreference = preferences.getString("save_state_location", "save_dir")!!
        return SaveStateLocation.valueOf(locationPreference.uppercase())
    }

    override fun getSaveStateDirectory(rom: Rom): Uri? {
        val saveStateLocation = getSaveStateLocation(rom)

        return when (saveStateLocation) {
            SaveStateLocation.SAVE_DIR -> getSaveFileDirectory(rom)
            SaveStateLocation.ROM_DIR -> getRomParentDirectory(rom)
            SaveStateLocation.INTERNAL_DIR -> {
                val saveStateDir = File(context.getExternalFilesDir(null), "savestates")
                if (!saveStateDir.isDirectory) {
                    saveStateDir.mkdirs()
                }
                DocumentFile.fromFile(saveStateDir).uri
            }
        }
    }

    private fun getRomParentDirectory(rom: Rom): Uri {
        return rom.parentTreeUri?.let {
            uriHandler.getUriTreeDocument(rom.parentTreeUri)?.uri
        } ?: throw Exception("Could not determine ROMs parent document")
    }

    override fun getControllerConfiguration(): ControllerConfiguration {
        return controllerConfiguration.value
    }

    override fun observeControllerConfiguration(): StateFlow<ControllerConfiguration> {
        return controllerConfiguration
    }

    override fun getSelectedLayoutId(): UUID {
        val id = preferences.getString("input_layout_id", null)
        return id?.let { UUID.fromString(it) } ?: LayoutConfiguration.DEFAULT_ID
    }

    override fun getSoftInputBehaviour(): Flow<SoftInputBehaviour> {
        return getOrCreatePreferenceSharedFlow("soft_input_behaviour") {
            val preference = preferences.getString("soft_input_behaviour", "hide_system_buttons_when_controller_connected")

            when (preference) {
                "always_visible" -> SoftInputBehaviour.ALWAYS_VISIBLE
                "hide_system_buttons_when_controller_connected" -> SoftInputBehaviour.HIDE_SYSTEM_BUTTONS_WHEN_CONTROLLERS_CONNECTED
                "hide_mapped_buttons_when_controller_connected" -> SoftInputBehaviour.HIDE_ALL_BUTTONS_ASSIGNED_TO_CONNECTED_CONTROLLERS
                "always_invisible" -> SoftInputBehaviour.ALWAYS_INVISIBLE
                else -> SoftInputBehaviour.HIDE_SYSTEM_BUTTONS_WHEN_CONTROLLERS_CONNECTED
            }
        }
    }

    override fun isTouchHapticFeedbackEnabled(): Flow<Boolean> {
        return getOrCreatePreferenceSharedFlow("input_touch_haptic_feedback_enabled") {
            preferences.getBoolean("input_touch_haptic_feedback_enabled", true)
        }
    }

    override fun getTouchHapticFeedbackStrength(): Int {
        val strength = preferences.getInt("input_touch_haptic_feedback_strength", 30)
        return strength.coerceIn(1, 100)
    }

    override fun getSoftInputOpacity(): Flow<Int> {
        return getOrCreatePreferenceSharedFlow("input_opacity") {
            preferences.getInt("input_opacity", 50)
        }
    }

    override fun isRetroAchievementsRichPresenceEnabled(): Boolean {
        return preferences.getBoolean("ra_rich_presence", true)
    }

    override fun isRetroAchievementsHardcoreEnabled(): Boolean {
        return preferences.getBoolean("ra_hardcore_enabled", false)
    }

    override fun areRetroAchievementsActiveChallengeIndicatorsEnabled(): Boolean {
        return preferences.getBoolean("ra_active_challenge_indicators", true)
    }

    override fun areRetroAchievementsProgressIndicatorsEnabled(): Boolean {
        return preferences.getBoolean("ra_progress_indicators", true)
    }

    override fun areRetroAchievementsLeaderboardIndicatorsEnabled(): Boolean {
        return preferences.getBoolean("ra_leaderboard_indicators", true)
    }

    override fun areCheatsEnabled(): Boolean {
        return preferences.getBoolean("cheats_enabled", false)
    }

    override fun observeRomSearchDirectories(): Flow<Array<Uri>> {
        return getOrCreatePreferenceSharedFlow("rom_search_dirs") {
            getRomSearchDirectories()
        }
    }

    override fun observeSelectedLayoutId(): Flow<UUID> {
        return getOrCreatePreferenceSharedFlow("input_layout_id") {
            getSelectedLayoutId()
        }
    }

    override fun observeDSiCameraSource(): Flow<DSiCameraSourceType> {
        return getOrCreatePreferenceSharedFlow("dsi_camera_source") {
            getDSiCameraSource()
        }
    }

    override fun observeDSiCameraStaticImage(): Flow<Uri?> {
        return getOrCreatePreferenceSharedFlow("dsi_camera_static_image") {
            getDSiCameraStaticImage()
        }
    }

    override fun setDsBiosDirectory(directoryUri: Uri) {
        preferences.edit {
            putStringSet("bios_dir", setOf(directoryUri.toString()))
        }
    }

    override fun setDsiBiosDirectory(directoryUri: Uri) {
        preferences.edit {
            putStringSet("dsi_bios_dir", setOf(directoryUri.toString()))
        }
    }

    override fun addRomSearchDirectory(directoryUri: Uri) {
        preferences.edit {
            putStringSet("rom_search_dirs", setOf(directoryUri.toString()))
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun setControllerConfiguration(controllerConfiguration: ControllerConfiguration) {
        this.controllerConfiguration.value = controllerConfiguration

        try {
            val configFile = File(context.filesDir, CONTROLLER_CONFIG_FILE)
            val dto = ControllerConfigurationDto.fromControllerConfiguration(controllerConfiguration)
            configFile.outputStream().use {
                json.encodeToStream(dto, it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save controller configuration", e)
        }
    }

    override fun setRomSortingMode(sortingMode: SortingMode) {
        preferences.edit {
            putString("rom_sorting_mode", sortingMode.toString().lowercase())
        }
    }

    override fun setRomSortingOrder(sortingOrder: SortingOrder) {
        preferences.edit {
            putString("rom_sorting_order", sortingOrder.toString().lowercase())
        }
    }

    override fun setSelectedLayoutId(layoutId: UUID) {
        preferences.edit {
            putString("input_layout_id", layoutId.toString())
        }
    }

    override fun observeTheme(): Flow<Theme> {
        return getOrCreatePreferenceSharedFlow("theme") {
            getTheme()
        }
    }

    override fun observeRomIconFiltering(): Flow<RomIconFiltering> {
        return getOrCreatePreferenceSharedFlow("rom_icon_filtering") {
            getRomIconFiltering()
        }
    }

    private fun <T> getOrCreatePreferenceSharedFlow(preference: String, mapper: () -> T): Flow<T> {
        val preferenceFlow = preferenceSharedFlows.getOrPut(preference) {
            MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST).apply {
                // Immediately trigger an event to load the initial value
                tryEmit(Unit)
            }
        }

        return preferenceFlow.map { mapper() }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        preferenceSharedFlows[key]?.tryEmit(Unit)
    }

    override fun observeRenderConfiguration(): Flow<RendererConfiguration> {
        return renderConfigurationFlow
    }
}