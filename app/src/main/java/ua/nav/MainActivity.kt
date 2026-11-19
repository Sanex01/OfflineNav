package ua.nav

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.tilesource.FileBasedTileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.io.File

class MainActivity : Activity() {
    private lateinit var map: MapView
    private lateinit var status: TextView
    private lateinit var banner: TextView
    private lateinit var me: Marker
    private lateinit var destM: Marker
    private lateinit var routeLine: Polyline
    private lateinit var directLine: Polyline
    private lateinit var bMode: Button
    private lateinit var bVoice: Button
    private lateinit var bMock: Button
    private lateinit var base: File
    private val h = Handler(Looper.getMainLooper())
    private var follow = true
    private var shownPos: GeoPoint? = null
    private var shownRoute: List<GeoPoint>? = null
    private val ui = object : Runnable { override fun run() { refresh(); h.postDelayed(this, 500) } }
    private val svc get() = NavService.inst
    private val onlineSrc = org.osmdroid.tileprovider.tilesource.XYTileSource("CartoLight", 0, 19, 256, ".png", arrayOf("https://a.basemaps.cartocdn.com/light_all/", "https://b.basemaps.cartocdn.com/light_all/", "https://c.basemaps.cartocdn.com/light_all/"), "© OpenStreetMap contributors © CARTO")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        base = File(getExternalFilesDir(null), "osmdroid").also { it.mkdirs() }
        Configuration.getInstance().apply { userAgentValue = "OfflineNav/1.0 (+https://github.com/Sanex01/OfflineNav)"; osmdroidBasePath = base; osmdroidTileCache = File(base, "tiles") }

