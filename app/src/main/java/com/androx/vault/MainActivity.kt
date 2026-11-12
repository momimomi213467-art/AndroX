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
import android.graphics.SurfaceTexture
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
import android.view.Surface
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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

    // Calculator state
    private var expression = ""
    private var currentInput = "0"
    private var lastWasOperator = false
    private var justEvaluated = false
    private var failedAttempts = 0

    private lateinit var root: LinearLayout
    private lateinit var displayExpression: TextView
    private lateinit var displayResult: TextView

    // Colors
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
    private val REQ_STORAGE = 1003

    // Camera for intruder
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected.addAll(prefs.getStringSet("selected_apps", emptySet()) ?: emptySet())
        failedAttempts = prefs.getInt("failed_attempts", 0)
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
                val btn = createKeyButton(key)
                val lp = LinearLayout.LayoutParams(0, dp(72), weight).apply {
                    setMargins(dp(5), dp(5), dp(5), dp(5))
                }
                row.addView(btn, lp)
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
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)

            when {
                key == "=" -> {
                    setTextColor(bg)
                    background = rounded(accent, 20)
                }
                isOperator -> {
                    setTextColor(accent)
                    background = rounded(keyOperator, 20)
                }
                isClear -> {
                    setTextColor(Color.parseColor("#E8A87C"))
                    background = rounded(keyClear, 20)
                }
                else -> {
                    setTextColor(white)
                    background = rounded(keyNumber, 20)
                }
            }
            setOnClickListener { onCalculatorKey(key) }
        }
    }

    private fun onCalculatorKey(key: String) {
        when (key) {
            "C" -> {
                expression = ""
                currentInput = "0"
                lastWasOperator = false
                justEvaluated = false
                updateDisplay()
            }
            "⌫" -> {
                if (justEvaluated) {
                    expression = ""
                    currentInput = "0"
                    justEvaluated = false
                } else if (currentInput.length > 1) {
                    currentInput = currentInput.dropLast(1)
                } else {
                    currentInput = "0"
                }
                lastWasOperator = false
                updateDisplay()
            }
            "%" -> {
                try {
                    val v = currentInput.toBigDecimal().divide(BigDecimal(100), 10, RoundingMode.HALF_UP)
                    currentInput = formatNumber(v)
                    justEvaluated = false
                    updateDisplay()
                } catch (_: Exception) {}
            }
            "÷", "×", "−", "+" -> {
                if (justEvaluated) {
                    expression = currentInput
                    justEvaluated = false
                } else if (!lastWasOperator) {
                    expression += currentInput
                } else if (expression.isNotEmpty()) {
                    expression = expression.dropLast(1)
                }
                expression += key
                lastWasOperator = true
                currentInput = "0"
                updateDisplay()
            }
            "." -> {
                if (justEvaluated) {
                    currentInput = "0."
                    expression = ""
                    justEvaluated = false
                } else if (!currentInput.contains(".")) {
                    currentInput += "."
                }
                lastWasOperator = false
                updateDisplay()
            }
            "=" -> {
                val pin = prefs.getString("pin", null)
                if (pin != null && currentInput == pin && !justEvaluated) {
                    failedAttempts = 0
                    prefs.edit().putInt("failed_attempts", 0).apply()
                    showVaultHome()
                    return
                }
                if (pin == null && currentInput.length in 4..8 && currentInput.all { it.isDigit() }) {
                    prefs.edit().putString("pin", currentInput).apply()
                    Toast.makeText(this, "PIN impostato", Toast.LENGTH_SHORT).show()
                    showVaultHome()
                    return
                }

                // Wrong PIN attempt
                if (pin != null && currentInput.length in 4..8 && currentInput.all { it.isDigit() } && currentInput != pin) {
                    failedAttempts++
                    prefs.edit().putInt("failed_attempts", failedAttempts).apply()
                    if (failedAttempts >= 2) {
                        takeIntruderPhoto()
                    }
                    Toast.makeText(this, "PIN non corretto", Toast.LENGTH_SHORT).show()
                    currentInput = "0"
                    updateDisplay()
                    return
                }

                // Normal calculation
                try {
                    if (!lastWasOperator) expression += currentInput
                    val result = evaluate(expression)
                    currentInput = formatNumber(result)
                    expression = ""
                    justEvaluated = true
                    lastWasOperator = false
                    updateDisplay()
                } catch (_: Exception) {
                    currentInput = "Errore"
                    expression = ""
                    justEvaluated = true
                    updateDisplay()
                }
            }
            else -> {
                if (justEvaluated) {
                    currentInput = key
                    expression = ""
                    justEvaluated = false
                } else if (lastWasOperator || currentInput == "0") {
                    currentInput = key
                } else if (currentInput.length < 12) {
                    currentInput += key
                }
                lastWasOperator = false
                updateDisplay()
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
                s[i] in "+-*/" -> {
                    tokens.add(s[i].toString())
                    i++
                }
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
                    val op = tokens[j + 1]
                    val next = tokens[j + 2].toBigDecimal()
                    acc = if (op == "*") acc.multiply(next) else acc.divide(next, 10, RoundingMode.HALF_UP)
                    j += 2
                }
                pass1.add(acc.toPlainString())
                j++
            } else {
                pass1.add(tokens[j])
                j++
            }
        }

        var result = pass1[0].toBigDecimal()
        var k = 1
        while (k < pass1.size) {
            val op = pass1[k]
            val next = pass1[k + 1].toBigDecimal()
            result = if (op == "+") result.add(next) else result.subtract(next)
            k += 2
        }
        return result
    }

    private fun formatNumber(n: BigDecimal): String {
        val stripped = n.stripTrailingZeros()
        return if (stripped.scale() <= 0) stripped.toBigInteger().toString()
        else stripped.toPlainString()
    }

    // ==================== INTRUDER SELFIE ====================

    private fun takeIntruderPhoto() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        try {
            startBackgroundThread()
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            val frontId = manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: return

            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()
                    saveIntruderPhoto(bytes)
                    closeCamera()
                }, backgroundHandler)
            }

            manager.openCamera(frontId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val surface = imageReader!!.surface
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(surface)
                        }.build()
                        camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                session.capture(request, null, backgroundHandler)
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                closeCamera()
                            }
                        }, backgroundHandler)
                    } catch (_: Exception) {
                        closeCamera()
                    }
                }
                override fun onDisconnected(camera: CameraDevice) { closeCamera() }
                override fun onError(camera: CameraDevice, error: Int) { closeCamera() }
            }, backgroundHandler)
        } catch (_: Exception) {
            // silently fail if camera not available
        }
    }

    private fun saveIntruderPhoto(jpeg: ByteArray) {
        try {
            val dir = File(filesDir, "intruders").apply { mkdirs() }
            val name = "intruder_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
            File(dir, name).writeBytes(jpeg)
            runOnUiThread {
                // optional silent log
            }
        } catch (_: Exception) {}
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBg").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (_: Exception) {}
        backgroundThread = null
        backgroundHandler = null
    }

    private fun closeCamera() {
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
    }

    // ==================== VAULT HOME (tabs) ====================

    private fun showVaultHome(tab: Int = 0) {
        val box = baseScreen()

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Vault"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))

        val settings = Button(this).apply {
            text = "⋮"
            textSize = 22f
            setTextColor(white)
            isAllCaps = false
            background = rounded(panel, 16)
            setOnClickListener { showSettings() }
        }
        header.addView(settings, LinearLayout.LayoutParams(dp(48), dp(44)))
        box.addView(header, matchWrap())

        // Tabs
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val tabNames = listOf("App", "File", "Browser")
        tabNames.forEachIndexed { index, name ->
            val t = Button(this).apply {
                text = name
                textSize = 14f
                isAllCaps = false
                setTextColor(if (index == tab) bg else white)
                background = rounded(if (index == tab) accent else panel, 14)
                setOnClickListener { showVaultHome(index) }
            }
            tabs.addView(t, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            })
        }
        box.addView(tabs, matchWrap())

        when (tab) {
            0 -> showAppsContent(box)
            1 -> showFilesContent(box)
            2 -> showBrowserContent(box)
        }

        val lock = Button(this).apply {
            text = "Blocca vault"
            isAllCaps = false
            setTextColor(muted)
            background = rounded(panel, 14)
            setOnClickListener { showCalculator() }
        }
        box.addView(lock, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
    }

    // ==================== APPS TAB ====================

    private fun showAppsContent(box: LinearLayout) {
        val add = Button(this).apply {
            text = "+  Aggiungi applicazioni"
            textSize = 15f
            isAllCaps = false
            setTextColor(accent)
            background = rounded(Color.parseColor("#1E3A28"), 16)
            setOnClickListener { showAppPicker() }
        }
        box.addView(add, LinearLayout.LayoutParams(-1, dp(50)))

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }

        val apps = installedApps().filter { selected.contains(it.packageName) }
        if (apps.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Nessuna app nel vault."
                textSize = 15f
                setTextColor(muted)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(36), dp(16), dp(36))
            }
            list.addView(empty, matchWrap())
        }

        apps.forEach { app ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(panel, 16)
                setPadding(dp(16), dp(12), dp(12), dp(12))
                setOnClickListener { launchApp(app.packageName) }
            }
            val label = TextView(this).apply {
                text = app.label
                textSize = 16f
                setTextColor(white)
            }
            item.addView(label, LinearLayout.LayoutParams(0, dp(44), 1f))
            val open = TextView(this).apply {
                text = "APRI ›"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(accent)
            }
            item.addView(open, LinearLayout.LayoutParams(dp(70), dp(44)))
            list.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }

        scroll.addView(list)
        box.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    // ==================== FILE VAULT ====================

    private fun showFilesContent(box: LinearLayout) {
        val add = Button(this).apply {
            text = "+  Aggiungi foto / video"
            textSize = 15f
            isAllCaps = false
            setTextColor(accent)
            background = rounded(Color.parseColor("#1E3A28"), 16)
            setOnClickListener { pickFile() }
        }
        box.addView(add, LinearLayout.LayoutParams(-1, dp(50)))

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }

        val vaultDir = File(filesDir, "vault").apply { mkdirs() }
        val files = vaultDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

        if (files.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Nessun file nel vault.\nI file vengono criptati con AES-GCM."
                textSize = 15f
                setTextColor(muted)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(36), dp(16), dp(36))
            }
            list.addView(empty, matchWrap())
        }

        files.forEach { f ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(panel, 16)
                setPadding(dp(14), dp(12), dp(10), dp(12))
            }
            val name = TextView(this).apply {
                text = f.nameWithoutExtension.take(28)
                textSize = 14f
                setTextColor(white)
            }
            item.addView(name, LinearLayout.LayoutParams(0, -2, 1f))

            val openBtn = TextView(this).apply {
                text = "Apri"
                textSize = 13f
                setTextColor(accent)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { openEncryptedFile(f) }
            }
            item.addView(openBtn)

            val delBtn = TextView(this).apply {
                text = "✕"
                textSize = 16f
                setTextColor(danger)
                setPadding(dp(10), dp(6), dp(6), dp(6))
                setOnClickListener {
                    f.delete()
                    showVaultHome(1)
                }
            }
            item.addView(delBtn)

            list.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }

        // Intruders section
        val intruderDir = File(filesDir, "intruders")
        val intruders = intruderDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (intruders.isNotEmpty()) {
            val sep = TextView(this).apply {
                text = "Foto intrusi (${intruders.size})"
                textSize = 13f
                setTextColor(danger)
                setPadding(0, dp(18), 0, dp(8))
            }
            list.addView(sep, matchWrap())

            intruders.take(10).forEach { f ->
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(Color.parseColor("#2A1A1A"), 14)
                    setPadding(dp(12), dp(10), dp(10), dp(10))
                }
                val label = TextView(this).apply {
                    text = f.name
                    textSize = 13f
                    setTextColor(Color.parseColor("#E8A0A0"))
                }
                item.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
                val view = TextView(this).apply {
                    text = "Vedi"
                    textSize = 13f
                    setTextColor(danger)
                    setOnClickListener { viewImageFile(f) }
                }
                item.addView(view)
                list.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
        }

        scroll.addView(list)
        box.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

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
            val input = contentResolver.openInputStream(uri) ?: return
            val bytes = input.readBytes()
            input.close()

            val key = deriveKey()
            val encrypted = encrypt(bytes, key)

            val vaultDir = File(filesDir, "vault").apply { mkdirs() }
            val name = "file_" + System.currentTimeMillis() + ".enc"
            File(vaultDir, name).writeBytes(encrypted)

            Toast.makeText(this, "File aggiunto e criptato", Toast.LENGTH_SHORT).show()
            showVaultHome(1)
        } catch (e: Exception) {
            Toast.makeText(this, "Errore: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openEncryptedFile(file: File) {
        try {
            val encrypted = file.readBytes()
            val key = deriveKey()
            val decrypted = decrypt(encrypted, key)

            // Try to show as image, otherwise just confirm success
            val bmp = BitmapFactory.decodeByteArray(decrypted, 0, decrypted.size)
            if (bmp != null) {
                showImagePreview(bmp)
            } else {
                // Save temp and open with system viewer
                val tmp = File(cacheDir, "preview_tmp")
                tmp.writeBytes(decrypted)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.fromFile(tmp), "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this, "File decriptato (${decrypted.size} bytes)", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Errore decriptazione", Toast.LENGTH_SHORT).show()
        }
    }

    private fun viewImageFile(file: File) {
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return
        showImagePreview(bmp)
    }

    private fun showImagePreview(bmp: Bitmap) {
        val box = baseScreen()
        val img = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        box.addView(img, LinearLayout.LayoutParams(-1, 0, 1f))
        val back = Button(this).apply {
            text = "Indietro"
            isAllCaps = false
            setTextColor(white)
            background = rounded(panel, 14)
            setOnClickListener { showVaultHome(1) }
        }
        box.addView(back, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
    }

    // ==================== PRIVATE BROWSER ====================

    private fun showBrowserContent(box: LinearLayout) {
        val urlBar = EditText(this).apply {
            hint = "Cerca o inserisci URL"
            setTextColor(white)
            setHintTextColor(muted)
            setBackgroundColor(panel)
            setPadding(dp(14), dp(12), dp(14), dp(12)
            )
            textSize = 15f
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine()
        }

        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.saveFormData = false
            settings.setSupportZoom(true)
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            setBackgroundColor(bg)
        }

        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                var url = urlBar.text.toString().trim()
                if (url.isNotEmpty()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = if (url.contains(".") && !url.contains(" ")) "https://$url"
                        else "https://www.google.com/search?q=${Uri.encode(url)}"
                    }
                    web.loadUrl(url)
                }
                true
            } else false
        }

        box.addView(urlBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("←" to { if (web.canGoBack()) web.goBack() },
            "→" to { if (web.canGoForward()) web.goForward() },
            "↻" to { web.reload() },
            "✕" to {
                web.clearCache(true)
                web.clearHistory()
                web.clearFormData()
                web.loadUrl("about:blank")
                Toast.makeText(this, "Cronologia cancellata", Toast.LENGTH_SHORT).show()
            }).forEach { (label, action) ->
            val b = Button(this).apply {
                text = label
                textSize = 16f
                isAllCaps = false
                setTextColor(white)
                background = rounded(panel, 12)
                setOnClickListener { action() }
            }
            nav.addView(b, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
        }
        box.addView(nav, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        box.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))

        // Load blank initially
        web.loadUrl("about:blank")
    }

    // ==================== CRYPTO ====================

    private fun deriveKey(): SecretKey {
        val pin = prefs.getString("pin", "default") ?: "default"
        val hash = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        return SecretKeySpec(hash, "AES")
    }

    private fun encrypt(data: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(data)
        return iv + encrypted
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
            .map {
                AppEntry(it.loadLabel(packageManager).toString(), it.activityInfo.packageName)
            }
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
        val options = arrayOf("Cambia PIN", "Vedi tentativi falliti ($failedAttempts)", "Cancella foto intrusi")
        AlertDialog.Builder(this)
            .setTitle("Impostazioni")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val input = EditText(this).apply {
                            hint = "Nuovo PIN (4–8 cifre)"
                            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
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
                            .setNegativeButton("Annulla", null)
                            .show()
                    }
                    1 -> Toast.makeText(this, "Tentativi falliti: $failedAttempts", Toast.LENGTH_LONG).show()
                    2 -> {
                        File(filesDir, "intruders").listFiles()?.forEach { it.delete() }
                        Toast.makeText(this, "Foto intrusi cancellate", Toast.LENGTH_SHORT).show()
                        showVaultHome(1)
                    }
                }
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_FILE && resultCode == RESULT_OK) {
            data?.data?.let { encryptAndStore(it) }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            takeIntruderPhoto()
        }
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
}
