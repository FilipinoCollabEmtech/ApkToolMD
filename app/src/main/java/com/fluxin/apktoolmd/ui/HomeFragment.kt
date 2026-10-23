package com.fluxin.apktoolmd.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.widget.EditText
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.core.content.FileProvider
import androidx.core.widget.NestedScrollView
import com.android.apksig.ApkSigner
import com.fluxin.apktoolmd.R
import com.fluxin.apktoolmd.mod.RemapRule
import com.fluxin.apktoolmd.mod.ShadowLoader
import com.fluxin.apktoolmd.util.SurgicalZip
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.CRC32
import java.util.zip.ZipOutputStream

/**
 * Home tab: import an APK/APKS, inspect it, analyze its engine/compiler,
 * and dump UE4 assets out of it.
 */
class HomeFragment : Fragment() {

    private val requestImport = 1001
    private val kMaxEngineDumpFiles = 2000

    private lateinit var detailsCard: MaterialCardView
    private lateinit var kindView: TextView
    private lateinit var summaryView: TextView
    private lateinit var listView: LinearLayout
    private lateinit var analyzeButton: MaterialButton
    private lateinit var dumpButton: MaterialButton
    private lateinit var showDumpsButton: MaterialButton
    private lateinit var injectDumperButton: MaterialButton
    private lateinit var extractDumperButton: MaterialButton
    private lateinit var checkStatusButton: MaterialButton
    private lateinit var testDumperButton: MaterialButton
    private lateinit var patchModeSpinner: Spinner
    private lateinit var patchApkButton: MaterialButton
    private lateinit var installPatchedButton: MaterialButton
    private lateinit var injectAimbotButton: MaterialButton
    private lateinit var analyzeResult: TextView
    private lateinit var analyzeEvidence: TextView
    private lateinit var statusView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var consoleScroll: ScrollView
    private lateinit var consoleLog: TextView
    private lateinit var engineLabel: TextView
    private lateinit var engineChange: TextView
    private lateinit var scroll: NestedScrollView

    private var selectedUris: List<Uri> = emptyList()
    private var signedApkFile: File? = null

    // ------------------------------------------------------------ shadow loader
    private lateinit var shadowPick: MaterialButton
    private lateinit var shadowRules: EditText
    private lateinit var shadowLoad: MaterialButton
    private lateinit var shadowTest: MaterialButton
    private lateinit var shadowClass: EditText
    private lateinit var shadowStatus: TextView

    private var shadowSelectedUri: Uri? = null
    private var shadowLoader: ShadowLoader? = null

    private val requestImportShadow = 1002

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        detailsCard = view.findViewById(R.id.import_details_card)
        kindView = view.findViewById(R.id.import_kind)
        summaryView = view.findViewById(R.id.import_summary)
        listView = view.findViewById(R.id.import_list)
        analyzeButton = view.findViewById(R.id.analyze_apk)
        dumpButton = view.findViewById(R.id.dump_ue4)
        showDumpsButton = view.findViewById(R.id.show_dumps)
        injectDumperButton = view.findViewById(R.id.inject_dumper)
        extractDumperButton = view.findViewById(R.id.extract_dumper)
        checkStatusButton = view.findViewById(R.id.check_dumper_status)
        testDumperButton = view.findViewById(R.id.test_dumper)
        patchModeSpinner = view.findViewById(R.id.patch_mode)
        patchApkButton = view.findViewById(R.id.patch_apk)
        installPatchedButton = view.findViewById(R.id.install_patched)
        injectAimbotButton = view.findViewById(R.id.inject_aimbot)
        analyzeResult = view.findViewById(R.id.analyze_result)
        analyzeEvidence = view.findViewById(R.id.analyze_evidence)
        statusView = view.findViewById(R.id.status_text)
        progressBar = view.findViewById(R.id.progress_bar)
        consoleScroll = view.findViewById(R.id.console_scroll)
        consoleLog = view.findViewById(R.id.console_log)
        engineLabel = view.findViewById(R.id.engine_label)
        engineChange = view.findViewById(R.id.engine_change)
        scroll = view.findViewById(R.id.home_scroll)

        shadowPick = view.findViewById(R.id.shadow_pick)
        shadowRules = view.findViewById(R.id.shadow_rules)
        shadowLoad = view.findViewById(R.id.shadow_load)
        shadowTest = view.findViewById(R.id.shadow_test)
        shadowClass = view.findViewById(R.id.shadow_class)
        shadowStatus = view.findViewById(R.id.shadow_status)

        restoreShadowLoader()

        view.findViewById<View>(R.id.import_apk)?.setOnClickListener { pickApk() }
        analyzeButton.setOnClickListener { analyze() }
        dumpButton.setOnClickListener { dumpUe4() }
        showDumpsButton.setOnClickListener { showDumps() }
        injectDumperButton.setOnClickListener { injectDumper() }
        extractDumperButton.setOnClickListener { extractDumper() }
        checkStatusButton.setOnClickListener { checkDumperStatus() }
        testDumperButton.setOnClickListener { testDumper() }
        engineChange.setOnClickListener { openMenuPanel() }
        patchApkButton.setOnClickListener { patchApk() }
        installPatchedButton.setOnClickListener { installPatched() }
        injectAimbotButton.setOnClickListener { injectAimbot() }
        shadowPick.setOnClickListener { pickShadow() }
        shadowLoad.setOnClickListener { loadShadowLoader() }
        shadowTest.setOnClickListener { testShadowClass() }