        map = MapView(this).apply {
            setMultiTouchControls(true); setTileSource(onlineSrc)
            controller.setZoom(15.0); controller.setCenter(GeoPoint(50.45, 30.52))
            setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) follow = false; false }
        }
        routeLine = Polyline(map).apply { outlinePaint.color = Color.BLUE; outlinePaint.strokeWidth = 10f; isEnabled = false }
        directLine = Polyline(map).apply { outlinePaint.color = Color.RED; outlinePaint.strokeWidth = 5f; isEnabled = false }
        destM = Marker(map).apply { setInfoWindow(null); isEnabled = false }
        me = Marker(map).apply { setInfoWindow(null); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); isEnabled = false }
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                svc?.changeDest(p); Toast.makeText(this@MainActivity, "Цель задана. Нажмите «Маршрут»", Toast.LENGTH_SHORT).show(); return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean { svc?.setManual(p); return true }
        }))
        map.overlays.addAll(listOf<Overlay>(routeLine, directLine, PoiOverlay { svc }, destM, me))

        status = TextView(this).apply { setTextColor(Color.WHITE); setBackgroundColor(0xCC000000.toInt()); setPadding(24, 24, 24, 24); textSize = 14f }
        banner = TextView(this).apply { setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setPadding(24, 16, 24, 16); visibility = View.GONE }
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; textSize = 12f; isAllCaps = false; setOnClickListener { f() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun row(vararg v: Button) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; v.forEach { addView(it) } }
        bMode = btn("Пешком") { svc?.let { it.carMode = !it.carMode } }
        bVoice = btn("Голос") { svc?.let { it.voice = !it.voice } }
        bMock = btn("Подмена") { svc?.let { it.setMock(!it.mockUser) } }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xCC000000.toInt())
            addView(row(
                btn("Карта") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, 2) },
                btn("Центр") { follow = true },
                btn("Маршрут") { svc?.buildRoute() },
                btn("Цель ✕") { svc?.changeDest(null) },
                btn("Камеры") { svc?.updatePois(true) }))
            addView(row(btn("OBD") { pickObd() }, bMode, bVoice, bMock,
                btn("Выход") { stopService(Intent(this@MainActivity, NavService::class.java)); finish() }))
        }
        setContentView(FrameLayout(this).apply {
            addView(map, FrameLayout.LayoutParams(-1, -1))
            addView(LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; addView(status); addView(banner) },
                FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        })
        base.listFiles()?.firstOrNull { it.extension.lowercase() in setOf("zip", "mbtiles", "sqlite", "gemf") }?.let { loadOffline(it) }

        val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 31) need.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) requestPermissions(need.toTypedArray(), 1)
    }

    private fun startNav() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            startForegroundService(Intent(this, NavService::class.java))
    }
    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) { startNav() }
    override fun onResume() { super.onResume(); map.onResume(); startNav(); h.post(ui) }
    override fun onPause() { super.onPause(); map.onPause(); h.removeCallbacksAndMessages(null) }

    private fun pickObd() {
        try {
            val ad = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
            val devs = ad.bondedDevices.toList()
            if (devs.isEmpty()) { Toast.makeText(this, "Сначала спарьте OBD-адаптер в настройках Bluetooth", Toast.LENGTH_LONG).show(); return }
            AlertDialog.Builder(this).setTitle("OBD-адаптер").setItems(devs.map { it.name ?: it.address }.toTypedArray()) { _, i -> svc?.connectObd(devs[i]) }
                .setNegativeButton("Отключить") { _, _ -> svc?.disconnectObd() }.show()
        } catch (e: Exception) { Toast.makeText(this, "Bluetooth: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun refresh() {
        val s = svc
        if (s == null) { status.text = "Запуск… (нужно разрешение на геолокацию)"; return }
        val p = s.pos; val d = s.dest; val rt = s.route
        me.isEnabled = p != null
        if (p != null) { me.position = p; if (follow && p !== shownPos) map.controller.setCenter(p); shownPos = p }
        if (rt !== shownRoute) { routeLine.setPoints(rt); routeLine.isEnabled = rt.isNotEmpty(); shownRoute = rt }
        destM.isEnabled = d != null; if (d != null) destM.position = d
        if (p != null && d != null && rt.isEmpty()) { directLine.setPoints(listOf(p, d)); directLine.isEnabled = true } else directLine.isEnabled = false
        map.invalidate()

        val mode = when {
            s.gpsGood() -> "GPS ±${s.lastGpsAcc.toInt()} м"
            s.suspicious -> "⚠ GPS подозрителен, счисление"
            else -> "Счисление " + if (s.carMode) "по скорости OBD" else "по шагам"
        }
        var t = "$mode\n${s.obdState} | курс ${s.heading.toInt()}° | без GPS ${s.distNoGps.toInt()} м"
        if (p == null) t += "\nДолгий тап по карте = «я здесь»"
        if (s.offRoute) t += "\n⚠ Вне маршрута"
        if (s.route.isNotEmpty()) t += "\nМаршрут: ${s.routeSrc}"
        val st = s.steps.getOrNull(s.stepIdx)
        if (st != null && p != null) t += "\n${st.text} через ${p.distanceToAsDouble(st.p).toInt()} м"
        else if (d != null && p != null) t += "\nЦель: ${p.distanceToAsDouble(d).toInt()} м, азимут ${p.bearingTo(d).toInt()}°"
        if (s.alertText.isEmpty()) banner.visibility = View.GONE else {
            banner.text = s.alertText
            banner.setBackgroundColor(if (s.alertType == "cam") 0xEED32F2F.toInt() else 0xEEF57C00.toInt())
            banner.visibility = View.VISIBLE
        }
        status.text = t
        bMode.text = if (s.carMode) "Авто" else "Пешком"
        bVoice.text = if (s.voice) "Голос ✓" else "Голос ✗"
        bMock.text = if (s.mockUser) "Подмена ✓" else "Подмена ✗"
    }

    private fun loadOffline(f: File) {
        try {
            val p = OfflineTileProvider(SimpleRegisterReceiver(this), arrayOf(f))
            map.tileProvider = p
            val src = p.archives.firstOrNull()?.tileSources?.firstOrNull()
            map.setTileSource(if (src != null) FileBasedTileSource.getSource(src) else onlineSrc)
            map.setUseDataConnection(false); map.invalidate()
            Toast.makeText(this, "Офлайн-карта: ${f.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "Ошибка карты: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rc: Int, res: Int, d: Intent?) {
        super.onActivityResult(rc, res, d)
        val uri = d?.data ?: return
        if (rc != 2 || res != RESULT_OK) return
        Thread {
            try {
                val name = contentResolver.query(uri, null, null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
                } ?: "map.zip"
                val f = File(base, File(name).name)
                contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                runOnUiThread { loadOffline(f) }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show() } }
        }.start()
    }
}

class PoiOverlay(private val svc: () -> NavService?) : Overlay() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val pt = Point()

    override fun draw(c: Canvas, m: MapView, shadow: Boolean) {
        if (shadow || m.zoomLevelDouble < 11.0) return
        val s = svc() ?: return
        val bb = m.boundingBox; val d = m.resources.displayMetrics.density
        txt.textSize = 11 * d
        for (o in s.pois) {
            if (!bb.contains(o.lat, o.lon)) continue
            m.projection.toPixels(GeoPoint(o.lat, o.lon), pt)
            val x = pt.x.toFloat(); val y = pt.y.toFloat(); val lim = o.type == "limit"
            fill.style = Paint.Style.FILL
            fill.color = when (o.type) {
                "cam" -> 0xFFD32F2F.toInt(); "stop" -> 0xFFB71C1C.toInt(); "yield" -> 0xFFF57C00.toInt()
                "rail" -> 0xFF6A1B9A.toInt(); "bump" -> 0xFF00897B.toInt(); else -> Color.WHITE
            }
            c.drawCircle(x, y, 13 * d, fill)
            if (lim) { fill.style = Paint.Style.STROKE; fill.strokeWidth = 3 * d; fill.color = Color.RED; c.drawCircle(x, y, 13 * d, fill) }
            txt.color = if (lim) Color.BLACK else Color.WHITE
            val label = when (o.type) { "cam" -> "К"; "stop" -> "S"; "yield" -> "У"; "rail" -> "ЖД"; "bump" -> "ЛП"; else -> o.value.ifEmpty { "!" } }
            c.drawText(label, x, y + txt.textSize / 3, txt)
        }
    }
}
