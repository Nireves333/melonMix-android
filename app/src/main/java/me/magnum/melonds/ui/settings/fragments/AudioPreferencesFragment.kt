package me.magnum.melonds.ui.settings.fragments

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SeekBarPreference
import me.magnum.melonds.R
import me.magnum.melonds.ui.settings.PreferenceFragmentTitleProvider
import java.io.File

// [KHMM] Settings curation: the microphone-source preference (and its permission flow) is
// gone — neither KH game uses the microphone, so the source is pinned to NONE internally.
class AudioPreferencesFragment : BasePreferenceFragment(), PreferenceFragmentTitleProvider {

    override fun getTitle() = getString(R.string.category_audio)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_audio, rootKey)
        val volumePreference = findPreference<SeekBarPreference>("volume")!!

        // [KHMM] remastered-BGM pack pickers: entries are whatever pack subfolders exist in
        // the on-device assets tree (assets/<game>/audio/<pack>/bgmN.wav|flac). The choice
        // is read by the plugin at ROM load, so a change applies on the next launch.
        setupKhBgmPackPreference(findPreference("kh_bgm_pack_days")!!, "days")
        setupKhBgmPackPreference(findPreference("kh_bgm_pack_recoded")!!, "recoded")

        updateVolumePreferenceSummary(volumePreference, volumePreference.value)

        volumePreference.setOnPreferenceChangeListener { _, newValue ->
            updateVolumePreferenceSummary(volumePreference, newValue as Int)
            true
        }
    }

    // [KHMM]
    private fun setupKhBgmPackPreference(preference: ListPreference, gameFolder: String) {
        val audioDir = requireContext().getExternalFilesDir(null)?.let { File(it, "assets/$gameFolder/audio") }
        val packs = audioDir?.listFiles { file -> file.isDirectory }?.map { it.name }?.sorted().orEmpty()

        preference.entries = (listOf(getString(R.string.kh_bgm_pack_none)) + packs).toTypedArray()
        preference.entryValues = (listOf("") + packs).toTypedArray()
        if (preference.value !in preference.entryValues) {
            preference.value = ""
        }
        if (packs.isEmpty() && audioDir != null) {
            preference.summary = getString(R.string.kh_bgm_pack_summary_missing, audioDir.absolutePath)
        } else {
            // The pack is read by the plugin when the ROM loads, so a change only takes
            // effect on the next launch — say so.
            preference.summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                getString(R.string.kh_bgm_pack_summary, pref.entry ?: getString(R.string.kh_bgm_pack_none))
            }
        }
    }

    private fun updateVolumePreferenceSummary(volumePreference: SeekBarPreference, volume: Int) {
        val volumePercentage = (volume / volumePreference.max.toFloat() * 100f).toInt()
        volumePreference.summary = getString(R.string.volume_percentage, volumePercentage)
    }
}
