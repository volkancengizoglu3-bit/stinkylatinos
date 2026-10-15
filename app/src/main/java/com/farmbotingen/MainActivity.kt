package com.farmbotingen

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("bosshunter_settings", MODE_PRIVATE) }
    private val fields = linkedMapOf<String, EditText>()
    private lateinit var buffEnabled: CheckBox
    private val bg = Color.rgb(18, 16, 8)
    private val panel = Color.rgb(39, 33, 15)
    private val accent = Color.rgb(255, 196, 35)
    private val muted = Color.rgb(207, 194, 155)
    private val white = Color.rgb(241, 246, 255)

    private val points = listOf(
        "Chat açma" to FarmAccessibilityService.KEY_CHAT,
        "Chat kapatma" to FarmAccessibilityService.KEY_CHAT_CLOSE,
        "Farm hedefi" to FarmAccessibilityService.KEY_FARM,
        "Buff hedefi" to FarmAccessibilityService.KEY_BUFF,
        "Oto atak düğmesi" to FarmAccessibilityService.KEY_ATTACK,
        "Koruyucu kalkan" to FarmAccessibilityService.KEY_SHIELD,
        "Free Revival" to FarmAccessibilityService.KEY_REVIVAL,
        "Harita açma" to FarmAccessibilityService.KEY_MAP_OPEN,
        "Harita kapatma" to FarmAccessibilityService.KEY_MAP_CLOSE,
        "Random düğmesi" to FarmAccessibilityService.KEY_RANDOM,
        "Boss simgesi (minimap)" to FarmAccessibilityService.KEY_BOSS_ICON
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val scroll = ScrollView(this).apply { clipToPadding = false; setPadding(18, 16, 18, 24) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root); page.addView(scroll); setContentView(page)
        root.addView(TextView(this).apply {
            text = "MU IMMORTAL / GOLDEN MONSTER"; textSize = 12f; letterSpacing = .18f
            setTextColor(accent); setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Boss Hunter"; textSize = 30f; setTextColor(accent)
            setTypeface(null, Typeface.BOLD); setPadding(0, 5, 0, 3)
        })
        root.addView(bodyText("Konum seç düğmesine basıp oyun ekranında ilgili düğmeye dokun. Golden Monster taraması için harita, Random ve minimap boss simgesi noktalarını da kaydet."))

        val controls = card()
        controls.addView(sectionTitle("YÜZEN KONTROL"))
        controls.addView(actionButton("Yüzen kontrol iznini aç", accent) {
            if (!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            else if (!FarmAccessibilityService.isConnected()) {
                Toast.makeText(this, "Önce erişilebilirlik servisini etkinleştir.", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                FarmAccessibilityService.showFloatingControls()
                Toast.makeText(this, "Yüzen kontrol hazır.", Toast.LENGTH_SHORT).show()
            }
        })
        controls.addView(actionButton("Erişilebilirlik servisini aç", Color.rgb(91, 69, 15)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(controls, marginBottom())

        val coords = card()
        coords.addView(sectionTitle("01 / DOKUNUŞ NOKTALARI"))
        coords.addView(bodyText("Her konum için seç düğmesine basıp oyun ekranında ilgili noktaya dokun. Konumlar 0–1000 ölçeğinde kaydedilir."))
        points.forEach { (label, key) ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = label + if (pointSaved(key)) "  ✓" else "  •"
                tag = "label_$key"; textSize = 14f; setTextColor(white)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(actionButton("Konum seç", accent) {
                if (!Settings.canDrawOverlays(this) || !FarmAccessibilityService.isConnected()) {
                    Toast.makeText(this, "Önce yüzen kontrol ve erişilebilirlik izinlerini etkinleştir.", Toast.LENGTH_LONG).show()
                } else FarmAccessibilityService.beginPointSelection(key, label)
            })
            coords.addView(row)
        }
        root.addView(coords, marginBottom())

        val buff = card()
        buff.addView(sectionTitle("02 / BUFF AYARLARI"))
        buff.addView(bodyText("Buff açıksa ATK veya DEF kendi alt sınırının altına düştüğünde buff noktasına gider. Değerler okunamazsa karar vermez."))
        buffEnabled = CheckBox(this).apply {
            text = "Buff modunu etkinleştir"; setTextColor(white)
            buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            isChecked = prefs.getBoolean(FarmAccessibilityService.KEY_BUFF_ENABLED, false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(FarmAccessibilityService.KEY_BUFF_ENABLED, checked).apply()
                Toast.makeText(this@MainActivity, if (checked) "Buff protokolü açıldı." else "Buff kapalı: bot doğrudan farm yapacak.", Toast.LENGTH_SHORT).show()
            }
        }
        buff.addView(buffEnabled)
        addSingle(buff, "ATK alt sınırı", FarmAccessibilityService.KEY_ATK_THRESHOLD, "0")
        addSingle(buff, "DEF alt sınırı", FarmAccessibilityService.KEY_DEF_THRESHOLD, "0")
        root.addView(buff, marginBottom())

        val save = card()
        save.addView(actionButton("Ayarları kaydet", accent) { saveSettings() })
        save.addView(bodyText("Not: Oyun ekranındaki ATK/DEF yazım biçimi ve gerçek oyun içi dokunuşlar cihazda doğrulanmalıdır."))
        root.addView(save)
    }

    private fun pointSaved(key: String) = prefs.getInt("${key}_X", -1) in 0..1000 && prefs.getInt("${key}_Y", -1) in 0..1000

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this) && FarmAccessibilityService.isConnected()) FarmAccessibilityService.showFloatingControls()
    }

    private fun saveSettings() {
        val editor = prefs.edit()
        for ((key, field) in fields) {
            val value = field.text.toString().trim().toIntOrNull()
            if (value == null || value < 0) {
                Toast.makeText(this, "$key için 0 veya daha büyük bir sayı gir.", Toast.LENGTH_LONG).show(); return
            }
            editor.putInt(key, value)
        }
        editor.putBoolean(FarmAccessibilityService.KEY_BUFF_ENABLED, buffEnabled.isChecked)
        editor.apply()
        Toast.makeText(this, "Ayarlar kaydedildi.", Toast.LENGTH_SHORT).show()
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16); background = rounded(panel, 18)
    }
    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value; textSize = 13f; letterSpacing = .08f; setTextColor(accent)
        setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 8)
    }
    private fun bodyText(value: String) = TextView(this).apply {
        text = value; textSize = 13f; setTextColor(muted); setPadding(0, 0, 0, 12)
    }
    private fun actionButton(label: String, color: Int, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f; setTextColor(Color.WHITE)
        background = rounded(color, 12); setPadding(10, 5, 10, 5); setOnClickListener { action() }
    }
    private fun addSingle(root: LinearLayout, label: String, key: String, default: String) {
        root.addView(TextView(this).apply { text = label; textSize = 13f; setTextColor(muted); setPadding(0, 8, 0, 2) })
        val field = EditText(this).apply {
            hint = label; inputType = android.text.InputType.TYPE_CLASS_NUMBER; setSingleLine(true)
            textSize = 15f; setTextColor(white); setHintTextColor(Color.rgb(157, 139, 82))
            background = rounded(Color.rgb(54, 45, 19), 10); setPadding(12, 8, 12, 8)
            setText(prefs.getInt(key, default.toInt()).toString())
        }
        fields[key] = field; root.addView(field)
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius * resources.displayMetrics.density
    }
    private fun marginBottom() = LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = (14 * resources.displayMetrics.density).toInt()
    }
}
