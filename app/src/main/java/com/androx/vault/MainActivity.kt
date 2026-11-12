package com.androx.vault

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("androx_private", MODE_PRIVATE) }
    private val selected = linkedSetOf<String>()

    private var expression = ""
    private var currentInput = "0"
    private var lastWasOperator = false
    private var justEvaluated = false
    private var failedAttempts = 0
    private var adBlockEnabled = true
    private var adsBlockedCount = 0

    private lateinit var root: LinearLayout
    private lateinit var displayExpression: TextView
    private lateinit var displayResult: TextView

    private val bg = Color.parseColor("#0D1110")
    private val panel = Color.parseColor("#1A221C")
    private val accent = Color.parseColor("#2EE56D")
    private val muted = Color.parseColor("#8B9A8E")
    private val white = Color.parseColor("#E8F0EA")
    private val keyNumber = Color.parseColor("#252E27")
    private val keyOperator = Color.parseColor("#1E3A28")
    private val keyClear = Color.parseColor("#3A2A1E")
    private val danger = Color.parseColor("#E85D5D")

    private val REQ_CAMERA = 1001
    private val REQ_PICK_FILE = 1002

    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    // Common ad / tracker domains (simplified but effective list)
    private val blockedHosts = setOf(
        "doubleclick.net", "googleadservices.com", "googlesyndication.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com",
        "adservice.google.com", "pagead2.googlesyndication.com",
        "facebook.net", "facebook.com/tr", "connect.facebook.net",
        "scorecardresearch.com", "quantserve.com", "outbrain.com",
        "taboola.com", "criteo.com", "criteo.net", "adsrvr.org",
        "adnxs.com", "adsystem.com", "amazon-adsystem.com",
        "moatads.com", "pubmatic.com", "openx.net", "rubiconproject.com",
        "casalemedia.com", "advertising.com", "adform.net",
        "hotjar.com", "mouseflow.com", "fullstory.com",
        "segment.io", "segment.com", "mixpanel.com", "amplitude.com",
        "newrelic.com", "nr-data.net", "sentry.io",
        "ads.yahoo.com", "adserver", "banner", "popads",
        "propellerads.com", "exoclick.com", "juicyads.com",
        "mgid.com", "revcontent.com", "zergnet.com",
        "2mdn.net", "adsafeprotected.com", "advertising.com",
        "adcolony.com", "unity3d.com/ads", "applovin.com",
        "chartboost.com", "vungle.com", "ironsrc.com",
        "tapjoy.com", "supersonicads.com"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected.addAll(prefs.getStringSet("selected_apps", emptySet()) ?: emptySet())
        failedAttempts = prefs.getInt("failed_attempts", 0)
        adBlockEnabled = prefs.getBoolean("adblock", true)
        showCalculator()
    }

    override fun onDestroy() {
        closeCamera()
        stopBackgroundThread()
        super.onDestroy()
    }

    private fun baseScreen(): LinearLayout {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(16))
            setBackgroundColor(bg)
        }
        setContentView(root)
        return root
    }

    // ==================== CALCULATOR ====================

    private fun showCalculator() {
        expression = ""
        currentInput = "0"
        lastWasOperator = false
        justEvaluated = false

        val box = baseScreen()
        val top = TextView(this).apply {
            text = "Calcolatrice"
            textSize = 14f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(12))
        }
        box.addView(top, matchWrap())

        displayExpression = TextView(this).apply {
            text = ""
            textSize = 16f
            setTextColor(muted)
            gravity = Gravity.END
            setPadding(dp(12), dp(8), dp(12), 0)
            maxLines = 1
        }
        box.addView(displayExpression, matchWrap())

        displayResult = TextView(this).apply {
            text = "0"
            textSize = 48f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(16))
            maxLines = 1
        }
        box.addView(displayResult, LinearLayout.LayoutParams(-1, dp(90)))
        box.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        val keys = listOf(
            listOf("C", "⌫", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("0", ".", "=")
        )
        keys.forEach { rowKeys ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            rowKeys.forEach { key ->
                val weight = if (key == "0") 2f else 1f
                row.addView(createKeyButton(key), LinearLayout.LayoutParams(0, dp(72), weight).apply {
                    setMargins(dp(5), dp(5), dp(5), dp(5))
                })
            }
            if (rowKeys.size == 3) {
                row.addView(View(this), LinearLayout.LayoutParams(0, dp(72), 1f).apply {
                    setMargins(dp(5), dp(5), dp(5), dp(5))
                })
            }
            box.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun createKeyButton(key: String): Button {
        val isOperator = key in listOf("÷", "×", "−", "+", "=")
        val isClear = key in listOf("C", "⌫", "%")
        return Button(this).apply {
            text = key
            textSize = if (key == "=") 26f else 22f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            stateListAnimator = null
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            setPadding(0, 0, 0, 0)
            when {
                key == "=" -> { setTextColor(bg); background = rounded(accent, 20) }
                isOperator -> { setTextColor(accent); background = rounded(keyOperator, 20) }
                isClear -> { setTextColor(Color.parseColor("#E8A87C")); background = rounded(keyClear, 20) }
                else -> { setTextColor(white); background = rounded(keyNumber, 20) }
            }
            setOnClickListener { onCalculatorKey(key) }
        }
    }

    private fun onCalculatorKey(key: String) {
        when (key) {
            "C" -> { expression = ""; currentInput = "0"; lastWasOperator = false; justEvaluated = false; updateDisplay() }
            "⌫" -> {
                if (justEvaluated) { expression = ""; currentInput = "0"; justEvaluated = false }
                else if (currentInput.length > 1) currentInput = currentInput.dropLast(1)
                else currentInput = "0"
                lastWasOperator = false; updateDisplay()
            }
            "%" -> {
                try {
                    currentInput = formatNumber(currentInput.toBigDecimal().divide(BigDecimal(100), 10, RoundingMode.HALF_UP))
                    justEvaluated = false; updateDisplay()
                } catch (_: Exception) {}
            }
            "÷", "×", "−", "+" -> {
                if (justEvaluated) { expression = currentInput; justEvaluated = false }
                else if (!lastWasOperator) expression += currentInput
                else if (expression.isNotEmpty()) expression = expression.dropLast(1)
                expression += key; lastWasOperator = true; currentInput = "0"; updateDisplay()
            }
            "." -> {
                if (justEvaluated) { currentInput = "0."; expression = ""; justEvaluated = false }
                else if (!currentInput.contains(".")) currentInput += "."
                lastWasOperator = false; updateDisplay()
            }
            "=" -> {
                val pin = prefs.getString("pin", null)
                if (pin != null && currentInput == pin && !justEvaluated) {
                    failedAttempts = 0; prefs.edit().putInt("failed_attempts", 0).apply()
                    showVaultHome(); return
                }
                if (pin == null && currentInput.length in 4..8 && currentInput.all { it.isDigit() }) {
                    prefs.edit().putString("pin", currentInput).apply()
                    Toast.makeText(this, "PIN impostato", Toast.LENGTH_SHORT).show()
                    showVaultHome(); return
                }
                if (pin != null && currentInput.length in 4..8 && currentInput.all { it.isDigit() } && currentInput != pin) {
                    failedAttempts++; prefs.edit().putInt("failed_attempts", failedAttempts).apply()
                    if (failedAttempts >= 2) takeIntruderPhoto()
                    Toast.makeText(this, "PIN non corretto", Toast.LENGTH_SHORT).show()
                    currentInput = "0"; updateDisplay(); return
                }
                try {
                    if (!lastWasOperator) expression += currentInput
                    currentInput = formatNumber(evaluate(expression))
                    expression = ""; justEvaluated = true; lastWasOperator = false; updateDisplay()
                } catch (_: Exception) {
                    currentInput = "Errore"; expression = ""; justEvaluated = true; updateDisplay()
                }
            }
            else -> {
                if (justEvaluated) { currentInput = key; expression = ""; justEvaluated = false }
                else if (lastWasOperator || currentInput == "0") currentInput = key
                else if (currentInput.length < 12) currentInput += key
                lastWasOperator = false; updateDisplay()
            }
        }
    }

    private fun updateDisplay() {
        displayExpression.text = expression
        displayResult.text = currentInput
        displayResult.textSize = when {
            currentInput.length > 10 -> 32f
            currentInput.length > 7 -> 40f
            else -> 48f
        }
    }

    private fun evaluate(expr: String): BigDecimal {
        var s = expr.replace("×", "*").replace("÷", "/").replace("−", "-")
        if (s.isEmpty()) return BigDecimal.ZERO
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            when {
                s[i].isDigit() || s[i] == '.' -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    tokens.add(s.substring(start, i))
                }
                s[i] in "+-*/" -> { tokens.add(s[i].toString()); i++ }
                else -> i++
            }
        }
        if (tokens.isEmpty()) return BigDecimal.ZERO
        val pass1 = mutableListOf<String>()
        var j = 0
        while (j < tokens.size) {
            if (j + 1 < tokens.size && (tokens[j + 1] == "*" || tokens[j + 1] == "/")) {
                var acc = tokens[j].toBigDecimal()
                while (j + 1 < tokens.size && (tokens[j + 1] == "*" || tokens[j + 1] == "/")) {
                    val op = tokens[j + 1]; val next = tokens[j + 2].toBigDecimal()
                    acc = if (op == "*") acc.multiply(next) else acc.divide(next, 10, RoundingMode.HALF_UP)
                    j += 2
                }
                pass1.add(acc.toPlainString()); j++
            } else { pass1.add(tokens[j]); j++ }
        }
        var result = pass1[0].toBigDecimal()
        var k = 1
        while (k < pass1.size) {
            val op = pass1[k]; val next = pass1[k + 1].toBigDecimal()
            result = if (op == "+") result.add(next) else result.subtract(next)
            k += 2
        }
        return result
    }

    private fun formatNumber(n: BigDecimal): String {
        val stripped = n.stripTrailingZeros()
        return if (stripped.scale() <= 0) stripped.toBigInteger().toString() else stripped.toPlainString()
    }

    // ==================== INTRUDER ====================

    private fun takeIntruderPhoto() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA); return
        }
        try {
            startBackgroundThread()
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            val frontId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: return
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining()); buffer.get(bytes); image.close()
                    saveIntruderPhoto(bytes); closeCamera()
                }, backgroundHandler)
            }
            manager.openCamera(frontId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val surface = imageReader!!.surface
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(surface) }.build()
                        camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) { session.capture(request, null, backgroundHandler) }
                            override fun onConfigureFailed(session: CameraCaptureSession) { closeCamera() }
                        }, backgroundHandler)
                    } catch (_: Exception) { closeCamera() }
                }
                override fun onDisconnected(camera: CameraDevice) { closeCamera() }
                override fun onError(camera: CameraDevice, error: Int) { closeCamera() }
            }, backgroundHandler)
        } catch (_: Exception) {}
    }

    private fun saveIntruderPhoto(jpeg: ByteArray) {
        try {
            val dir = File(filesDir, "intruders").apply { mkdirs() }
            File(dir, "intruder_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg").writeBytes(jpeg)
        } catch (_: Exception) {}
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBg").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (_: Exception) {}
        backgroundThread = null; backgroundHandler = null
    }

    private fun closeCamera() {
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
    }

    // ==================== VAULT ====================

    private fun showVaultHome(tab: Int = 0) {
        val box = baseScreen()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "Vault"; textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(white)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply {
            text = "⋮"; textSize = 22f; setTextColor(white); isAllCaps = false
            background = rounded(panel, 16); setOnClickListener { showSettings() }
        }, LinearLayout.LayoutParams(dp(48), dp(44)))
        box.addView(header, matchWrap())

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, dp(12) }
        listOf("App", "File", "Browser").forEachIndexed { index, name ->
            tabs.addView(Button(this).apply {
                text = name; textSize = 14f; isAllCaps = false
                setTextColor(if (index == tab) bg else white)
                background = rounded(if (index == tab) accent else panel, 14)
                setOnClickListener { showVaultHome(index) }
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        box.addView(tabs, matchWrap())

        when (tab) {
            0 -> showAppsContent(box)
            1 -> showFilesContent(box)
            2 -> showBrowserContent(box)
        }

        box.addView(Button(this).apply {
            text = "Blocca vault"; isAllCaps = false; setTextColor(muted)
            background = rounded(panel, 14); setOnClickListener { showCalculator() }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
    }

    private fun showAppsContent(box: LinearLayout) {
        box.addView(Button(this).apply {
            text = "+  Aggiungi applicazioni"; textSize = 15f; isAllCaps = false; setTextColor(accent)
            background = rounded(Color.parseColor("#1E3A28"), 16); setOnClickListener { showAppPicker() }
        }, LinearLayout.LayoutParams(-1, dp(50)))

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(8) }
        val apps = installedApps().filter { selected.contains(it.packageName) }
        if (apps.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Nessuna app nel vault."; textSize = 15f; setTextColor(muted); gravity = Gravity.CENTER
                setPadding(dp(16), dp(36), dp(16), dp(36))
            }, matchWrap())
        }
        apps.forEach { app ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = rounded(panel, 16); setPadding(dp(16), dp(12), dp(12), dp(12))
                setOnClickListener { launchApp(app.packageName) }
            }
            item.addView(TextView(this).apply { text = app.label; textSize = 16f; setTextColor(white) }, LinearLayout.LayoutParams(0, dp(44), 1f))
            item.addView(TextView(this).apply { text = "APRI ›"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(accent) }, LinearLayout.LayoutParams(dp(70), dp(44)))
            list.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        scroll.addView(list)
        box.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showFilesContent(box: LinearLayout) {
        box.addView(Button(this).apply {
            text = "+  Aggiungi foto / video"; textSize = 15f; isAllCaps = false; setTextColor(accent)
            background = rounded(Color.parseColor("#1E3A28"), 16); setOnClickListener { pickFile() }
        }, LinearLayout.LayoutParams(-1, dp(50)))

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(8) }
        val vaultDir = File(filesDir, "vault").apply { mkdirs() }
        val files = vaultDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Nessun file nel vault.\nI file vengono criptati con AES-GCM."
                textSize = 15f; setTextColor(muted); gravity = Gravity.CENTER
                setPadding(dp(16), dp(36), dp(16), dp(36))
            }, matchWrap())
        }
        files.forEach { f ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = rounded(panel, 16); setPadding(dp(14), dp(12), dp(10), dp(12))
            }
            item.addView(TextView(this).apply { text = f.nameWithoutExtension.take(28); textSize = 14f; setTextColor(white) }, LinearLayout.LayoutParams(0, -2, 1f))
            item.addView(TextView(this).apply {
                text = "Apri"; textSize = 13f; setTextColor(accent); setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { openEncryptedFile(f) }
            })
            item.addView(TextView(this).apply {
                text = "✕"; textSize = 16f; setTextColor(danger); setPadding(dp(10), dp(6), dp(6), dp(6))
                setOnClickListener { f.delete(); showVaultHome(1) }
            })
            list.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }

        val intruders = File(filesDir, "intruders").listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (intruders.isNotEmpty()) {
            list.addView(TextView(this).apply {
                text = "Foto intrusi (${intruders.size})"; textSize = 13f; setTextColor(danger)
                setPadding(0, dp(18), 0, dp(8))
            }, matchWrap())
            intruders.take(10).forEach { f ->
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = rounded(Color.parseColor("#2A1A1A"), 14); setPadding(dp(12), dp(10), dp(10), dp(10))
                }
                item.addView(TextView(this).apply { text = f.name; textSize = 13f; setTextColor(Color.parseColor("#E8A0A0")) }, LinearLayout.LayoutParams(0, -2, 1f))
                item.addView(TextView(this).apply {
                    text = "Vedi"; textSize = 13f; setTextColor(danger); setOnClickListener { viewImageFile(f) }
                })
                list.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
        }
        scroll.addView(list)
        box.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    // ==================== BROWSER + AD BLOCKER ====================

    private fun showBrowserContent(box: LinearLayout) {
        adsBlockedCount = 0

        // Status bar
        val status = TextView(this).apply {
            text = if (adBlockEnabled) "🛡 Ad Block attivo" else "Ad Block disattivato"
            textSize = 12f
            setTextColor(if (adBlockEnabled) accent else muted)
            setPadding(0, 0, 0, dp(8))
        }
        box.addView(status, matchWrap())

        val urlBar = EditText(this).apply {
            hint = "Cerca o inserisci URL"
            setTextColor(white); setHintTextColor(muted)
            setBackgroundColor(panel)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            textSize = 15f; imeOptions = EditorInfo.IME_ACTION_GO; setSingleLine()
        }

        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.saveFormData = false
            settings.setSupportZoom(true)
            settings.mediaPlaybackRequiresUserGesture = true
            setBackgroundColor(bg)

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    if (!adBlockEnabled || request == null) return super.shouldInterceptRequest(view, request)
                    val url = request.url?.toString()?.lowercase() ?: return super.shouldInterceptRequest(view, request)
                    val host = request.url?.host?.lowercase() ?: ""

                    val isBlocked = blockedHosts.any { host.contains(it) || url.contains(it) } ||
                            url.contains("/ads/") || url.contains("adsystem") ||
                            url.contains("doubleclick") || url.contains("pagead") ||
                            url.contains("googlesyndication") || url.contains("sponsor")

                    if (isBlocked) {
                        adsBlockedCount++
                        runOnUiThread {
                            status.text = "🛡 Bloccati: $adsBlockedCount annunci/tracker"
                        }
                        // Return empty response
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Inject basic CSS to hide leftover ad containers
                    if (adBlockEnabled) {
                        view?.evaluateJavascript(
                            """
                            (function(){
                              var s=document.createElement('style');
                              s.innerHTML='[id*="ad"],[class*="ad-"],[class*="ads"],[class*="banner"],[id*="google_ads"],iframe[src*="doubleclick"],iframe[src*="googlesyndication"]{display:none!important;height:0!important;width:0!important;}';
                              document.head.appendChild(s);
                            })();
                            """.trimIndent(), null
                        )
                    }
                }
            }
            webChromeClient = WebChromeClient()
        }

        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                var url = urlBar.text.toString().trim()
                if (url.isNotEmpty()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = if (url.contains(".") && !url.contains(" ")) "https://$url"
                        else "https://www.google.com/search?q=${Uri.encode(url)}"
                    }
                    // Prefer HTTPS
                    if (url.startsWith("http://")) url = url.replaceFirst("http://", "https://")
                    web.loadUrl(url)
                }
                true
            } else false
        }

        box.addView(urlBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // Navigation + controls
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        fun navBtn(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label; textSize = 14f; isAllCaps = false; setTextColor(white)
            background = rounded(panel, 12); setOnClickListener { action() }
        }

        nav.addView(navBtn("←") { if (web.canGoBack()) web.goBack() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        nav.addView(navBtn("→") { if (web.canGoForward()) web.goForward() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        nav.addView(navBtn("↻") { web.reload() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })

        // AdBlock toggle
        nav.addView(navBtn(if (adBlockEnabled) "🛡" else "○") {
            adBlockEnabled = !adBlockEnabled
            prefs.edit().putBoolean("adblock", adBlockEnabled).apply()
            status.text = if (adBlockEnabled) "🛡 Ad Block attivo" else "Ad Block disattivato"
            status.setTextColor(if (adBlockEnabled) accent else muted)
            Toast.makeText(this, if (adBlockEnabled) "Ad Block attivato" else "Ad Block disattivato", Toast.LENGTH_SHORT).show()
            web.reload()
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })

        nav.addView(navBtn("✕") {
            web.clearCache(true); web.clearHistory(); web.clearFormData()
            web.loadUrl("about:blank")
            adsBlockedCount = 0
            status.text = if (adBlockEnabled) "🛡 Ad Block attivo" else "Ad Block disattivato"
            Toast.makeText(this, "Cronologia e cache cancellate", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })

        box.addView(nav, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        box.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        web.loadUrl("about:blank")
    }

    // ==================== FILE CRYPTO ====================

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "Scegli file"), REQ_PICK_FILE)
    }

    private fun encryptAndStore(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.readBytes() ?: return
            val encrypted = encrypt(bytes, deriveKey())
            File(File(filesDir, "vault").apply { mkdirs() }, "file_" + System.currentTimeMillis() + ".enc").writeBytes(encrypted)
            Toast.makeText(this, "File aggiunto e criptato", Toast.LENGTH_SHORT).show()
            showVaultHome(1)
        } catch (e: Exception) {
            Toast.makeText(this, "Errore: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openEncryptedFile(file: File) {
        try {
            val decrypted = decrypt(file.readBytes(), deriveKey())
            val bmp = BitmapFactory.decodeByteArray(decrypted, 0, decrypted.size)
            if (bmp != null) showImagePreview(bmp)
            else Toast.makeText(this, "File decriptato (${decrypted.size} bytes)", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Errore decriptazione", Toast.LENGTH_SHORT).show()
        }
    }

    private fun viewImageFile(file: File) {
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return
        showImagePreview(bmp)
    }

    private fun showImagePreview(bmp: Bitmap) {
        val box = baseScreen()
        box.addView(ImageView(this).apply {
            setImageBitmap(bmp); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        box.addView(Button(this).apply {
            text = "Indietro"; isAllCaps = false; setTextColor(white)
            background = rounded(panel, 14); setOnClickListener { showVaultHome(1) }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
    }

    private fun deriveKey(): SecretKey {
        val pin = prefs.getString("pin", "default") ?: "default"
        return SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(pin.toByteArray()), "AES")
    }

    private fun encrypt(data: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(data)
    }

    private fun decrypt(data: ByteArray, key: SecretKey): ByteArray {
        val iv = data.copyOfRange(0, 12)
        val encrypted = data.copyOfRange(12, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    // ==================== HELPERS ====================

    private data class AppEntry(val label: String, val packageName: String)

    @Suppress("DEPRECATION")
    private fun installedApps(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { AppEntry(it.loadLabel(packageManager).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun showAppPicker() {
        val apps = installedApps()
        val labels = apps.map { it.label }.toTypedArray()
        val checked = BooleanArray(apps.size) { selected.contains(apps[it].packageName) }
        AlertDialog.Builder(this)
            .setTitle("Aggiungi app al vault")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val pkg = apps[which].packageName
                if (isChecked) selected.add(pkg) else selected.remove(pkg)
            }
            .setPositiveButton("Salva") { _, _ ->
                prefs.edit().putStringSet("selected_apps", selected.toSet()).apply()
                showVaultHome(0)
            }
            .setNegativeButton("Annulla") { _, _ -> showVaultHome(0) }
            .show()
    }

    private fun launchApp(pkg: String) {
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) Toast.makeText(this, "App non avviabile", Toast.LENGTH_SHORT).show()
        else try { startActivity(launch) } catch (_: Exception) {
            Toast.makeText(this, "Impossibile aprire", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSettings() {
        val options = arrayOf(
            "Cambia PIN",
            "Tentativi falliti: $failedAttempts",
            "Cancella foto intrusi",
            if (adBlockEnabled) "Disattiva Ad Block" else "Attiva Ad Block"
        )
        AlertDialog.Builder(this)
            .setTitle("Impostazioni")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val input = EditText(this).apply {
                            hint = "Nuovo PIN (4–8 cifre)"
                            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                        }
                        AlertDialog.Builder(this)
                            .setTitle("Cambia PIN")
                            .setView(input)
                            .setPositiveButton("Salva") { _, _ ->
                                val pin = input.text.toString()
                                if (pin.length in 4..8) {
                                    prefs.edit().putString("pin", pin).apply()
                                    Toast.makeText(this, "PIN aggiornato", Toast.LENGTH_SHORT).show()
                                } else Toast.makeText(this, "4–8 cifre", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("Annulla", null).show()
                    }
                    1 -> Toast.makeText(this, "Tentativi falliti: $failedAttempts", Toast.LENGTH_LONG).show()
                    2 -> {
                        File(filesDir, "intruders").listFiles()?.forEach { it.delete() }
                        Toast.makeText(this, "Foto intrusi cancellate", Toast.LENGTH_SHORT).show()
                        showVaultHome(1)
                    }
                    3 -> {
                        adBlockEnabled = !adBlockEnabled
                        prefs.edit().putBoolean("adblock", adBlockEnabled).apply()
                        Toast.makeText(this, if (adBlockEnabled) "Ad Block attivo" else "Ad Block disattivato", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Chiudi", null).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_FILE && resultCode == RESULT_OK) data?.data?.let { encryptAndStore(it) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            takeIntruderPhoto()
        }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
}
