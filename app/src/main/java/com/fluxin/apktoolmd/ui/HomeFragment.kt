package com.fluxin.apktoolmd.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.fluxin.apktoolmd.R
import com.google.android.material.snackbar.Snackbar

/**
 * Home tab: welcome header, a few stat cards and demo quick actions.
 */
class HomeFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val message = getString(R.string.demo_only)
        listOf(R.id.action_scan, R.id.action_extract, R.id.action_sign).forEach { id ->
            view.findViewById<View>(id)?.setOnClickListener {
                showSnack(view, message)
            }
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