        applyProfileUi()
    }

    // ------------------------------------------------------------ the menu

    /** Open the burger menu: the engine profiles and the tool shortcuts. */
    fun openMenuPanel() {
        val ctx = context ?: return
        val sheet = BottomSheetDialog(ctx)
        val sheetView = LayoutInflater.from(ctx).inflate(R.layout.bottom_sheet_menu, null)
        sheet.setContentView(sheetView)

        val profile = EngineProfile.current(ctx)
        sheetView.findViewById<TextView>(R.id.sheet_subtitle).text =
            getString(R.string.menu_subtitle, profile.label)

        bindEngineRow(sheetView, R.id.engine_cocos2d, R.id.engine_cocos2d_check, EngineProfile.COCOS2D, profile) {
            sheet.dismiss()
            selectEngine(it)
        }
        bindEngineRow(sheetView, R.id.engine_ue4, R.id.engine_ue4_check, EngineProfile.UE4, profile) {
            sheet.dismiss()
            selectEngine(it)
        }

        sheetView.findViewById<View>(R.id.tool_importer).setOnClickListener {
            sheet.dismiss()
            scrollToSection(R.id.section_importer)
        }
        sheetView.findViewById<View>(R.id.tool_analyzer).setOnClickListener {
            sheet.dismiss()
            scrollToSection(R.id.section_analyzer)
        }
        sheetView.findViewById<View>(R.id.tool_patcher).setOnClickListener {
            sheet.dismiss()
            scrollToSection(R.id.section_patcher)
        }

        sheet.show()
    }

    /** Switch the active engine profile (remembered between launches). */
    private fun selectEngine(profile: EngineProfile) {
        val ctx = context ?: return
        EngineProfile.save(ctx, profile)
        applyProfileUi()
        val root = view ?: return
        showSnack(root, "${profile.label} selected")
        log("engine profile -> ${profile.label}")
    }

    /** Mark the picked engine and show only the tools that belong to it. */
    private fun bindEngineRow(
        sheetView: View,
        rowId: Int,
        checkId: Int,
        profile: EngineProfile,
        current: EngineProfile,
        onPick: (EngineProfile) -> Unit
    ) {
        sheetView.findViewById<TextView>(checkId).text = if (profile == current) "\u2713" else ""
        sheetView.findViewById<View>(rowId).setOnClickListener { onPick(profile) }
    }

    /**
     * Show only the tools that make sense for the active engine.
     *
     * The live UE4 dumper (and the aimbot that rides inside it, and the
     * auto-inject that patches libUE4.so) only exist for Unreal Engine, so
     * they are hidden for the Unity and the Cocos2d profiles.
     */
    private fun applyProfileUi() {
        val ctx = context ?: return
        val profile = EngineProfile.current(ctx)
        val unreal = profile.isUnreal

        val ue4Only: List<View> = listOf(
            dumpButton,
            extractDumperButton,
            checkStatusButton,
            testDumperButton,
            injectAimbotButton
        )
        for (v in ue4Only) v.visibility = if (unreal) View.VISIBLE else View.GONE
        showDumpsButton.visibility = if (profile == EngineProfile.COCOS2D) View.VISIBLE else View.GONE
        injectDumperButton.visibility = if (profile == EngineProfile.COCOS2D) View.VISIBLE else View.GONE

        // the auto-inject patch mode patches libUE4.so - Unreal only
        patchModeSpinner.adapter = ArrayAdapter(
            ctx,
            android.R.layout.simple_spinner_dropdown_item,
            patchModes(profile)
        )

        engineLabel.text = getString(R.string.engine_label, profile.label)
    }

    private fun patchModes(profile: EngineProfile): Array<String> {
        val modes = ArrayList<String>()
        // the auto-inject patches the game's own .so - Unreal or Cocos2d
        if (profile.isUnreal || profile == EngineProfile.COCOS2D) {
            modes.add(getString(R.string.patch_mode_autoinject))
        }
        // sign the ORIGINAL package without touching a single entry - that is
        // what a normal signer (MT Manager, apksigner) does
        modes.add(getString(R.string.patch_mode_signonly))
        return modes.toTypedArray()
    }

    /** Scroll the home screen to one of its sections. */
    private fun scrollToSection(id: Int) {
        val target = view?.findViewById<View>(id) ?: return
        scroll.post { scroll.smoothScrollTo(0, target.top) }
    }

    /** Inject aimbot .so into running game via ptrace. */
    private fun injectAimbot() {
        val root = view ?: return
        if (selectedUris.isEmpty()) {
            showSnack(root, "Import an APK first to identify the game")
            return
        }
        log("== Inject Aimbot ==")
        statusView.text = "Injecting aimbot..."
        progressBar.visibility = View.VISIBLE

        val appContext = requireContext().applicationContext
        Thread {
            var message = "Injection failed"
            try {
                // 1. Copy aimbot_mod.so to /sdcard/aimbot_mod.so
                val aimbotDest = File("/sdcard/aimbot_mod.so")
                appContext.assets.open("mod/libaimbot_mod.so").use { input ->
                    FileOutputStream(aimbotDest).use { out -> input.copyTo(out) }
                }
                log("copied aimbot_mod.so to ${aimbotDest.absolutePath}")
                
                // 2. Copy ptrace_injector to app private dir and make executable
                val injectorDest = File(appContext.filesDir, "ptrace_injector")
                appContext.assets.open("bin/ptrace_injector").use { input ->
                    FileOutputStream(injectorDest).use { out -> input.copyTo(out) }
                }
                injectorDest.setExecutable(true, false)
                log("copied ptrace_injector to ${injectorDest.absolutePath}")
                
                // 3. Run injector with su (requires root)
                val pb = ProcessBuilder("su", "-c", "${injectorDest.absolutePath} ${aimbotDest.absolutePath}")
                val proc = pb.start()
                val out = proc.inputStream.bufferedReader().readText()
                val err = proc.errorStream.bufferedReader().readText()
                proc.waitFor()
                log("injector exit code: ${proc.exitValue()}")
                if (out.isNotBlank()) log("stdout: $out")
                if (err.isNotBlank()) log("stderr: $err")
                
                if (proc.exitValue() == 0) {
                    message = "Aimbot injected successfully"
                } else {
                    message = "Injection failed (exit ${proc.exitValue()}): $err"
                }
            } catch (e: Throwable) {
                log("ERROR: $e")
                message = "Injection error: ${e.message}"
            }
            root.post {
                statusView.text = message
                progressBar.visibility = View.GONE
                showSnack(root, message)
            }
        }.start()
    }

    /** Open the system file picker, allowing one APK or a multi-file .apks/.split set. */
    private fun pickApk() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/vnd.android.package-archive",
                    "application/zip",
                    "application/octet-stream"
                )
            )
        }
        startActivityForResult(intent, requestImport)
    }

    /** Open the file picker for the shadow DEX/APK (modded classes overlay). */
    private fun pickShadow() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/vnd.android.package-archive",
                    "application/zip",
                    "application/octet-stream"
                )
            )
        }
        startActivityForResult(intent, requestImportShadow)
    }

    /** Restore the previously persisted shadow loader (if any). */
    private fun restoreShadowLoader() {
        ShadowLoader.restore(requireContext())?.let { loader ->
            shadowLoader = loader
            shadowStatus.text = getString(R.string.shadow_load_ok, loader.remapRules.size)
            shadowTest.isEnabled = true
            shadowLoad.isEnabled = true
            log("restored shadow loader: ${loader.shadowFile.name} (${loader.remapRules.size} rule(s))")
        }
    }

    /** Load the shadow DEX/APK into a ShadowLoader with the configured remap rules. */
    private fun loadShadowLoader() {
        val root = view ?: return
        val uri = shadowSelectedUri
        if (uri == null) {
            showSnack(root, getString(R.string.shadow_no_file))
            return
        }
        val appContext = requireContext().applicationContext
        consoleLog.text = ""
        log("== Load shadow loader ==")
        Thread {
            var message: String
            try {
                val workDir = File("/sdcard/ApkToolMD/shadow_work").apply { deleteRecursively(); mkdirs() }
                val shadowFile = File(workDir, "shadow.dex")
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(shadowFile).use { out -> input.copyTo(out) }
                }
                if (!shadowFile.exists()) {
                    throw RuntimeException("could not read the shadow file")
                }
                log("shadow file: ${shadowFile.length()} bytes")

                val rulesText = shadowRules.text.toString()
                val rules = rulesText.lines().mapNotNull { RemapRule.parse(it) }
                log("remap rules: ${rules.size}")

                val loader = ShadowLoader(appContext, shadowFile, rules, appContext.classLoader)
                loader.persist(appContext)
                shadowLoader = loader
                message = getString(R.string.shadow_load_ok, rules.size)
                root.post {
                    shadowStatus.text = message
                    shadowTest.isEnabled = true
                    showSnack(root, message)
                }
            } catch (e: Throwable) {
                log("ERROR: $e")
                message = getString(R.string.shadow_load_fail, e.message)
                root.post {
                    shadowStatus.text = message
                    showSnack(root, message)
                }
            }
        }.start()
    }

    /** Load + instantiate a remapped shadow class to prove the loader works. */
    private fun testShadowClass() {
        val root = view ?: return
        val loader = shadowLoader
        if (loader == null) {
            showSnack(root, getString(R.string.shadow_not_loaded))
            return
        }
        val className = shadowClass.text.toString().trim()
        if (className.isBlank()) {
            showSnack(root, "Enter a class name")
            return
        }
        log("== Test shadow class: $className ==")
        Thread {
            var message: String
            try {
                val instance = loader.newInstance(className)
                if (instance != null) {
                    message = getString(R.string.shadow_instance_ok, className)
                    log("instantiated: $className -> ${instance.javaClass.name}")
                } else {
                    message = getString(R.string.shadow_instance_fail, className, "class not found or no no-arg constructor")
                }
            } catch (e: Throwable) {
                message = getString(R.string.shadow_instance_fail, className, e.message)
                log("ERROR: $e")
            }
            root.post {
                shadowStatus.text = message
                showSnack(root, message)
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // shadow DEX/APK picker
        if (requestCode == requestImportShadow && resultCode == Activity.RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                shadowSelectedUri = uri
                shadowStatus.text = "Shadow: ${uri.lastPathSegment ?: uri.toString()}"
                log("shadow file picked: $uri")
            }
            return
        }

        if (requestCode != requestImport || resultCode != Activity.RESULT_OK) return

        val uris = ArrayList<Uri>()
        val clip = data?.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                uris.add(clip.getItemAt(i).uri)
            }
        } else {
            data?.data?.let { uris.add(it) }
        }
        if (uris.isNotEmpty()) showDetails(uris)
    }

    private fun showDetails(uris: List<Uri>) {
        val root = view ?: return
        selectedUris = uris

        consoleLog.text = ""
        log("== Import ==")
        log("selected ${uris.size} file(s)")

        val names = ArrayList<String>()
        val sizes = ArrayList<Long>()
        var total = 0L

        for (uri in uris) {
            val meta = readMeta(uri)
            names.add(meta.first)
            sizes.add(meta.second)
            if (meta.second > 0) total += meta.second
            log("  ${meta.first} (${if (meta.second > 0) "${meta.second} bytes" else "unknown size"})")
        }

        val firstName = names.firstOrNull().orEmpty()
        val kind = when {
            uris.size > 1 -> "Split APK bundle"
            firstName.endsWith(".apks", true) -> "APKS bundle"
            firstName.endsWith(".apk", true) -> "APK package"
            firstName.endsWith(".zip", true) -> "ZIP archive"
            else -> "Selected file"
        }

        kindView.text = kind
        summaryView.text =
            "${uris.size} file(s) · ${Formatter.formatFileSize(requireContext(), total)}"

        listView.removeAllViews()
        for (i in uris.indices) {
            val sizeText =
                if (sizes[i] > 0) Formatter.formatFileSize(requireContext(), sizes[i])
                else "unknown size"
            val row = TextView(requireContext())
            row.text = "${names[i]}\n$sizeText"
            row.textSize = 13f
            row.setPadding(0, 8, 0, 8)
            listView.addView(row)
        }

        analyzeResult.text = getString(R.string.analyze_idle)
        analyzeEvidence.text = ""
        analyzeButton.isEnabled = true
        dumpButton.isEnabled = true
        statusView.text = getString(R.string.status_idle)
        progressBar.visibility = View.GONE

        detailsCard.visibility = View.VISIBLE
        showSnack(root, "Imported ${uris.size} file(s)")
    }

    // ---------------------------------------------------------------- analyze

    private fun analyze() {
        val root = view ?: return
        if (selectedUris.isEmpty()) {
            showSnack(root, "Import an APK first")
            return
        }

        analyzeResult.text = getString(R.string.analyze_scanning)
        analyzeEvidence.text = ""
        analyzeButton.isEnabled = false
        dumpButton.isEnabled = false
        statusView.text = getString(R.string.status_scanning)
        progressBar.visibility = View.VISIBLE

        val appContext = requireContext().applicationContext
        val uris = selectedUris
        consoleLog.text = ""
        log("== Analyze ==")
        log("files: ${uris.size}")
        Thread {
            val result = try {
                log("scanning zip entries...")
                val r = runAnalysis(appContext, uris)
                log("detected: ${r.first}")
                log("evidence (${r.second.size}):")
                r.second.forEach { log("  - $it") }
                if (r.first.startsWith("Native Android")) {
                    log("WARNING: no known game engine recognized")
                }
                r
            } catch (e: Throwable) {
                log("ERROR: analysis failed: ${e}")
                log(e.stackTrace.joinToString("\n"))
                "Analysis failed: ${e.message}" to emptyList<String>()
            }
            root.post {
                analyzeResult.text = result.first + "\n" + profileNote(result.first)
                analyzeEvidence.text = result.second.joinToString("\n")
                analyzeButton.isEnabled = true
                dumpButton.isEnabled = true
                        statusView.text = getString(R.string.status_done)
                progressBar.visibility = View.GONE
            }
        }.start()
    }

    /** Does the detected engine match the profile the user picked? */
    private fun profileNote(detected: String): String {
        val profile = EngineProfile.current(requireContext())
        val d = detected.toLowerCase()
        val matches = when (profile) {
            EngineProfile.UNITY -> d.contains("unity")
            EngineProfile.COCOS2D -> d.contains("cocos")
            EngineProfile.UE4 -> d.contains("unreal") || d.contains("ue4")
        }
        val state = getString(if (matches) R.string.engine_matches else R.string.engine_mismatch)
        return "${profile.label} $state"
    }

    private fun runAnalysis(context: Context, uris: List<Uri>): Pair<String, List<String>> {
        val names = LinkedHashSet<String>()
        for (uri in uris) {
            context.contentResolver.openInputStream(uri)?.use { scanZip(it, names) }
        }

        // .apks / split bundles wrap real APKs: dig into base.apk too.
        val innerApks = names.filter { it.toLowerCase().endsWith(".apk") }
        if (innerApks.isNotEmpty() && names.none { looksLikeEngine(it) }) {
            val baseName = innerApks.firstOrNull {
                it.substringAfterLast('/').equals("base.apk", true)
            } ?: innerApks.firstOrNull { it.contains("base", true) } ?: innerApks.first()
            context.contentResolver.openInputStream(uris.first())?.use { input ->
                readEntry(input, baseName)?.let { bytes ->
                    scanZip(ByteArrayInputStream(bytes), names)
                }
            }
        }

        return detectEngine(names)
    }

    private fun scanZip(input: InputStream, out: MutableSet<String>) {
        ZipInputStream(BufferedInputStream(input)).use { zin ->
            var entry = zin.nextEntry
            var count = 0
            while (entry != null && count < 300000) {
                out.add(entry.name)
                entry = zin.nextEntry
                count++
            }
        }
    }

    private fun readEntry(input: InputStream, wanted: String): ByteArray? {
        ZipInputStream(BufferedInputStream(input)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (entry.name == wanted) {
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var read = zin.read(buffer)
                    while (read > 0) {
                        out.write(buffer, 0, read)
                        read = zin.read(buffer)
                    }
                    return out.toByteArray()
                }
                entry = zin.nextEntry
            }
        }
        return null
    }

    private fun looksLikeEngine(name: String): Boolean {
        val n = name.toLowerCase()
        return n.endsWith("libunity.so") || n.contains("assets/bin/data/") ||
            n.endsWith("globalgamemanagers") || n.endsWith("libue4.so") ||
            n.endsWith("libunreal.so") || n.endsWith(".pak") || n.contains("libgodot") ||
            n.endsWith(".pck") || n.contains("libcocos2d") || n.endsWith("libflutter.so") ||
            n.contains("flutter_assets/") || n.endsWith("libreactnativejni.so") ||
            n.endsWith("index.android.bundle") || n.contains("libgdx") ||
            n.endsWith("libyoyo.so") || n.endsWith("game.droid") || n.endsWith("liblove.so") ||
            n.endsWith("libdmengine.so")
    }

    private fun detectEngine(names: Set<String>): Pair<String, List<String>> {
        val lower = names.map { it.toLowerCase() }

        fun match(predicate: (String) -> Boolean): List<String> =
            names.filterIndexed { index, _ -> predicate(lower[index]) }

        match { it.endsWith("libunity.so") || it.contains("assets/bin/data/") || it.endsWith("globalgamemanagers") }
            .takeIf { it.isNotEmpty() }?.let { unity ->
                val il2cpp = match { it.endsWith("libil2cpp.so") }
                val mono = match { it.contains("libmono") }
                val flavor = when {
                    il2cpp.isNotEmpty() -> " (IL2CPP)"
                    mono.isNotEmpty() -> " (Mono)"
                    else -> ""
                }
                val evidence = (unity.take(4) + il2cpp.take(2)).distinct()
                return "Unity$flavor" to evidence
            }

        match {
            it.endsWith("libue4.so") || it.endsWith("libunreal.so") ||
                it.contains("ue4commandline") || it.endsWith(".pak") || it.contains("assets/ue4/")
        }.takeIf { it.isNotEmpty() }?.let { return "Unreal Engine" to it.take(5) }

        match { it.contains("libgodot") || it.endsWith(".pck") }
            .takeIf { it.isNotEmpty() }?.let { return "Godot Engine" to it.take(5) }

        match { it.contains("libcocos2d") || it.contains("cocos2d") }
            .takeIf { it.isNotEmpty() }?.let { return "Cocos2d-x" to it.take(5) }

        match { it.endsWith("libflutter.so") || it.contains("flutter_assets/") }
            .takeIf { it.isNotEmpty() }?.let { return "Flutter" to it.take(5) }

        match {
            it.endsWith("libreactnativejni.so") || it.endsWith("index.android.bundle") ||
                it.endsWith("libhermes.so")
        }.takeIf { it.isNotEmpty() }?.let { return "React Native" to it.take(5) }

        match { it.contains("libgdx") }
            .takeIf { it.isNotEmpty() }?.let { return "libGDX" to it.take(5) }

        match { it.endsWith("libyoyo.so") || it.endsWith("game.droid") }
            .takeIf { it.isNotEmpty() }?.let { return "GameMaker" to it.take(5) }

        match { it.endsWith("libmonodroid.so") || it.contains("libmonosgen") }
            .takeIf { it.isNotEmpty() }?.let { return "Xamarin / Mono" to it.take(5) }

        match { it.contains("assets/www/") || it.endsWith("cordova.js") || it.contains("capacitor") }
            .takeIf { it.isNotEmpty() }?.let { return "Cordova / Ionic (WebView)" to it.take(5) }

        match { it.endsWith("liblove.so") }
            .takeIf { it.isNotEmpty() }?.let { return "LOVE (Lua)" to it.take(5) }

        match { it.endsWith("libdmengine.so") }
            .takeIf { it.isNotEmpty() }?.let { return "Defold" to it.take(5) }

        // Nothing recognized: report as a native Android app.
        val abis = abisFrom(names)
        val soLibs = names.filter { it.startsWith("lib/") && it.endsWith(".so") }
        val evidence = ArrayList<String>()
        if (abis.isNotEmpty()) evidence.add("ABIs: ${abis.joinToString(", ")}")
        evidence.addAll(soLibs.take(10))
        val label = if (soLibs.isEmpty())
            "Native Android (Java/Kotlin) — no native libraries"
        else
            "Native Android (Java/Kotlin) — no known game engine"
        return label to evidence
    }

    private fun abisFrom(names: Set<String>): List<String> {
        val abis = LinkedHashSet<String>()
        val regex = Regex("^lib/([^/]+)/.+\\.so$")
        for (name in names) {
            val result = regex.find(name.toLowerCase()) ?: continue
            abis.add(result.groupValues[1])
        }
        return abis.toList()
    }

    // ---------------------------------------------------------------- dump UE4

    private fun dumpUe4() {
        val root = view ?: return
        if (selectedUris.isEmpty()) {
            showSnack(root, "Import an APK first")
            return
        }

        analyzeButton.isEnabled = false
        dumpButton.isEnabled = false
        statusView.text = getString(R.string.status_dumping)
        progressBar.visibility = View.VISIBLE
        consoleLog.text = ""
        log("== Dump UE4 files ==")

        val appContext = requireContext().applicationContext
        val uris = selectedUris
        val firstMeta = readMeta(uris.first()).first
        val baseName = firstMeta.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "dump" }

        val dumped = ArrayList<String>()
        Thread {
            var count = 0
            var outPath = ""
            var message: String? = null
            val tempFiles = ArrayList<File>()
            try {
                val dumpsRoot = File("/sdcard/ApkToolMD/dumps")
                val outDir = if (dumpsRoot.exists() || dumpsRoot.mkdirs()) {
                    File(dumpsRoot, baseName).apply { mkdirs() }
                } else {
                    log("WARNING: /sdcard not writable, using app storage")
                    File(appContext.getExternalFilesDir("dumps") ?: appContext.cacheDir, baseName).apply { mkdirs() }
                }
                outPath = outDir.absolutePath
                log("output dir: $outPath")

                for (uri in uris) {
                    log("opening: $uri")
                    val input = appContext.contentResolver.openInputStream(uri)
                    if (input == null) {
                        log("WARNING: could not open $uri")
                        continue
                    }
                    val temp = File(appContext.cacheDir, "import_${System.currentTimeMillis()}.tmp")
                    tempFiles.add(temp)
                    FileOutputStream(temp).use { out -> input.use { it.copyTo(out) } }
                    log("cached copy: ${temp.length()} bytes")

                    // Detect bundle (.apks): dump from EVERY inner .apk (base + splits).
                    val targets = ArrayList<File>()
                    ZipFile(temp).use { zf ->
                        val apkEntries = zf.entries().asSequence()
                            .filter { it.name.toLowerCase().endsWith(".apk") }.toList()
                        if (apkEntries.isNotEmpty()) {
                            log("bundle detected: ${apkEntries.size} inner apk(s)")
                            for (ae in apkEntries) {
                                val t = File(appContext.cacheDir, "inner_${System.currentTimeMillis()}.apk")
                                tempFiles.add(t)
                                zf.getInputStream(ae).use { ins ->
                                    FileOutputStream(t).use { outs -> ins.copyTo(outs) }
                                }
                                targets.add(t)
                            }
                        } else {
                            targets.add(temp)
                        }
                    }

                    for (target in targets) {
                        ZipFile(target).use { zf ->
                            val e = zf.entries()
                            while (e.hasMoreElements()) {
                                val entry = e.nextElement()
                                if (entry.isDirectory) continue
                                if (!isUe4Entry(entry.name)) continue
                                val outFile = File(outDir, entry.name).apply { parentFile?.mkdirs() }
                                zf.getInputStream(entry).use { ins ->
                                    FileOutputStream(outFile).use { outs -> ins.copyTo(outs) }
                                }
                                count++
                                dumped.add(entry.name)
                                log("dumped (${entry.size} bytes): ${entry.name}")
                            }
                        }
                    }
                }
                if (count == 0) {
                    log("WARNING: no UE4 entries (.so/.pak) found in the APK")
                } else {
                    log("done: $count file(s) extracted")
                }
            } catch (e: Throwable) {
                log("ERROR: dump failed: ${e}")
                log(e.stackTrace.joinToString("\n"))
                message = "Dump failed: ${e.message}"
            } finally {
                tempFiles.forEach { it.delete() }
            }

            root.post {
                analyzeButton.isEnabled = true
                dumpButton.isEnabled = true
                statusView.text = getString(R.string.status_done)
                progressBar.visibility = View.GONE
                analyzeEvidence.text = if (dumped.isEmpty()) "" else dumped.joinToString("\n")
                if (message != null) {
                    showSnack(root, message)
                } else if (count == 0) {
                    showSnack(root, getString(R.string.dump_none))
                } else {
                    showSnack(root, getString(R.string.dump_done, count, outPath))
                }
            }
        }.start()
    }

    private fun extractDumper() {
        val root = view ?: return
        statusView.text = "Extracting dumper binary…"
        progressBar.visibility = View.VISIBLE
        consoleLog.text = ""
        log("== Extract UE4Dumper binary ==")
        val appContext = requireContext().applicationContext
        Thread {
            var message = "Dumper binary not bundled in this build."
            try {
                val am = appContext.assets
                val names = am.list("dumper") ?: emptyArray()
                log("assets/dumper: ${if (names.isEmpty()) "(empty)" else names.joinToString(", ")}")
                if (names.isNotEmpty()) {
                    val baseDir = File("/sdcard/ApkToolMD/dumper")
                    if (!baseDir.exists() && !baseDir.mkdirs()) {
                        log("WARNING: could not create $baseDir")
                    }
                    val outDir = File(baseDir, "extracted").apply { mkdirs() }
                    for (n in names) {
                        am.open("dumper/$n").use { input ->
                            val out = File(outDir, n)
                            FileOutputStream(out).use { o -> input.copyTo(o) }
                            out.setReadable(true, false)
                            out.setExecutable(true, false)
                            log("wrote $n (${out.length()} bytes, exec=${out.canExecute()})")
                        }
                    }
                    message = "Extracted ${names.size} file(s) to ${outDir.absolutePath}"
                    log(message)
                } else {
                    log("ERROR: no bundled dumper assets found")
                }
            } catch (e: Throwable) {
                log("ERROR: extract failed: ${e}")
                log(e.stackTrace.joinToString("\n"))
                message = "Extract failed: ${e.message}"
            }
            root.post {
                statusView.text = message
                progressBar.visibility = View.GONE
                showSnack(root, message)
            }
        }.start()
    }

    private fun checkDumperStatus() {
        val root = view ?: return
        consoleLog.text = ""
        log("== Check dumper status ==")
        val candidates = ArrayList<File>()
        candidates.add(File("/sdcard/UEDump3r_status.txt"))
        candidates.add(File("/sdcard/UE4Dump3r_status.txt"))
        // also scan each app's own Android/data/*/files dir
        try {
            File("/sdcard/Android/data").listFiles()?.forEach { pkg ->
                val c = File(pkg, "files/UEDump3r_status.txt")
                if (c.exists()) candidates.add(c)
            }
        } catch (e: Throwable) {
            log("WARNING: could not scan Android/data: $e")
        }
        log("checked ${candidates.size} location(s):")
        candidates.forEach { log("  ${it.absolutePath} exists=${it.exists()}") }
        val found = candidates.firstOrNull { it.exists() }
        val msg = if (found != null) {
            try {
                log("reading: ${found.absolutePath}")
                found.readText().trim().ifBlank { "Status file empty." }
            } catch (e: Throwable) {
                log("ERROR: could not read status file: $e")
                "Could not read status file: ${e.message}"
            }
        } else {
            "No status file yet. Inject the dumper and run the game."
        }
        log(msg)
        statusView.text = msg
        showSnack(root, msg)
    }

    private fun testDumper() {
        val root = view ?: return
        statusView.text = "Testing dumper load…"
        progressBar.visibility = View.VISIBLE
        consoleLog.text = ""
        log("== Test dumper (.so load) ==")
        val appContext = requireContext().applicationContext
        Thread {
            var message: String
            log("calling System.loadLibrary(\"shared_UEDump3r_arm64\")")
            try {
                System.loadLibrary("shared_UEDump3r_arm64")
                message = "Loaded OK via System.loadLibrary. Check status in ~25s."
                log(message)
            } catch (e: Throwable) {
                message = "Load failed: ${e}"
                val full = "${e}\n\n${e.stackTrace.joinToString("\n")}"
                log("ERROR: $message")
                log(e.stackTrace.joinToString("\n"))
                try {
                    File(appContext.getExternalFilesDir(null), "dumper_test_error.txt").writeText(full)
                } catch (ignored: Exception) { }
                root.post {
                    val tv = TextView(appContext)
                    tv.text = full
                    tv.setTextIsSelectable(true)
                    tv.textSize = 11f
                    val sv = ScrollView(appContext)
                    sv.addView(tv)
                    AlertDialog.Builder(appContext)
                        .setTitle("Load error")
                        .setView(sv)
                        .setPositiveButton("OK", null)
                        .show()
                }
                root.post {
                    statusView.text = message
                    progressBar.visibility = View.GONE
                    showSnack(root, message)
                }
                return@Thread
            }
            root.post {
                statusView.text = message
                progressBar.visibility = View.GONE
                showSnack(root, message)
            }
        }.start()
    }

    private fun log(line: String) {
        val root = view ?: return
        root.post {
            val scroll = consoleScroll
            val tv = consoleLog
            val atBottom = scroll.scrollY + scroll.height >= tv.height - 20
            tv.append(line + "\n")
            if (atBottom) {
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun patchApk() {
        val root = view ?: return
        if (selectedUris.isEmpty()) {
            showSnack(root, "Import an APK first")
            return
        }
        // the mode list depends on the engine, so compare the text and not
        // the position
        val signOnly = patchModeSpinner.selectedItem?.toString() == getString(R.string.patch_mode_signonly)
        consoleLog.text = ""
        statusView.text = "Patching…"
        progressBar.visibility = View.VISIBLE
        val appContext = requireContext().applicationContext
        log("== Patch APK ==")
        log("mode: ${if (signOnly) "sign only" else "auto-inject"}")
        log("input: ${selectedUris.first()}")
        Thread {
            var message: String
            try {
                // extract dumper .so to /sdcard
                val outDir = File("/sdcard/ApkToolMD/dumper").apply { mkdirs() }
                val am = appContext.assets
                val names = am.list("dumper") ?: emptyArray()
                log("assets/dumper: ${if (names.isEmpty()) "(empty)" else names.joinToString(", ")}")
                for (n in names) {
                    am.open("dumper/$n").use { input ->
                        FileOutputStream(File(outDir, n)).use { o -> input.copyTo(o) }
                    }
                }
                val soPath = File(outDir, "libshared_UEDump3r_arm64.so").absolutePath
                if (EngineProfile.current(requireContext()) == EngineProfile.COCOS2D) {
                    // the cocos runtime dumper travels with the app
                    appContext.assets.open("mod/libcocos_dumper.so").use { input ->
                        FileOutputStream(File(outDir, "libcocos_dumper.so")).use { o -> input.copyTo(o) }
                    }
                    log("extracted libcocos_dumper.so to $outDir")
                }
                if (signOnly) {
                    // Sign the original package as it is - no re-pack, no
                    // library is touched.  This is what a normal signer does.
                    log("== Sign only (the package is not touched) ==")
                    val workDir = File("/sdcard/ApkToolMD/patch_work").apply { deleteRecursively(); mkdirs() }
                    val apkFile = File(workDir, "input.apk")
                    appContext.contentResolver.openInputStream(selectedUris.first())?.use { input ->
                        FileOutputStream(apkFile).use { out -> input.copyTo(out) }
                    }
                    log("original APK: ${apkFile.length()} bytes")
                    try {
                        val signedApk = File("/sdcard/ApkToolMD", "patched-signed.apk")
                        signApk(appContext, apkFile, signedApk)
                        log("signed APK: ${signedApk.absolutePath} (${signedApk.length()} bytes)")
                        signedApkFile = signedApk
                        message = "Signed (nothing changed):\n${signedApk.absolutePath}"
                        root.post {
                            installPatchedButton.visibility = View.VISIBLE
                            installPatchedButton.isEnabled = true
                        }
                    } catch (se: Throwable) {
                        log("ERROR: signing failed: ${se}")
                        message = "Signing failed: ${se.message}"
                    }
                } else {
                    log("== Auto-inject mode (in-memory) ==")
                    val workDir = File("/sdcard/ApkToolMD/patch_work").apply { deleteRecursively(); mkdirs() }
                    val apkFile = File(workDir, "input.apk")
                    log("copying imported APK...")
                    appContext.contentResolver.openInputStream(selectedUris.first())?.use { input ->
                        FileOutputStream(apkFile).use { out -> input.copyTo(out) }
                    }
                    log("original APK: ${apkFile.length()} bytes")
                    log("extracting patchelf to internal storage...")
                    val patchelf = File(appContext.filesDir, "patchelf").apply { delete() }
                    appContext.assets.open("dumper/patchelf").use { input ->
                        FileOutputStream(patchelf).use { out -> input.copyTo(out) }
                    }
                    patchelf.setExecutable(true, false)
                    var targetLibName: String? = null
                    var payloadName = "libshared_UEDump3r_arm64.so"
                    var payloadAsset = "dumper/libshared_UEDump3r_arm64.so"
                    var origEntryCount = 0
                    ZipFile(apkFile).use { zf ->
                        val entries = zf.entries().asSequence().map { it.name }.toList()
                        origEntryCount = entries.size
                        log("original APK has ${entries.size} entries")
                        val target = injectionTarget(entries)
                        if (target != null) {
                            targetLibName = target.libEntry
                            payloadName = target.payloadName
                            payloadAsset = target.payloadAsset
                        }
                    }
                    var dumperSoBytes = ByteArray(0)
                    if (!signOnly) {
                        dumperSoBytes = appContext.assets.open(payloadAsset).use { it.readBytes() }
                        log("loaded payload $payloadName: ${dumperSoBytes.size} bytes")
                    } else {
                        log("sign only: the package is not touched")
                    }
                    if (targetLibName == null && !signOnly) {
                        log("ERROR: no hookable library in the APK")
                        message = "No ${hookHint()} found in APK. Auto-inject failed."
                    } else {
                        log("target lib: $targetLibName")
                        val targetLibFile = File(workDir, "target_lib.so")
                        ZipFile(apkFile).use { zf ->
                            val entry = zf.getEntry(targetLibName)
                            zf.getInputStream(entry).use { input ->
                                FileOutputStream(targetLibFile).use { out -> input.copyTo(out) }
                            }
                        }
                        log("extracted target lib: ${targetLibFile.length()} bytes")
                        log("running patchelf --add-needed ...")
                        val pb = ProcessBuilder(patchelf.absolutePath, "--add-needed", payloadName, targetLibFile.absolutePath)
                        pb.directory(workDir)
                        val proc = pb.start()
                        val err = proc.errorStream.bufferedReader().readText()
                        val out = proc.inputStream.bufferedReader().readText()
                        proc.waitFor()
                        log("patchelf exit code: ${proc.exitValue()}")
                        if (err.isNotBlank()) log("stderr: $err")
                        if (out.isNotBlank()) log("stdout: $out")
                        if (proc.exitValue() != 0) {
                            message = "patchelf failed (exit ${proc.exitValue()}): $err"
                        } else {
                            log("verifying patch...")
                            val verifyPb = ProcessBuilder(patchelf.absolutePath, "--print-needed", targetLibFile.absolutePath)
                            val verifyProc = verifyPb.start()
                            val verifyOut = verifyProc.inputStream.bufferedReader().readText()
                            verifyProc.waitFor()
                            log("needed libs after patch:\n$verifyOut")
                            val patchedLibBytes = targetLibFile.readBytes()
                            log("patched lib size: ${patchedLibBytes.size} bytes")
                            // The surgical patch: every entry of the original
                            // package is copied VERBATIM (local header +
                            // compressed data), only the engine library is
                            // replaced and the payload is added.  A full
                            // re-pack changes the compression of the entries
                            // and that is enough to kill the game.
                            log("patching the APK in place (the other entries stay byte identical)...")
                            val outApk = File("/sdcard/ApkToolMD", "patched.apk").apply { parentFile?.mkdirs() }
                            val replaceMap = HashMap<String, ByteArray>()
                            replaceMap[targetLibName ?: ""] = patchedLibBytes
                            val addMap = HashMap<String, ByteArray>()
                            addMap["lib/arm64-v8a/$payloadName"] = dumperSoBytes
                            val patchErr = SurgicalZip.patch(apkFile, outApk, replaceMap, addMap)
                            if (patchErr != null) {
                                log("ERROR: the surgical patch failed: $patchErr")
                                message = "Patch failed: $patchErr"
                                throw RuntimeException(patchErr)
                            }
                            log("patched: ${outApk.length()} bytes (was ${apkFile.length()})")
                            log("patched APK size: ${outApk.length()} bytes")
                            log("native lib alignment (must be mod4096=0):")
                            libAlignments(outApk).forEach { log("  $it") }
                            try {
                                ZipFile(outApk).use { zf ->
                                    log("validation: APK opens as zip, ${zf.size()} entries")
                                }
                            } catch (e: Throwable) {
                                log("validation FAILED: $e")
                            }
                            log("signing patched APK...")
                            val signedApk = File("/sdcard/ApkToolMD", "patched-signed.apk")
                            try {
                                signApk(appContext, outApk, signedApk)
                                log("signed APK: ${signedApk.absolutePath} (${signedApk.length()} bytes)")
                                try {
                                    ZipFile(signedApk).use { zf ->
                                        log("signed APK validation: ${zf.size()} entries")
                                    }
                                } catch (e: Throwable) {
                                    log("signed APK zip check failed: $e")
                                }
                                signedApkFile = signedApk
                                message = "Patched + signed APK:\n${signedApk.absolutePath}\nReady to install."
                                root.post {
                                    installPatchedButton.visibility = View.VISIBLE
                                    installPatchedButton.isEnabled = true
                                }
                            } catch (se: Throwable) {
                                log("ERROR: signing failed: ${se}")
                                log(se.stackTrace.joinToString("\n"))
                                signedApkFile = outApk
                                message = "Patched (unsigned): ${outApk.absolutePath}\nSigning failed: ${se.message}"
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                log("EXCEPTION: ${e}")
                log(e.stackTrace.joinToString("\n"))
                message = "Patch failed: ${e}"
            }
            root.post {
                statusView.text = message
                progressBar.visibility = View.GONE
                showSnack(root, message)
            }
        }.start()
    }

    /** Sign an APK with the keystore embedded in the app assets (v1 + v2 scheme). */
    private fun signApk(context: Context, unsigned: File, signed: File) {
        val ksDir = File(context.cacheDir, "ks").apply { mkdirs() }
        val ksFile = File(ksDir, "release.p12")
        context.assets.open("keystore/release.p12").use { ins ->
            FileOutputStream(ksFile).use { outs -> ins.copyTo(outs) }
        }
        val alias = context.assets.open("keystore/alias").bufferedReader().readText().trim()
        val storePass = context.assets.open("keystore/storepass").bufferedReader().readText().trim()
        val keyPass = context.assets.open("keystore/keypass").bufferedReader().readText().trim()

        val keyStore = KeyStore.getInstance("PKCS12")
        FileInputStream(ksFile).use { keyStore.load(it, storePass.toCharArray()) }
        val keyAlias = if (alias.isNotEmpty() && keyStore.containsAlias(alias)) alias
        else keyStore.aliases().nextElement()
        val privateKey = keyStore.getKey(keyAlias, keyPass.toCharArray()) as PrivateKey
        val cert = keyStore.getCertificate(keyAlias) as X509Certificate

        val signerConfig = ApkSigner.SignerConfig.Builder(
            "apktoolmd",
            privateKey,
            listOf(cert)
        ).build()

        if (signed.exists()) signed.delete()
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsigned)
            .setOutputApk(signed)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            // MT Manager (and the other normal signers) also write the v3
            // block - a game that checks the signing block sees the same
            // shape as it does with a normal signer.
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }

    /** Launch the system package installer for the patched+signed APK. */
    private fun installPatched() {
        val root = view ?: return
        val apk = signedApkFile
        if (apk == null || !apk.exists()) {
            showSnack(root, "No patched APK yet. Patch an APK first.")
            return
        }
        log("== Install patched APK ==")
        log("apk: ${apk.absolutePath} (${apk.length()} bytes)")
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                requireContext().packageName + ".fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            log("package installer launched")
        } catch (e: Throwable) {
            log("ERROR: install intent failed: $e")
            log(e.stackTrace.joinToString("\n"))
            showSnack(root, "Install failed: ${e.message}")
        }
    }

    private fun isUe4Entry(name: String): Boolean {
        val n = name.toLowerCase()
        return n.endsWith("libue4.so") || n.endsWith("libunreal.so") ||
            n.endsWith(".pak") || n.contains("ue4commandline") ||
            n.contains("assets/ue4/")
    }

    /** What we inject and into which library - it depends on the engine. */
    private data class InjectionTarget(val libEntry: String, val payloadName: String, val payloadAsset: String)

    private val cocosLibs = arrayOf(
        "libcocos2dcpp.so", "libcocos2djs.so", "libcocos2dlua.so",
        "libcocos2d.so", "libcocos.so", "libgame.so"
    )

    private fun injectionTarget(entries: List<String>): InjectionTarget? {
        val profile = EngineProfile.current(requireContext())
        if (profile.isUnreal) {
            val lib = when {
                entries.contains("lib/arm64-v8a/libUE4.so") -> "lib/arm64-v8a/libUE4.so"
                entries.contains("lib/arm64-v8a/libUnreal.so") -> "lib/arm64-v8a/libUnreal.so"
                else -> null
            }
            return lib?.let {
                InjectionTarget(it, "libshared_UEDump3r_arm64.so", "dumper/libshared_UEDump3r_arm64.so")
            }
        }
        if (profile == EngineProfile.COCOS2D) {
            val lib = entries.firstOrNull { e ->
                e.startsWith("lib/arm64-v8a/") && cocosLibs.any { e.endsWith(it) }
            }
            return lib?.let {
                InjectionTarget(it, "libcocos_dumper.so", "mod/libcocos_dumper.so")
            }
        }
        return null
    }

    private fun hookHint(): String {
        val profile = EngineProfile.current(requireContext())
        return if (profile.isUnreal) "libUE4.so/libUnreal.so" else cocosLibs.joinToString("/")
    }

    // ------------------------------------------------------- cocos2d scripts


    /**
     * Load the dumper into the RUNNING game with root.
     *
     * The game, its APK and its libraries are not touched: the .so is copied
     * next to the game's own library and then loaded into the running
     * process with ptrace (the classic remote dlopen).  Nothing in the
     * package changes, so the game cannot detect it.
     */
    private fun injectDumper() {
        val root = view ?: return
        val appContext = requireContext().applicationContext
        statusView.text = "Injecting the dumper..."
        progressBar.visibility = View.VISIBLE
        consoleLog.text = ""
        log("== Inject the dumper into the running game (root) ==")
        val filesDir = appContext.filesDir
        Thread {
            var message: String? = null
            try {
                val injector = File(filesDir, "ptrace_injector")
                val payload = File(filesDir, "libcocos_dumper.so")
                appContext.assets.open("bin/ptrace_injector").use { input ->
                    FileOutputStream(injector).use { o -> input.copyTo(o) }
                }
                injector.setExecutable(true, false)
                appContext.assets.open("mod/libcocos_dumper.so").use { input ->
                    FileOutputStream(payload).use { o -> input.copyTo(o) }
                }
                payload.setReadable(true, false)
                log("injector: ${injector.length()} bytes, payload: ${payload.length()} bytes")

                val script = """
                    set -e
                    LIBDIR=''
                    for d in /data/app/*/lib/arm64; do
                      [ -d "@D@d" ] || continue
                      for n in libcocos2dcpp.so libcocos2djs.so libcocos2dlua.so libcocos.so libgame.so; do
                        if [ -f "@D@d/@D@n" ]; then LIBDIR="@D@d"; LIB="@D@n"; break; fi
                      done
                      if [ -n "@D@LIBDIR" ]; then break; fi
                    done
                    if [ -z "@D@LIBDIR" ]; then echo NO_GAME_LIB; exit 3; fi
                    echo "lib dir: @D@LIBDIR  lib: @D@LIB"
                    cp "@PAYLOAD@" "@D@LIBDIR/libcocos_dumper.so"
                    chmod 755 "@D@LIBDIR/libcocos_dumper.so"
                    chown --reference="@D@LIBDIR/@D@LIB" "@D@LIBDIR/libcocos_dumper.so" 2>/dev/null || true
                    restorecon "@D@LIBDIR/libcocos_dumper.so" 2>/dev/null || true
                    "@INJECTOR@" "@D@LIBDIR/libcocos_dumper.so" "@D@LIB"
                """.trimIndent()
                    .replace("@D@", "$")
                    .replace("@PAYLOAD@", payload.absolutePath)
                    .replace("@INJECTOR@", injector.absolutePath)
                val scriptFile = File(filesDir, "inject_dumper.sh")
                scriptFile.writeText(script)

                val pb = ProcessBuilder("su", "-c", "sh ${scriptFile.absolutePath}")
                val proc = pb.start()
                val out = proc.inputStream.bufferedReader().readText()
                val err = proc.errorStream.bufferedReader().readText()
                proc.waitFor()
                if (out.isNotBlank()) log(out.trim())
                if (err.isNotBlank()) log("stderr: ${err.trim()}")
                log("exit code: ${proc.exitValue()}")
                if (out.contains("injection is done") || out.contains("dlopen")) {
                    message = "Injected - the dumper is in the game now."
                } else if (out.contains("NO_GAME_LIB")) {
                    message = "The game does not seem to be installed."
                } else {
                    message = "Injection failed: ${err.trim().ifBlank { out.trim() }}"
                }
            } catch (e: Throwable) {
                log("ERROR: $e")
                log(e.stackTrace.joinToString("\n"))
                message = "Failed: ${e.message}"
            }
            root.post {
                progressBar.visibility = View.GONE
                val done = message ?: "Done"
                statusView.text = done
                showSnack(root, done)
            }
        }.start()
    }

    /** List what the runtime dumper wrote. */
    private fun showDumps() {
        val ctx = context ?: return
        val dir = File("/sdcard/ApkToolMD/cocos_dump")
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        val text = if (files.isEmpty()) {
            getString(R.string.cocos_no_dumps)
        } else {
            files.joinToString("\n") { "${it.name}  (${it.length()} bytes)" }
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.cocos_dumps_title))
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }

    /** Verify page alignment of native libs inside a zip (zipalign -p check). */
    private fun libAlignments(f: File): List<String> {
        val out = ArrayList<String>()
        try {
            java.io.RandomAccessFile(f, "r").use { raf ->
                var pos = 0L
                val len = raf.length()
                val buf = ByteArray(30)
                while (pos + 30 <= len) {
                    raf.seek(pos)
                    if (raf.read(buf, 0, 4) != 4) break
                    val sig = (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8) or
                        ((buf[2].toInt() and 0xFF) shl 16) or ((buf[3].toInt() and 0xFF) shl 24)
                    if (sig != 0x04034b50) break
                    raf.seek(pos)
                    raf.readFully(buf)
                    fun u16(o: Int) = (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8)
                    fun u32(o: Int): Long = (buf[o].toLong() and 0xFF) or ((buf[o + 1].toLong() and 0xFF) shl 8) or
                        ((buf[o + 2].toLong() and 0xFF) shl 16) or ((buf[o + 3].toLong() and 0xFF) shl 24)
                    val csize = u32(18)
                    val nlen = u16(26)
                    val elen = u16(28)
                    val name = ByteArray(nlen).also { raf.readFully(it) }.toString(Charsets.UTF_8)
                    val dataOff = pos + 30 + nlen + elen
                    if (name.endsWith(".so")) {
                        out.add("$name  mod4096=${dataOff % 4096}")
                    }
                    pos = dataOff + csize
                }
            }
        } catch (e: Throwable) {
            out.add("align check failed: $e")
        }
        return out
    }

    // ------------------------------------------------------------------- util

    /** Read display name + size for a content uri. */
    private fun readMeta(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "unknown"
        var size = -1L
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        var cursor: Cursor? = null
        try {
            cursor = requireContext().contentResolver.query(uri, projection, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
            }
        } catch (ignored: Exception) {
            // fall back to the defaults above
            log("WARNING: could not read metadata for $uri: ${ignored.message}")
        } finally {
            cursor?.close()
        }
        return name to size
    }

    private fun showSnack(view: View, message: String) {
        val snackbar = Snackbar.make(view, message, Snackbar.LENGTH_LONG)
        requireActivity().findViewById<View>(R.id.bottom_nav)?.let {
            snackbar.setAnchorView(it)
        }
        snackbar.show()
    }


}
