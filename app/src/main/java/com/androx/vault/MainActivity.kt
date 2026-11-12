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
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("androx_private", MODE_PRIVATE) }
    private val selected = linkedSetOf<String>()
    private var enteredPin = ""
    private var setupMode = false
    private lateinit var root: LinearLayout
    private lateinit var pinDisplay: TextView

    private val bg = Color.rgb(7, 10, 8)
    private val panel = Color.rgb(24, 30, 25)
    private val accent = Color.rgb(50, 220, 105)
    private val muted = Color.rgb(142, 157, 145)
    private val white = Color.rgb(235, 245, 237)
    private val keyNumber = Color.rgb(34, 42, 35)
    private val keyAction = Color.rgb(24, 75, 42)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected.addAll(prefs.getStringSet("selected_apps", emptySet()) ?: emptySet())
        showCalculator()
    }

    private fun baseScreen(): LinearLayout {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(20))
            setBackgroundColor(bg)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        setContentView(root)
        return root
    }

    private fun showCalculator() {
        enteredPin = ""
        setupMode = !prefs.contains("pin")
        val box = baseScreen()
        val top = TextView(this).apply {
            text = "CALCOLATRICE"
            textSize = 13f
            letterSpacing = .24f
            setTextColor(muted)
            gravity = Gravity.CENTER
        }
        box.addView(top, matchWrap())
        val heading = TextView(this).apply {
            text = "Calcolatrice"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
            gravity = Gravity.CENTER
        }
        val hp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(42) }
        box.addView(heading, hp)

        pinDisplay = TextView(this).apply {
            text = "0"
            textSize = 48f
            setTextColor(white)
            gravity = Gravity.CENTER
            setPadding(0, dp(26), 0, dp(26))
        }
        box.addView(pinDisplay, LinearLayout.LayoutParams(-1, dp(100)))

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val keys = listOf(listOf("7", "8", "9"), listOf("4", "5", "6"), listOf("1", "2", "3"), listOf("⌫", "0", "✓"))
        keys.forEach { rowKeys ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
            rowKeys.forEach { key ->
                val button = Button(this).apply {
                    text = if (key == "✓") "=" else key
                    textSize = 23f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (key == "✓") bg else white)
                    isAllCaps = false
                    stateListAnimator = null
                    minHeight = 0
                    minimumHeight = 0
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(0, 0, 0, 0)
                    background = rounded(if (key == "✓") accent else keyNumber, 18)
                    elevation = dp(1).toFloat()
                    setOnClickListener { onKey(key) }
                }
                val lp = LinearLayout.LayoutParams(0, dp(70), 1f).apply {
                    setMargins(dp(5), dp(5), dp(5), dp(5))
                }
                row.addView(button, lp)
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        val gridLp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) }
        box.addView(grid, gridLp)

        val hint = TextView(this).apply {
            text = " "
            textSize = 1f
            setTextColor(bg)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        box.addView(hint, matchWrap())
    }

    private fun onKey(key: String) {
        if (key == "⌫") {
            if (enteredPin.isNotEmpty()) enteredPin = enteredPin.dropLast(1)
        } else if (key == "✓") {
            if (setupMode) {
                if (enteredPin.length in 4..8) {
                    prefs.edit().putString("pin", enteredPin).apply()
                    Toast.makeText(this, "PIN creato", Toast.LENGTH_SHORT).show()
                    showVault()
                } else {
                    Toast.makeText(this, "Il PIN deve avere 4–8 cifre", Toast.LENGTH_SHORT).show()
                }
            } else {
                if (enteredPin == prefs.getString("pin", "")) showVault()
                else {
                    enteredPin = ""
                    Toast.makeText(this, "PIN non corretto", Toast.LENGTH_SHORT).show()
                }
            }
            return
        } else if (enteredPin.length < 8) enteredPin += key

        pinDisplay.text = if (enteredPin.isEmpty()) "0" else "•".repeat(enteredPin.length)
        pinDisplay.textSize = 34f
    }

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
            textSize = 27f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val settings = Button(this).apply {
            text = "⋮"
            textSize = 24f
            setTextColor(white)
            isAllCaps = false
            background = rounded(panel, 18)
            setOnClickListener { showSettings() }
        }
        header.addView(settings, LinearLayout.LayoutParams(dp(52), dp(48)))
        box.addView(header, matchWrap())

        val subtitle = TextView(this).apply {
            text = "Accesso rapido"
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(8), 0, dp(22))
        }
        box.addView(subtitle, matchWrap())

        val add = Button(this).apply {
            text = "+  Aggiungi applicazioni"
            textSize = 15f
            isAllCaps = false
            setTextColor(accent)
            background = rounded(Color.rgb(24, 75, 42), 18)
            setOnClickListener { showAppPicker() }
        }
        box.addView(add, LinearLayout.LayoutParams(-1, dp(54)))

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, dp(12))
        }
        val apps = installedApps().filter { selected.contains(it.packageName) }
        if (apps.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Nessuna app nel vault. Tocca “Aggiungi applicazioni” per iniziare."
                textSize = 15f
                setTextColor(muted)
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(42), dp(18), dp(42))
            }
            list.addView(empty, matchWrap())
        }
        apps.forEach { app ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(panel, 18)
                setPadding(dp(15), dp(10), dp(10), dp(10))
                setOnClickListener { launchApp(app.packageName) }
            }
            val label = TextView(this).apply {
                text = app.label
                textSize = 16f
                setTextColor(white)
            }
            item.addView(label, LinearLayout.LayoutParams(0, dp(48), 1f))
            val open = TextView(this).apply {
                text = "APRI  ›"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(accent)
                gravity = Gravity.CENTER
            }
            item.addView(open, LinearLayout.LayoutParams(dp(72), dp(48)))
            list.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        scroll.addView(list)
        box.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val lock = Button(this).apply {
            text = "Blocca vault"
            isAllCaps = false
            setTextColor(muted)
            background = rounded(panel, 16)
            setOnClickListener { showCalculator() }
        }
        box.addView(lock, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })
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
        if (launch == null) Toast.makeText(this, "App non avviabile", Toast.LENGTH_SHORT).show()
        else try { startActivity(launch) } catch (_: Exception) {
            Toast.makeText(this, "Impossibile aprire questa app", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSettings() {
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
                } else Toast.makeText(this, "Inserisci 4–8 cifre", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
}
