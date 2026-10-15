package com.farmbotingen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale

class FarmAccessibilityService : AccessibilityService() {
    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null
    private var popup: View? = null
    private var popupParams: WindowManager.LayoutParams? = null
    private var selectionView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var detectorRunning = false
    private var screenshotBusy = false
    private var automationRunning = false
    private var state = State.FARM
    private var revivalTappedForCurrentDeath = false
    private var lastDeathEvidenceAt = 0L
    private var lastActionAt = 0L
    private var lastAtk: Int? = null
    private var lastDef: Int? = null
    private var selectionKey: String? = null
    private var selectionLabel: String? = null
    private val prefs by lazy { getSharedPreferences("bosshunter_settings", MODE_PRIVATE) }
    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private enum class State { FARM, GOING_BUFF, WAITING_BUFF, GOING_FARM, DEAD_WAIT, BOSS_SCANNING, BOSS_MOVING, BOSS_ATTACKING }
    private var grayBossFrames = 0

    private val farmActionLoop = object : Runnable {
        override fun run() {
            if (!automationRunning) return
            if ((state == State.FARM || state == State.BOSS_ATTACKING) && System.currentTimeMillis() - lastActionAt >= FARM_ACTION_INTERVAL_MS) tapFarmSkills()
            handler.postDelayed(this, 2000)
        }
    }

    private val detectorLoop = object : Runnable {
        override fun run() {
            if (!detectorRunning) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !screenshotBusy && selectionView == null) captureAndInspect()
            handler.postDelayed(this, 1800)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (android.provider.Settings.canDrawOverlays(this)) showFloatingControls()
        detectorRunning = true
        handler.post(detectorLoop)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private fun captureAndInspect() {
        if (screenshotBusy || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        screenshotBusy = true
        try {
            takeScreenshot(0, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    var bitmap: Bitmap? = null
                    try {
                        bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        screenshot.hardwareBuffer.close()
                        if (bitmap == null) { screenshotBusy = false; return }
                        if (automationRunning && (state == State.BOSS_SCANNING || state == State.BOSS_ATTACKING)) inspectBossVisual(bitmap)
                        val image = InputImage.fromBitmap(bitmap, 0)
                        textRecognizer.process(image)
                            .addOnSuccessListener { result -> inspectResult(result, bitmap.width, bitmap.height) }
                            .addOnFailureListener { }
                            .addOnCompleteListener { bitmap.recycle(); screenshotBusy = false }
                    } catch (_: Exception) {
                        try { screenshot.hardwareBuffer.close() } catch (_: Exception) {}
                        bitmap?.recycle(); screenshotBusy = false
                    }
                }
                override fun onFailure(errorCode: Int) { screenshotBusy = false }
            })
        } catch (_: Exception) { screenshotBusy = false }
    }

