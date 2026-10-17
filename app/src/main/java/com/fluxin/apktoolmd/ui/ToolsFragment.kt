package com.fluxin.apktoolmd.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.fluxin.apktoolmd.R
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Tools tab: a playground of interactive Material components.
 */
class ToolsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_tools, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val switch = view.findViewById<SwitchMaterial>(R.id.demo_switch)
        val switchStatus = view.findViewById<TextView>(R.id.switch_status)
        switch.setOnCheckedChangeListener { _, isChecked ->
            switchStatus.text =
                getString(if (isChecked) R.string.switch_on else R.string.switch_off)
            showSnack(view, "Demo mode ${if (isChecked) "enabled" else "disabled"}")
        }

        val slider = view.findViewById<Slider>(R.id.demo_slider)
        val sliderValue = view.findViewById<TextView>(R.id.slider_value)
        slider.addOnChangeListener { _, value, _ ->
            sliderValue.text = value.toInt().toString()
        }

        val chips = view.findViewById<ChipGroup>(R.id.chip_group)
        chips.setOnCheckedChangeListener { group, _ ->
            val selected = ArrayList<String>()
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child is Chip && child.isChecked) {
                    selected.add(child.text.toString())
                }
            }
            showSnack(
                view,
                if (selected.isEmpty()) "No tags selected"
                else "Tags: ${selected.joinToString(", ")}"
            )
        }

        view.findViewById<View>(R.id.action_run_demo)?.setOnClickListener {
            showSnack(view, getString(R.string.demo_only))
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
