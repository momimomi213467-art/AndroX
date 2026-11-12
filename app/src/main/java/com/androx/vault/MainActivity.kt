package com.androx.vault

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.math.BigDecimal
import java.math.RoundingMode

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("androx_private", MODE_PRIVATE) }
    private val selected = linkedSetOf<String>()

    // Calculator state
    private var expression = ""
    private var currentInput = "0"
    private var lastWasOperator = false
    private var justEvaluated = false

    private lateinit var root: LinearLayout
    private lateinit var displayExpression: TextView
    private lateinit var displayResult: TextView

    // Colors - clean dark calculator look
    private val bg = Color.parseColor("#0D1110")
    private val panel = Color.parseColor("#1A221C")
    private val accent = Color.parseColor("#2EE56D")
    private val muted = Color.parseColor("#8B9A8E")
    private val white = Color.parseColor("#E8F0EA")
    private val keyNumber = Color.parseColor("#252E27")
    private val keyOperator = Color.parseColor("#1E3A28")
    private val keyClear = Color.parseColor("#3A2A1E")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected.addAll(prefs.getStringSet("selected_apps", emptySet()) ?: emptySet())
        showCalculator()
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

    // ==================== CALCULATOR UI ====================

    private fun showCalculator() {
        expression = ""
        currentInput = "0"
        lastWasOperator = false
        justEvaluated = false

        val box = baseScreen()

        // Top label (subtle)
        val top = TextView(this).apply {
            text = "Calcolatrice"
            textSize = 14f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(12))
        }
        box.addView(top, matchWrap())

        // Expression (small, above)
        displayExpression = TextView(this).apply {
            text = ""
            textSize = 16f
            setTextColor(muted)
            gravity = Gravity.END
            setPadding(dp(12), dp(8), dp(12), 0)
            maxLines = 1
        }
        box.addView(displayExpression, matchWrap())

        // Main result display
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

        // Spacer
        box.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        // Button grid
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
            // Fill empty space on last row if needed
            if (rowKeys.size == 3) {
                val spacer = View(this)
                row.addView(spacer, LinearLayout.LayoutParams(0, dp(72), 1f).apply {
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
                    // replace last operator
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
                // Check if current input is the PIN → open vault
                val pin = prefs.getString("pin", null)
                if (pin != null && currentInput == pin && !justEvaluated) {
                    showVault()
                    return
                }
                // First time setup: if no PIN yet and input is 4-8 digits, set it
                if (pin == null && currentInput.length in 4..8 && currentInput.all { it.isDigit() }) {
                    prefs.edit().putString("pin", currentInput).apply()
                    Toast.makeText(this, "PIN impostato", Toast.LENGTH_SHORT).show()
                    showVault()
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
            else -> { // digit
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
        displayExpression.text = expression.replace("×", "×").replace("÷", "÷").replace("−", "−")
        displayResult.text = currentInput
        // Shrink text if long
        displayResult.textSize = when {
            currentInput.length > 10 -> 32f
            currentInput.length > 7 -> 40f
            else -> 48f
        }
    }

    private fun evaluate(expr: String): BigDecimal {
        // Simple left-to-right evaluator for + − × ÷
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

        // First pass: * and /
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

        // Second pass: + and -
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

    // ==================== VAULT ====================

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

    private fun showVault() {
        val box = baseScreen()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Le mie applicazioni"
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

        val subtitle = TextView(this).apply {
            text = "Accesso rapido alle app nascoste"
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(6), 0, dp(18))
        }
        box.addView(subtitle, matchWrap())

        val add = Button(this).apply {
            text = "+  Aggiungi applicazioni"
            textSize = 15f
            isAllCaps = false
            setTextColor(accent)
            background = rounded(Color.parseColor("#1E3A28"), 16)
            setOnClickListener { showAppPicker() }
        }
        box.addView(add, LinearLayout.LayoutParams(-1, dp(52)))

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(8))
        }

        val apps = installedApps().filter { selected.contains(it.packageName) }
        if (apps.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Nessuna app nel vault.\nTocca “Aggiungi applicazioni” per iniziare."
                textSize = 15f
                setTextColor(muted)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(40), dp(16), dp(40))
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
                gravity = Gravity.CENTER
            }
            item.addView(open, LinearLayout.LayoutParams(dp(70), dp(44)))
            list.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }

        scroll.addView(list)
        box.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val lock = Button(this).apply {
            text = "Blocca vault"
            isAllCaps = false
            setTextColor(muted)
            background = rounded(panel, 14)
            setOnClickListener { showCalculator() }
        }
        box.addView(lock, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
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
                showVault()
            }
            .setNegativeButton("Annulla") { _, _ -> showVault() }
            .show()
    }

    private fun launchApp(pkg: String) {
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            Toast.makeText(this, "App non avviabile", Toast.LENGTH_SHORT).show()
        } else {
            try {
                startActivity(launch)
            } catch (_: Exception) {
                Toast.makeText(this, "Impossibile aprire questa app", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSettings() {
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
                } else {
                    Toast.makeText(this, "Inserisci 4–8 cifre", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // ==================== HELPERS ====================

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
}
