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
    private val prefs by lazy { getSharedPreferences("farmbotingen_settings", MODE_PRIVATE) }
    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private enum class State { FARM, GOING_BUFF, WAITING_BUFF, GOING_FARM, DEAD_WAIT }

    private val farmActionLoop = object : Runnable {
        override fun run() {
            if (!automationRunning) return
            if (state == State.FARM && System.currentTimeMillis() - lastActionAt >= FARM_ACTION_INTERVAL_MS) tapFarmSkills()
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
        bubble?.text = "F\\nA:${atk ?: "?"} D:${def ?: "?"}"

        if (automationRunning && state == State.FARM) {
            val thresholdAtk = prefs.getInt(KEY_ATK_THRESHOLD, 0)
            val thresholdDef = prefs.getInt(KEY_DEF_THRESHOLD, 0)
            val buffNeeded = prefs.getBoolean(KEY_BUFF_ENABLED, false) &&
                lastAtk != null && lastDef != null &&
                lastAtk!! < thresholdAtk && lastDef!! < thresholdDef
            if (buffNeeded && now - lastActionAt > 5000) {
                lastActionAt = now
                state = State.GOING_BUFF
                travelToTarget(KEY_BUFF, prefs) {
                    if (automationRunning) {
                        state = State.WAITING_BUFF
                        handler.postDelayed({
                            if (automationRunning && state == State.WAITING_BUFF) {
                                // Wait at the configured buff location, then read stats again.
                                state = State.GOING_FARM
                                travelToTarget(KEY_FARM, prefs) {
                                    if (automationRunning) { state = State.FARM; tapFarmSkills() }
                                }
                            }
                        }, BUFF_WAIT_MS)
                    }
                }
            }
        }
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
                                            if (automationRunning) travelToTarget(KEY_FARM, prefs) {
                                                state = State.FARM
                                                tapFarmSkills()
                                                Toast.makeText(this@FarmAccessibilityService, "Dirilme sonrası farm akışına dönüldü.", Toast.LENGTH_LONG).show()
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
        val required = mutableListOf(KEY_CHAT, KEY_CHAT_CLOSE, KEY_FARM, KEY_ATTACK, KEY_SHIELD, KEY_REVIVAL)
        if (prefs.getBoolean(KEY_BUFF_ENABLED, false)) required.add(KEY_BUFF)
        val missing = required.filter { !valid(prefs.getInt("${it}_X", -1), prefs.getInt("${it}_Y", -1)) }
        if (missing.isNotEmpty()) {
            Toast.makeText(this, "Eksik konumlar: " + missing.joinToString(), Toast.LENGTH_LONG).show(); return
        }
        automationRunning = true
        state = State.FARM
        handler.removeCallbacks(farmActionLoop)
        handler.postDelayed(farmActionLoop, 2000)
        travelToTarget(KEY_FARM, prefs) {
            if (automationRunning) {
                tapFarmSkills()
                Toast.makeText(this, "Otomasyon başladı. Durdur düğmesine basana kadar çalışır.", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun stopAutomation() {
        automationRunning = false
        handler.removeCallbacks(farmActionLoop)
        state = State.FARM
        Toast.makeText(this, "Otomasyon durduruldu.", Toast.LENGTH_SHORT).show()
    }

    fun showFloatingControls() {
        if (!android.provider.Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (bubble != null) return
        val density = resources.displayMetrics.density
        val icon = TextView(this).apply {
            text = "F"; textSize = 21f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; background = circle(Color.rgb(55, 125, 255)); elevation = 10f
            contentDescription = "FarmBotInGen yüzen kontrolünü aç"
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
            background=rounded(Color.rgb(20,29,46),18,Color.rgb(65,91,132)); elevation=18f
        }
        val header=TextView(this).apply {
            text="FARMBOTINGEN   ⋮⋮"; textSize=14f; typeface=Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(125,184,255)); setPadding(4,4,4,14)
        }
        container.addView(header)
        addPopupAction(container, if (automationRunning) "■  Otomasyonu durdur" else "▶  Otomasyonu başlat", Color.rgb(45,117,232)) {
            if (automationRunning) stopAutomation() else startAutomation()
            try { windowManager?.removeView(container) } catch (_:Exception) {}; popup=null; popupParams=null
            togglePopup()
        }
        addPopupAction(container, "▶  Farm konumuna git", Color.rgb(53,76,105)) { travelToTarget(KEY_FARM,prefs) }
        addPopupAction(container, "✦  Buff konumuna git", Color.rgb(53,76,105)) { travelToTarget(KEY_BUFF,prefs) }
        addPopupAction(container, "♥  Free Revival (test)", Color.rgb(144,87,45)) { tapRevival() }
        addPopupAction(container, "⚙  Ayarları aç", Color.rgb(53,76,105)) {
            startActivity(android.content.Intent(this, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        addPopupAction(container, "—  Paneli kapat", Color.rgb(40,51,70)) {
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
            setPadding(20,18,20,18); background=rounded(Color.rgb(20,29,46),14,Color.rgb(88,166,255))
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
    private fun circle(color:Int)=GradientDrawable().apply { shape=GradientDrawable.OVAL; setColor(color); setStroke((2*resources.displayMetrics.density).toInt(),Color.rgb(180,215,255)) }
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
        private const val BUFF_WAIT_MS=10000L
        private const val FARM_ACTION_INTERVAL_MS=15000L
        @Volatile private var instance:FarmAccessibilityService?=null
        fun isConnected()=instance!=null
        fun showFloatingControls(){ instance?.showFloatingControls() }
        fun beginPointSelection(key:String,label:String){ instance?.beginPointSelection(key,label) }
    }
}