    private fun inspectResult(result: Text, width: Int, height: Int) {
        val raw = result.text.lowercase(Locale.ROOT)
        val dead = raw.contains("character is defeated") ||
            (raw.contains("free revival") && raw.contains("respawn now")) ||
            (raw.contains("you have been defeated") && raw.contains("respawn"))
        val now = System.currentTimeMillis()
        if (dead) {
            lastDeathEvidenceAt = now
            if (!revivalTappedForCurrentDeath) {
                revivalTappedForCurrentDeath = true
                state = State.DEAD_WAIT
                handler.postDelayed({ confirmDeathThenTapRevival() }, 450)
            }
            return
        } else if (now - lastDeathEvidenceAt > 7000) revivalTappedForCurrentDeath = false

        // Only inspect the upper-left area; never guess values if labels are not clear.
        val topLeft = result.textBlocks.flatMap { block ->
            block.lines.filter { line ->
                val b = line.boundingBox
                b != null && b.left < width * 0.48 && b.top < height * 0.32
            }.map { it.text }
        }.joinToString(" ").lowercase(Locale.ROOT)

        val atk = extractStat(topLeft, listOf("atk", "attack"))
        val def = extractStat(topLeft, listOf("def", "defense", "defence"))
        // Do not reuse old readings: ATK and DEF must both be detected in this screenshot.
        lastAtk = atk
        lastDef = def
        bubble?.text = "B\\nA:${atk ?: "?"} D:${def ?: "?"}"

        val thresholdAtk = prefs.getInt(KEY_ATK_THRESHOLD, 0)
        val thresholdDef = prefs.getInt(KEY_DEF_THRESHOLD, 0)
        if (automationRunning && state == State.WAITING_BUFF) {
            val recovered = lastAtk != null && lastDef != null &&
                lastAtk!! >= thresholdAtk && lastDef!! >= thresholdDef
            if (recovered) {
                state = State.BOSS_SCANNING
                lastActionAt = 0L
                Toast.makeText(this, "ATK/DEF alt sınırları karşılandı; boss taramasına dönülüyor.", Toast.LENGTH_SHORT).show()
                beginBossScan()
            } else {
                bubble?.text = "B\\nBUFF?"
            }
        } else if (automationRunning && (state == State.FARM || state == State.BOSS_SCANNING || state == State.BOSS_ATTACKING)) {
            val buffNeeded = prefs.getBoolean(KEY_BUFF_ENABLED, false) &&
                lastAtk != null && lastDef != null &&
                (lastAtk!! < thresholdAtk || lastDef!! < thresholdDef)
            if (buffNeeded && now - lastActionAt > 5000) {
                lastActionAt = now
                val mapWasOpen = state == State.BOSS_SCANNING
                state = State.GOING_BUFF
                val goBuff = {
                    travelToTarget(KEY_BUFF, prefs) {
                        if (automationRunning) {
                            state = State.WAITING_BUFF
                            Toast.makeText(this, "Buff noktasında ATK/DEF toparlanması bekleniyor.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                if (mapWasOpen) tapConfigured(KEY_MAP_CLOSE) { goBuff() } else goBuff()
            }
        }
    }

    private fun beginBossScan() {
        if (!automationRunning && state != State.FARM) {
            // Manual scan from the floating panel is allowed when the service is connected.
        }
        val x = prefs.getInt("${KEY_MAP_OPEN}_X", -1)
        val y = prefs.getInt("${KEY_MAP_OPEN}_Y", -1)
        if (!valid(x, y)) {
            Toast.makeText(this, "Önce Harita açma konumunu kaydet.", Toast.LENGTH_LONG).show()
            return
        }
        state = State.BOSS_SCANNING
        tapNormalized(x, y) {
            lastActionAt = System.currentTimeMillis()
            Toast.makeText(this, "Harita açık: sarı boss işareti aranıyor.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun inspectBossVisual(source: Bitmap) {
        if (!automationRunning) return
        val bitmap = try { if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source } catch (_: Exception) { return }
        try {
            if (state == State.BOSS_SCANNING) {
                val target = findYellowTarget(bitmap)
                if (target != null) {
                    state = State.BOSS_MOVING
                    val nx = (target.first.toFloat() / bitmap.width * 1000).toInt().coerceIn(0, 1000)
                    val ny = (target.second.toFloat() / bitmap.height * 1000).toInt().coerceIn(0, 1000)
                    tapNormalized(nx, ny) {
                        handler.postDelayed({
                            if (automationRunning && state == State.BOSS_MOVING) {
                                tapConfigured(KEY_MAP_CLOSE) {
                                    state = State.BOSS_ATTACKING
                                    grayBossFrames = 0
                                    tapFarmSkills()
                                    Toast.makeText(this, "Golden Monster hedefi seçildi; oto-atak başladı.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }, 5500)
                    }
                    lastActionAt = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - lastActionAt > 3500) {
                    tapConfigured(KEY_RANDOM) {
                        // Random teleport keeps the map open; scan the new area on the next screenshot.
                        lastActionAt = System.currentTimeMillis()
                    }
                }
            } else if (state == State.BOSS_ATTACKING) {
                val marker = sampleBossMarker(bitmap)
                when (marker) {
                    1 -> grayBossFrames = 0 // yellow skull: boss alive
                    2 -> {
                        grayBossFrames++
                        if (grayBossFrames >= 3) {
                            grayBossFrames = 0
                            state = State.BOSS_SCANNING
                            lastActionAt = 0L
                            val mx = prefs.getInt("${KEY_MAP_OPEN}_X", -1)
                            val my = prefs.getInt("${KEY_MAP_OPEN}_Y", -1)
                            if (valid(mx, my)) handler.postDelayed({ if (automationRunning) tapNormalized(mx, my) }, 900)
                            Toast.makeText(this, "Boss simgesi 3 kez gri görüldü; sonraki hedef aranıyor.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    else -> grayBossFrames = 0
                }
            }
        } finally {
            if (bitmap !== source) bitmap.recycle()
        }
    }

    // Finds compact yellow clusters in the map area; rejects long text/UI streaks.
    // This is a color heuristic, not a trained game-specific detector, so uncertain screens are skipped.
    private fun findYellowTarget(bitmap: Bitmap): Pair<Int, Int>? {
        val stepX = (bitmap.width / 240).coerceAtLeast(1)
        val stepY = (bitmap.height / 160).coerceAtLeast(1)
        val left = (bitmap.width * 0.10f).toInt()
        val right = (bitmap.width * 0.86f).toInt()
        val top = (bitmap.height * 0.12f).toInt()
        val bottom = (bitmap.height * 0.80f).toInt()
        val cols = ((right - left) / stepX).coerceAtLeast(1)
        val rows = ((bottom - top) / stepY).coerceAtLeast(1)
        val mask = Array(rows) { BooleanArray(cols) }
        for (gy in 0 until rows) {
            val y = top + gy * stepY
            for (gx in 0 until cols) {
                val x = left + gx * stepX
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                mask[gy][gx] = r > 190 && g > 115 && g < 245 && b < 110 &&
                    r > g && g > b * 1.22
            }
        }
        val seen = Array(rows) { BooleanArray(cols) }
        var best: Pair<Int, Int>? = null
        var bestScore = 0
        for (sy in 1 until rows - 1) for (sx in 1 until cols - 1) {
            if (!mask[sy][sx] || seen[sy][sx]) continue
            val queue = java.util.ArrayDeque<Pair<Int, Int>>()
            val component = ArrayList<Pair<Int, Int>>()
            queue.add(Pair(sx, sy)); seen[sy][sx] = true
            while (queue.isNotEmpty()) {
                val p = queue.removeFirst()
                component.add(p)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = p.first + dx; val ny = p.second + dy
                    if (nx in 1 until cols - 1 && ny in 1 until rows - 1 &&
                        mask[ny][nx] && !seen[ny][nx]) {
                        seen[ny][nx] = true
                        queue.add(Pair(nx, ny))
                    }
                }
            }
            val minX = component.minOf { it.first }; val maxX = component.maxOf { it.first }
            val minY = component.minOf { it.second }; val maxY = component.maxOf { it.second }
            val w = maxX - minX + 1; val h = maxY - minY + 1
            val count = component.size
            // A small compact blob is more plausible than a long yellow label or UI strip.
            if (count in 3..45 && w in 2..10 && h in 2..10 &&
                w.toFloat() / h.toFloat() in 0.35f..2.8f && count > bestScore) {
                bestScore = count
                val centerX = left + ((minX + maxX) / 2) * stepX
                val centerY = top + ((minY + maxY) / 2) * stepY
                best = Pair(centerX.coerceIn(left, right), centerY.coerceIn(top, bottom))
            }
        }
        return if (bestScore >= 3) best else null
    }

    // 1 = yellow/alive, 2 = pale gray/dead, 0 = unclear.
    private fun sampleBossMarker(bitmap: Bitmap): Int {
        val nx = prefs.getInt("${KEY_BOSS_ICON}_X", -1)
        val ny = prefs.getInt("${KEY_BOSS_ICON}_Y", -1)
        if (!valid(nx, ny)) return 0
        val cx = (nx / 1000f * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val cy = (ny / 1000f * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        var yellow = 0; var gray = 0
        for (y in (cy - 10).coerceAtLeast(0)..(cy + 10).coerceAtMost(bitmap.height - 1) step 2) {
            for (x in (cx - 10).coerceAtLeast(0)..(cx + 10).coerceAtMost(bitmap.width - 1) step 2) {
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                if (r > 175 && g > 110 && b < 100 && r > g && g > b * 1.3) yellow++
                if (r in 95..215 && g in 95..215 && b in 95..215 && kotlin.math.abs(r-g) < 20 && kotlin.math.abs(g-b) < 20) gray++
            }
        }
        return if (yellow >= 4) 1 else if (gray >= 8) 2 else 0
    }

    private fun tapConfigured(key: String, done: (() -> Unit)? = null) {
        val x = prefs.getInt("${key}_X", -1)
        val y = prefs.getInt("${key}_Y", -1)
        if (!valid(x, y)) {
            Toast.makeText(this, "Eksik dokunuş noktası: $key", Toast.LENGTH_LONG).show()
            return
        }
        tapNormalized(x, y, done)
    }

    private fun extractStat(text: String, labels: List<String>): Int? {
        for (label in labels) {
            val regex = Regex("(?:^|\\s)${Regex.escape(label)}\\s*[:：]?\\s*([0-9][0-9,]*)", RegexOption.IGNORE_CASE)
            val match = regex.find(text) ?: continue
            return match.groupValues[1].replace(",", "").toIntOrNull()
        }
        return null
    }

    private fun confirmDeathThenTapRevival() {
        if (!automationRunning || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || screenshotBusy) {
            if (state == State.DEAD_WAIT && automationRunning) handler.postDelayed({ confirmDeathThenTapRevival() }, 1200)
            return
        }
        screenshotBusy = true
        try {
            takeScreenshot(0, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    var bitmap: Bitmap? = null
                    try {
                        bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        screenshot.hardwareBuffer.close()
                        if (bitmap == null) { screenshotBusy = false; return }
                        textRecognizer.process(InputImage.fromBitmap(bitmap, 0))
                            .addOnSuccessListener { result ->
                                val text = result.text.lowercase(Locale.ROOT)
                                val confirmed = text.contains("character is defeated") ||
                                    (text.contains("free revival") && text.contains("respawn now")) ||
                                    (text.contains("you have been defeated") && text.contains("respawn"))
                                if (confirmed) {
                                    val x = prefs.getInt("${KEY_REVIVAL}_X", -1)
                                    val y = prefs.getInt("${KEY_REVIVAL}_Y", -1)
                                    if (valid(x, y)) tapNormalized(x, y) {
                                        handler.postDelayed({
                                            if (automationRunning) {
                                                state = State.BOSS_SCANNING
                                                beginBossScan()
                                                Toast.makeText(this@FarmAccessibilityService, "Dirilme sonrası Golden Monster taramasına dönüldü.", Toast.LENGTH_LONG).show()
                                            }
                                        }, 6000)
                                    }
                                } else {
                                    revivalTappedForCurrentDeath = false
                                    if (automationRunning) { state = State.FARM; tapFarmSkills() }
                                }
                            }
                            .addOnFailureListener { revivalTappedForCurrentDeath = false }
                            .addOnCompleteListener { bitmap.recycle(); screenshotBusy = false }
                    } catch (_: Exception) {
                        try { screenshot.hardwareBuffer.close() } catch (_: Exception) {}
                        bitmap?.recycle(); screenshotBusy = false; revivalTappedForCurrentDeath = false
                    }
                }
                override fun onFailure(errorCode: Int) { screenshotBusy = false; revivalTappedForCurrentDeath = false }
            })
        } catch (_: Exception) { screenshotBusy = false; revivalTappedForCurrentDeath = false }
    }

    private fun tapFarmSkills() {
        if (!automationRunning) return
        val ax = prefs.getInt("${KEY_ATTACK}_X", -1); val ay = prefs.getInt("${KEY_ATTACK}_Y", -1)
        val sx = prefs.getInt("${KEY_SHIELD}_X", -1); val sy = prefs.getInt("${KEY_SHIELD}_Y", -1)
        if (valid(ax, ay)) tapNormalized(ax, ay) {
            handler.postDelayed({ if (automationRunning && valid(sx, sy)) tapNormalized(sx, sy) }, 300)
        }
        lastActionAt = System.currentTimeMillis()
    }

    fun startAutomation() {
        val required = mutableListOf(KEY_ATTACK, KEY_SHIELD, KEY_REVIVAL, KEY_MAP_OPEN, KEY_MAP_CLOSE, KEY_RANDOM, KEY_BOSS_ICON)
        if (prefs.getBoolean(KEY_BUFF_ENABLED, false)) {
            required.add(KEY_CHAT)
            required.add(KEY_CHAT_CLOSE)
            required.add(KEY_BUFF)
        }
        val missing = required.filter { !valid(prefs.getInt("${it}_X", -1), prefs.getInt("${it}_Y", -1)) }
        if (missing.isNotEmpty()) {
            Toast.makeText(this, "Eksik konumlar: " + missing.joinToString(), Toast.LENGTH_LONG).show(); return
        }
        automationRunning = true
        state = State.BOSS_SCANNING
        grayBossFrames = 0
        handler.removeCallbacks(farmActionLoop)
        handler.postDelayed(farmActionLoop, 2000)
        Toast.makeText(this, "Golden Monster avı başladı; harita taranıyor.", Toast.LENGTH_LONG).show()
        beginBossScan()
    }

    fun stopAutomation() {
        automationRunning = false
        handler.removeCallbacks(farmActionLoop)
        state = State.FARM
        grayBossFrames = 0
        Toast.makeText(this, "Golden Monster otomasyonu durduruldu.", Toast.LENGTH_SHORT).show()
    }

    fun showFloatingControls() {
        if (!android.provider.Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (bubble != null) return
        val density = resources.displayMetrics.density
        val icon = TextView(this).apply {
            text = "B"; textSize = 21f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; background = circle(Color.rgb(255, 190, 25)); elevation = 10f
            contentDescription = "Boss Hunter yüzen kontrolünü aç"
        }
        val params = WindowManager.LayoutParams((54*density).toInt(), (54*density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - 72*density).toInt()
            y = (resources.displayMetrics.heightPixels * .35f).toInt()
        }
        icon.setOnTouchListener(object : View.OnTouchListener {
            var dx0=0f; var dy0=0f; var sx=0; var sy=0; var moved=false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when(e.action) {
                    MotionEvent.ACTION_DOWN -> { dx0=e.rawX; dy0=e.rawY; sx=params.x; sy=params.y; moved=false; return true }
                    MotionEvent.ACTION_MOVE -> {
                        val dx=(e.rawX-dx0).toInt(); val dy=(e.rawY-dy0).toInt()
                        if (kotlin.math.abs(dx)>6 || kotlin.math.abs(dy)>6) moved=true
                        params.x=sx+dx; params.y=sy+dy
                        try { windowManager?.updateViewLayout(icon, params) } catch (_:Exception) {}
                        return true
                    }
                    MotionEvent.ACTION_UP -> { if(!moved) togglePopup(); return true }
                }
                return false
            }
        })
        try { windowManager?.addView(icon, params); bubble=icon } catch (_:Exception) { bubble=null }
    }

    private fun togglePopup() {
        if (popup != null) {
            try { windowManager?.removeView(popup) } catch (_:Exception) {}
            popup=null; popupParams=null; return
        }
        val density=resources.displayMetrics.density
        val container=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(16,14,16,14)
            background=rounded(Color.rgb(31,27,13),18,Color.rgb(255,196,35)); elevation=18f
        }
        val header=TextView(this).apply {
            text="BOSS HUNTER   ⋮⋮"; textSize=14f; typeface=Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(255,196,35)); setPadding(4,4,4,14)
        }
        container.addView(header)
        addPopupAction(container, if (automationRunning) "■  Avı durdur" else "▶  Golden Monster avını başlat", Color.rgb(150,108,12)) {
            if (automationRunning) stopAutomation() else startAutomation()
            try { windowManager?.removeView(container) } catch (_:Exception) {}; popup=null; popupParams=null
            togglePopup()
        }
        addPopupAction(container, "◎  Haritayı aç ve tara", Color.rgb(91,69,15)) { beginBossScan() }
        addPopupAction(container, "✦  Buff konumuna git", Color.rgb(91,69,15)) { travelToTarget(KEY_BUFF,prefs) }
        addPopupAction(container, "♥  Free Revival (test)", Color.rgb(110,73,22)) { tapRevival() }
        addPopupAction(container, "⚙  Ayarları aç", Color.rgb(91,69,15)) {
            startActivity(android.content.Intent(this, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        addPopupAction(container, "—  Paneli kapat", Color.rgb(59,49,21)) {
            try { windowManager?.removeView(container) } catch (_:Exception) {}; popup=null; popupParams=null
        }
        val params=WindowManager.LayoutParams((280*density).toInt(),WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.START; x=(resources.displayMetrics.widthPixels*.12f).toInt()
            y=(resources.displayMetrics.heightPixels*.25f).toInt()
        }
        try { windowManager?.addView(container,params); popup=container; popupParams=params }
        catch (_:Exception) { Toast.makeText(this,"Yüzen panel açılamadı.",Toast.LENGTH_LONG).show() }
    }

    private fun addPopupAction(parent: LinearLayout,label:String,color:Int,action:()->Unit) {
        val density=resources.displayMetrics.density
        val item=TextView(this).apply {
            text=label; textSize=14f; setTextColor(Color.WHITE); gravity=Gravity.CENTER_VERTICAL
            setPadding(12,13,12,13); background=rounded(color,11); isClickable=true
            setOnClickListener { action() }
        }
        parent.addView(item,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=(7*density).toInt() })
    }

    fun beginPointSelection(key:String,label:String) {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this,"Önce üstte gösterme iznini aç.",Toast.LENGTH_LONG).show(); return
        }
        selectionKey=key; selectionLabel=label
        val density=resources.displayMetrics.density
        val overlay=FrameLayout(this).apply { setBackgroundColor(Color.argb(35,0,0,0)) }
        val hint=TextView(this).apply {
            text="KONUM SEÇİMİ: $label\nİstenen noktaya bir kez dokun. İptal için geri tuşuna bas."
            textSize=16f; setTextColor(Color.WHITE); gravity=Gravity.CENTER
            setPadding(20,18,20,18); background=rounded(Color.rgb(31,27,13),14,Color.rgb(255,196,35))
        }
        overlay.addView(hint,FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply { setMargins(12,36,12,0) })
        val p=WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.START }
        overlay.setOnTouchListener { _, event ->
            if (event.action==MotionEvent.ACTION_UP) {
                val x=(event.rawX/resources.displayMetrics.widthPixels*1000).toInt().coerceIn(0,1000)
                val y=(event.rawY/resources.displayMetrics.heightPixels*1000).toInt().coerceIn(0,1000)
                val k=selectionKey
                if (k!=null) prefs.edit().putInt("${k}_X",x).putInt("${k}_Y",y).apply()
                removeSelection()
                Toast.makeText(this,"$selectionLabel konumu kaydedildi.",Toast.LENGTH_SHORT).show()
                return@setOnTouchListener true
            }
            true
        }
        try { windowManager?.addView(overlay,p); selectionView=overlay }
        catch (_:Exception) { selectionView=null; Toast.makeText(this,"Konum seçimi açılamadı.",Toast.LENGTH_LONG).show() }
    }

    private fun removeSelection() {
        try { selectionView?.let { windowManager?.removeView(it) } } catch (_:Exception) {}
        selectionView=null; selectionKey=null; selectionLabel=null
    }

    fun tapNormalized(x:Int,y:Int,done:(()->Unit)?=null) {
        val dm=resources.displayMetrics
        val px=(x.coerceIn(0,1000)/1000f*dm.widthPixels).coerceIn(0f,dm.widthPixels.toFloat()-1)
        val py=(y.coerceIn(0,1000)/1000f*dm.heightPixels).coerceIn(0f,dm.heightPixels.toFloat()-1)
        val path=Path().apply { moveTo(px,py) }
        val gesture=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,80)).build()
        dispatchGesture(gesture,object:GestureResultCallback(){
            override fun onCompleted(gestureDescription:GestureDescription?) { done?.invoke() }
            override fun onCancelled(gestureDescription:GestureDescription?) { done?.invoke() }
        },handler)
    }

    fun tapRevival() {
        val x=prefs.getInt("${KEY_REVIVAL}_X",-1); val y=prefs.getInt("${KEY_REVIVAL}_Y",-1)
        if(!valid(x,y)) { Toast.makeText(this,"Önce Free Revival konumunu seç.",Toast.LENGTH_LONG).show(); return }
        tapNormalized(x,y) { Toast.makeText(this,"Free Revival dokunuşu gönderildi.",Toast.LENGTH_SHORT).show() }
    }

    fun travelToTarget(prefix:String,prefs:SharedPreferences,done:(()->Unit)?=null) {
        val chatX=prefs.getInt("${KEY_CHAT}_X",-1); val chatY=prefs.getInt("${KEY_CHAT}_Y",-1)
        val targetX=prefs.getInt("${prefix}_X",-1); val targetY=prefs.getInt("${prefix}_Y",-1)
        val closeX=prefs.getInt("${KEY_CHAT_CLOSE}_X",-1); val closeY=prefs.getInt("${KEY_CHAT_CLOSE}_Y",-1)
        if(!valid(chatX,chatY)||!valid(targetX,targetY)||!valid(closeX,closeY)) {
            Toast.makeText(this,"Chat açma/kapatma ve hedef konumlarını seç. Hedef: ${prefix}",Toast.LENGTH_LONG).show(); return
        }
        tapNormalized(chatX,chatY) { handler.postDelayed({
            tapNormalized(targetX,targetY) { handler.postDelayed({
                tapNormalized(closeX,closeY) { handler.postDelayed({ done?.invoke() },900) }
            },450) }
        },500) }
    }

    private fun valid(x:Int,y:Int)=x in 0..1000 && y in 0..1000
    private fun circle(color:Int)=GradientDrawable().apply { shape=GradientDrawable.OVAL; setColor(color); setStroke((2*resources.displayMetrics.density).toInt(),Color.rgb(255,218,91)) }
    private fun rounded(color:Int,radius:Int,stroke:Int?=null)=GradientDrawable().apply {
        setColor(color); cornerRadius=radius*resources.displayMetrics.density
        if(stroke!=null) setStroke((1*resources.displayMetrics.density).toInt(),stroke)
    }

    override fun onDestroy() {
        detectorRunning=false; automationRunning=false; handler.removeCallbacks(detectorLoop)
        removeSelection()
        try { popup?.let { windowManager?.removeView(it) } } catch (_:Exception) {}
        try { bubble?.let { windowManager?.removeView(it) } } catch (_:Exception) {}
        textRecognizer.close(); if(instance===this) instance=null; super.onDestroy()
    }

    companion object {
        const val KEY_CHAT="chat"
        const val KEY_CHAT_CLOSE="chat_close"
        const val KEY_FARM="farm"
        const val KEY_BUFF="buff"
        const val KEY_ATTACK="attack"
        const val KEY_SHIELD="shield"
        const val KEY_REVIVAL="revival"
        const val KEY_BUFF_ENABLED="buff_enabled"
        const val KEY_ATK_THRESHOLD="atk_threshold"
        const val KEY_DEF_THRESHOLD="def_threshold"
        const val KEY_MAP_OPEN="map_open"
        const val KEY_MAP_CLOSE="map_close"
        const val KEY_RANDOM="random"
        const val KEY_BOSS_ICON="boss_icon"
        private const val BUFF_WAIT_MS=10000L
        private const val FARM_ACTION_INTERVAL_MS=15000L
        @Volatile private var instance:FarmAccessibilityService?=null
        fun isConnected()=instance!=null
        fun showFloatingControls(){ instance?.showFloatingControls() }
        fun beginPointSelection(key:String,label:String){ instance?.beginPointSelection(key,label) }
    }
}
