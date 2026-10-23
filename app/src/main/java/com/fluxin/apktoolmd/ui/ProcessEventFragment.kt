package com.fluxin.apktoolmd.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.fluxin.apktoolmd.R
import com.google.android.material.button.MaterialButton
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process Event page: import a dump output file (script.json, Offsets.hpp,
 * Objects.txt, Logs.txt) or a raw .so, and find ProcessEvent references.
 * The official source is the dumper's script.json (Name: "UObject$$ProcessInternal"
 * or similar, Address: RVA from libUE4.so base).
 */
class ProcessEventFragment : Fragment() {

    private val requestImport = 4242

    private lateinit var importButton: MaterialButton
    private lateinit var findButton: MaterialButton
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var progress: ProgressBar

    private var selectedUri: Uri? = null
    private var selectedName: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_process_event, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        importButton = view.findViewById(R.id.pe_import)
        findButton = view.findViewById(R.id.pe_find)
        statusView = view.findViewById(R.id.pe_status)
        resultView = view.findViewById(R.id.pe_result)
        logView = view.findViewById(R.id.pe_log)
        scroll = view.findViewById(R.id.pe_scroll)
        progress = view.findViewById(R.id.pe_progress)

        importButton.setOnClickListener { pickFile() }
        findButton.setOnClickListener { find() }
        findButton.isEnabled = false
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, requestImport)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestImport || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        selectedUri = uri
        selectedName = uri.lastPathSegment ?: "selected"
        statusView.text = "Imported: $selectedName"
        logView.text = ""
        log("imported $selectedName")
        findButton.isEnabled = true
    }

    private fun find() {
        val uri = selectedUri ?: return
        val root = view ?: return
        statusView.text = getString(R.string.pe_scanning)
        progress.visibility = View.VISIBLE
        findButton.isEnabled = false
        logView.text = ""
        resultView.text = ""
        log("== Find ProcessEvent ==")
        log("file: $selectedName")

        val appContext = requireContext().applicationContext
        Thread {
            val bytes = try {
                appContext.contentResolver.openInputStream(uri)?.use { ins ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(65536)
                    var r = ins.read(buf)
                    while (r > 0) {
                        out.write(buf, 0, r)
                        r = ins.read(buf)
                    }
                    out.toByteArray()
                } ?: ByteArray(0)
            } catch (e: Throwable) {
                log("ERROR reading: $e")
                ByteArray(0)
            }

            log("size: ${bytes.size} bytes")

            val sb = StringBuilder()

            // 1) If JSON (script.json): parse for ProcessEvent/ProcessInternal
            if (selectedName.toLowerCase().endsWith(".json")) {
                parseScriptJson(bytes, sb)
            }

            // 2) If text (.hpp/.txt): regex scan lines
            if (selectedName.toLowerCase().endsWith(".hpp")
                || selectedName.toLowerCase().endsWith(".txt")
                || selectedName.toLowerCase().endsWith(".json")) {
                scanTextLines(bytes, sb)
            }

            // 3) Binary scan: ASCII "ProcessEvent" + mangled symbol
            scanBinary(bytes, sb)

            if (sb.isEmpty()) sb.append(getString(R.string.pe_none))

            root.post {
                resultView.text = sb.toString()
                statusView.text = if (sb.toString() == getString(R.string.pe_none))
                    getString(R.string.pe_none) else "Done."
                progress.visibility = View.GONE
                findButton.isEnabled = true
            }
        }.start()
    }

    /** Parse script.json: {"Functions":[{"Name":"Class$$Function","Address":RVA},...]} */
    private fun parseScriptJson(bytes: ByteArray, sb: StringBuilder) {
        try {
            val text = String(bytes, Charsets.UTF_8)
            val root = JSONObject(text)
            if (!root.has("Functions")) return
            val arr = root.getJSONArray("Functions")
            var count = 0
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.optString("Name", "")
                val addr = obj.optLong("Address", 0L)
                if (name.contains("ProcessEvent", true) || name.contains("ProcessInternal", true)) {
                    sb.append("script.json: Name=\"$name\"  Address=0x${addr.toString(16)} (RVA)\n")
                    count++
                }
            }
            if (count > 0) log("script.json: found $count ProcessEvent/ProcessInternal entries")
        } catch (e: Throwable) {
            log("JSON parse failed: $e")
        }
    }

    /** Scan text lines for ProcessEvent references (Offsets.hpp, Logs.txt, etc.) */
    private fun scanTextLines(bytes: ByteArray, sb: StringBuilder) {
        try {
            val text = String(bytes, Charsets.UTF_8)
            var found = 0
            for (line in text.lineSequence()) {
                if (line.contains("ProcessEvent", true)) {
                    sb.append("Line: ${line.trim()}\n")
                    found++
                    if (found >= 40) break
                }
            }
            if (found > 0) log("text lines with ProcessEvent: $found")
        } catch (e: Throwable) {
            log("text scan failed: $e")
        }
    }

    /** Binary scan: ASCII "ProcessEvent" and mangled symbol "_ZN7UObject12ProcessEventEP9UFunctionPv" */
    private fun scanBinary(bytes: ByteArray, sb: StringBuilder) {
        val needle = "ProcessEvent".toByteArray(Charsets.US_ASCII)
        val hits = ArrayList<Int>()
        var i = 0
        while (i <= bytes.size - needle.size) {
            var match = true
            for (j in needle.indices) if (bytes[i + j] != needle[j]) { match = false; break }
            if (match) hits.add(i)
            i++
        }

        val sym = "_ZN7UObject12ProcessEventEP9UFunctionPv"
        val symBytes = sym.toByteArray(Charsets.US_ASCII)
        val symHits = ArrayList<Int>()
        i = 0
        while (i <= bytes.size - symBytes.size) {
            var match = true
            for (j in symBytes.indices) if (bytes[i + j] != symBytes[j]) { match = false; break }
            if (match) symHits.add(i)
            i++
        }

        if (symHits.isNotEmpty()) {
            sb.append("Mangled symbol '$sym' found at:\n")
            for (h in symHits.take(20)) sb.append("  file offset 0x${h.toString(16)}\n")
        }
        if (hits.isNotEmpty()) {
            sb.append("ASCII 'ProcessEvent' occurrences: ${hits.size}\n")
            for (h in hits.take(20)) {
                val start = maxOf(0, h - 16)
                val end = minOf(bytes.size, h + 48)
                val snippet = String(bytes, start, end - start, Charsets.ISO_8859_1)
                    .replace(Regex("[^\\x20-\\x7E]"), ".")
                sb.append("  0x${h.toString(16)}: $snippet\n")
            }
        }
        log("binary scan: ascii hits=${hits.size}, mangled hits=${symHits.size}")
    }

    private fun log(line: String) {
        val root = view ?: return
        root.post {
            logView.append(line + "\n")
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
}