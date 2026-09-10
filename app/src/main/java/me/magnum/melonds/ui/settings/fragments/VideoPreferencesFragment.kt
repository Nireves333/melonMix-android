package me.magnum.melonds.ui.settings.fragments

import android.os.Bundle
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import me.magnum.melonds.R
import me.magnum.melonds.ui.settings.PreferenceFragmentTitleProvider
import java.io.File

// [KHMM] This app is OpenGL-only (the KH composite requires it), so the renderer picker and
// its per-renderer preference visibility machinery are gone, along with the software-only
// threaded-rendering toggle and the DSi camera options (no DSi mode; the KH games never use
// the camera).
@AndroidEntryPoint
class VideoPreferencesFragment : BasePreferenceFragment(), PreferenceFragmentTitleProvider {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_video, rootKey)

        // [KHMM] The HD cutscene pack has no picker (files either exist or they don't), but
        // like the music packs it should tell the user where it lives and what was found.
        val cutsceneStatus = findPreference<Preference>("kh_cutscene_pack_status")!!
        val cinematicsDir = requireContext().getExternalFilesDir(null)?.let { File(it, "assets/days/cutscenes/cinematics") }
        val videoCount = cinematicsDir?.listFiles { file -> file.isFile && file.extension.equals("mp4", ignoreCase = true) }?.size ?: 0
        cutsceneStatus.summary = if (videoCount > 0) {
            resources.getQuantityString(R.plurals.kh_cutscene_pack_summary_found, videoCount, videoCount)
        } else {
            getString(R.string.kh_cutscene_pack_summary_missing, cinematicsDir?.absolutePath.orEmpty())
        }
    }

    override fun getTitle(): String {
        return getString(R.string.category_video)
    }
}
