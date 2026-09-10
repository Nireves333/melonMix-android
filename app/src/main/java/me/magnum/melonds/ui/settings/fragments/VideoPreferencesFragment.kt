package me.magnum.melonds.ui.settings.fragments

import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import me.magnum.melonds.R
import me.magnum.melonds.ui.settings.PreferenceFragmentTitleProvider

// [KHMM] This app is OpenGL-only (the KH composite requires it), so the renderer picker and
// its per-renderer preference visibility machinery are gone, along with the software-only
// threaded-rendering toggle and the DSi camera options (no DSi mode; the KH games never use
// the camera). What remains needs no fragment logic beyond inflating the XML.
@AndroidEntryPoint
class VideoPreferencesFragment : BasePreferenceFragment(), PreferenceFragmentTitleProvider {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_video, rootKey)
    }

    override fun getTitle(): String {
        return getString(R.string.category_video)
    }
}
