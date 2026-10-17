package com.fluxin.apktoolmd.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.fluxin.apktoolmd.R
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Settings tab: demo preference toggles and an about card.
 */
class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listOf(R.id.pref_dark, R.id.pref_notifications, R.id.pref_beta).forEach { id ->
            val row = view.findViewById<SwitchMaterial>(id)
            row.setOnCheckedChangeListener { button, isChecked ->
                val label = button.text
                showSnack(view, "$label ${if (isChecked) "on" else "off"}")
            }
        }

        view.findViewById<View>(R.id.action_about)?.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.app_name)
                .setMessage(R.string.about_desc)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun showSnack(view: View, message: String) {
        val snackbar = Snackbar.make(view, message, Snackbar.LENGTH_SHORT)
        requireActivity().findViewById<View>(R.id.bottom_nav)?.let {
            snackbar.setAnchorView(it)
        }
        snackbar.show()
    }
}
