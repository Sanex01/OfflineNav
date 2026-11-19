package ua.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.widget.Toast
import btools.routingapp.IBRouterService
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class Step(val p: GeoPoint, val text: String)
class Poi(val id: Long, val lat: Double, val lon: Double, val type: String, val value: String, val dir: Double)

class NavService : Service(), SensorEventListener, LocationListener {
    companion object { @Volatile var inst: NavService? = null }

    private val STEP_M = 0.75
    private val MAX_ACC = 40f
    private val MAX_SPEED = 60.0
    private val GPS = LocationManager.GPS_PROVIDER

    @Volatile var pos: GeoPoint? = null
    @Volatile var dest: GeoPoint? = null
    @Volatile var route: List<GeoPoint> = emptyList()
    @Volatile var steps: List<Step> = emptyList()
    @Volatile var stepIdx = 0
    @Volatile var heading = 0.0
    @Volatile var speedMs = 0.0
    @Volatile var carMode = false
    @Volatile var voice = true
    @Volatile var mockUser = false
    @Volatile var obdState = "OBD выкл"
    @Volatile var distNoGps = 0.0
    @Volatile var suspicious = false
    @Volatile var offRoute = false
    @Volatile var lastGpsAcc = 999f

    private lateinit var lm: LocationManager
    private lateinit var sm: SensorManager
    private lateinit var tts: TextToSpeech
    private var ttsOk = false
    private val h = Handler(Looper.getMainLooper())
    private var lastGpsMs = 0L
    private var posTime = 0L
    private var hs = 0.0
    private var hc = 1.0
    private var prevGood = false
    private var announced = 0
    private var lastReroute = 0L
    private var mockActive = false
    private var mockSince = 0L
    private var probeUntil = 0L
    private var cnt = 0
    private var lastFixPos: GeoPoint? = null
    private var lastFixTime = 0L
    private var sensorHeading = 0.0
    private var bias = 0.0
    @Volatile private var obdRun = false
    private var sock: BluetoothSocket? = null
    @Volatile var routeSrc = ""
    @Volatile var pois: List<Poi> = emptyList()
    @Volatile var alertText = ""
    @Volatile var alertType = ""
    private var poiById = HashMap<Long, Poi>()
    private val warned = HashSet<Long>()
    private val overWarn = HashSet<Long>()
    @Volatile private var poiBusy = false
    @Volatile private var brouter: IBRouterService? = null
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) { brouter = IBRouterService.Stub.asInterface(b) }
        override fun onServiceDisconnected(n: ComponentName?) { brouter = null }
    }

    private val tickR = object : Runnable { override fun run() { tick(); h.postDelayed(this, 1000) } }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        inst = this
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                ttsOk = listOf("ru", "uk").any { l -> tts.setLanguage(Locale(l)) >= TextToSpeech.LANG_AVAILABLE }
                if (!ttsOk) toast("Нет русского/украинского голоса: установите его в настройках речи Android")
            }
        }
        val pr = getSharedPreferences("nav", 0)
        try {
            pr.getString("pos", null)?.split(",")?.let { pos = GeoPoint(it[0].toDouble(), it[1].toDouble()) }
            pr.getString("dest", null)?.split(",")?.let { dest = GeoPoint(it[0].toDouble(), it[1].toDouble()) }
        } catch (_: Exception) {}
        try {
            val g = File(filesDir, "route.gpx"); val j = File(filesDir, "route.json")
            if (g.exists()) { setRouteFromPoints(parseGpx(g.readText())); routeSrc = "BRouter (офлайн)" }
            else if (j.exists()) { parseRoute(j.readText()); routeSrc = "OSRM (онлайн)" }
        } catch (_: Exception) {}
        try { bindService(Intent().setClassName("btools.routingapp", "btools.routingapp.BRouterService"), conn, BIND_AUTO_CREATE) } catch (_: Exception) {}
        try { loadPois() } catch (_: Exception) {}
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        try { lm.requestLocationUpdates(GPS, 1000L, 0f, this) } catch (_: SecurityException) {}
        h.post(tickR)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("nav", "Навигация", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "nav").setContentTitle("OfflineNav работает")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentIntent(pi).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(1, n)
        return START_STICKY
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null); sm.unregisterListener(this); lm.removeUpdates(this)
        stopMock(); disconnectObd(); tts.shutdown(); inst = null
        try { unbindService(conn) } catch (_: Exception) {}
        super.onDestroy()
    }

    fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
    fun say(t: String) { if (voice && ttsOk) tts.speak(t, TextToSpeech.QUEUE_ADD, null, null) }
    fun gpsGood() = !suspicious && System.currentTimeMillis() - lastGpsMs < 10000 && lastGpsAcc <= MAX_ACC

    // ---------- позиция ----------
    private fun updatePos(p: GeoPoint) { pos = p; posTime = System.currentTimeMillis() }
    fun setManual(p: GeoPoint) { distNoGps = 0.0; updatePos(p); toast("Позиция задана вручную") }
    fun changeDest(p: GeoPoint?) {
        dest = p
        if (p == null) { route = emptyList(); steps = emptyList(); offRoute = false }
    }

    private fun advance(d: Double) {
        val p = pos ?: return
        val hr = Math.toRadians(heading)
        updatePos(GeoPoint(p.latitude + d * cos(hr) / 111320.0, p.longitude + d * sin(hr) / (111320.0 * cos(Math.toRadians(p.latitude)))))
        distNoGps += d
    }

    @Suppress("DEPRECATION")
    override fun onLocationChanged(l: Location) {
        if (l.isFromMockProvider) return
        val now = System.currentTimeMillis()
        lastGpsMs = now; lastGpsAcc = l.accuracy
        if (l.accuracy > MAX_ACC) return
        val p = GeoPoint(l.latitude, l.longitude)
        val lf = lastFixPos   // сравниваем с прошлым принятым GPS-фиксом, а не с позицией счисления (она дрейфует)
        if (lf != null) {
            val dt = ((now - lastFixTime) / 1000.0).coerceAtLeast(1.0)
            val d = lf.distanceToAsDouble(p)
            if (d > 200 && d > MAX_SPEED * dt) { suspicious = true; return }
        }
        lastFixPos = p; lastFixTime = now
        suspicious = false; distNoGps = 0.0
        if (l.hasSpeed()) speedMs = l.speed.toDouble()
        if (l.hasBearing() && l.speed > 3f) bias = (l.bearing - sensorHeading + 360) % 360   // поправка на установку телефона и помехи компаса
        updatePos(p)
    }
    @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                var r = FloatArray(9); val o = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(r, e.values)
                if (carMode) { val t = FloatArray(9); SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, t); r = t }
                SensorManager.getOrientation(r, o)
                val decl = pos?.let { GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), 0f, System.currentTimeMillis()).declination } ?: 0f
                val a = Math.toRadians(o[0].toDouble() + decl)
                hs = 0.85 * hs + 0.15 * sin(a); hc = 0.85 * hc + 0.15 * cos(a)
                sensorHeading = (Math.toDegrees(atan2(hs, hc)) + 360) % 360
                heading = (sensorHeading + bias) % 360
            }
            Sensor.TYPE_STEP_DETECTOR -> if (!carMode && !gpsGood()) advance(STEP_M)
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // ---------- тик раз в секунду ----------
    private fun tick() {
        val g = gpsGood()
        if (g != prevGood) {
            if (prevGood) say("GPS потерян. Счисление пути") else if (lastGpsMs > 0) say("GPS восстановлен")
            prevGood = g
        }
        if (!g && !obdRun) speedMs = 0.0   // без GPS и OBD скорость неизвестна: предупреждения отключаем
        follow()
        alerts()
        if (mockUser) mockTick(g)
        if (++cnt % 5 == 0) {
            val ed = getSharedPreferences("nav", 0).edit()
            pos?.let { ed.putString("pos", "${it.latitude},${it.longitude}") }
            dest?.let { ed.putString("dest", "${it.latitude},${it.longitude}") }
            ed.apply()
        }
    }

    // ---------- маршрут ----------
    private fun instr(type: String, mod: String, name: String): String {
        val on = if (name.isNotBlank()) " на $name" else ""
        val dir = when (mod) {
            "left" -> "налево"; "right" -> "направо"; "slight left" -> "плавно налево"
            "slight right" -> "плавно направо"; "sharp left" -> "резко налево"; "sharp right" -> "резко направо"; else -> ""
        }
        return when {
            type == "arrive" -> "Вы прибыли"
            type == "depart" -> "Начните движение$on"
            type.startsWith("round") || type == "rotary" -> "На кольце, съезд$on"
            mod == "uturn" -> "Развернитесь"
            dir == "" -> "Продолжайте прямо$on"
            else -> "Поверните $dir$on"
        }
    }

    private fun parseRoute(js: String) {
        val r = JSONObject(js).getJSONArray("routes").getJSONObject(0)
        val c = r.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = List(c.length()) { val q = c.getJSONArray(it); GeoPoint(q.getDouble(1), q.getDouble(0)) }
        val st = mutableListOf<Step>()
        val legs = r.getJSONArray("legs")
        for (li in 0 until legs.length()) {
            val ss = legs.getJSONObject(li).getJSONArray("steps")
            for (i in 0 until ss.length()) {
                val s = ss.getJSONObject(i); val m = s.getJSONObject("maneuver"); val loc = m.getJSONArray("location")
                st.add(Step(GeoPoint(loc.getDouble(1), loc.getDouble(0)), instr(m.getString("type"), m.optString("modifier"), s.optString("name"))))
            }
        }
        route = pts; steps = st; stepIdx = if (st.size > 1) 1 else 0; announced = 0
    }

    private fun parseGpx(gpx: String): List<GeoPoint> {
        val la = Regex("lat=\"([^\"]+)\""); val lo = Regex("lon=\"([^\"]+)\"")
        val pts = Regex("<trkpt\\s+([^>]*)>").findAll(gpx).mapNotNull {
            val a = it.groupValues[1]
            val y = la.find(a)?.groupValues?.get(1)?.toDoubleOrNull(); val x = lo.find(a)?.groupValues?.get(1)?.toDoubleOrNull()
            if (x != null && y != null) GeoPoint(y, x) else null
        }.toList()
        if (pts.size < 2) throw Exception("пустой трек")
        return pts
    }

    // Повороты считаем по геометрии: угол между направлением за 30 м до точки и за 30 м после.
    private fun setRouteFromPoints(pts: List<GeoPoint>) {
        val n = pts.size
        val cum = DoubleArray(n); for (i in 1 until n) cum[i] = cum[i - 1] + pts[i - 1].distanceToAsDouble(pts[i])
        val out = mutableListOf(Step(pts[0], "Начните движение"))
        var lastCum = 0.0
        fun emit(i: Int, d: Double) {
            if (cum[i] - lastCum < 40) return
            val a = abs(d)
            val t = when {
                a > 150 -> "Развернитесь"
                else -> { val side = if (d < 0) "налево" else "направо"
                    if (a < 70) "Плавно поверните $side" else if (a < 130) "Поверните $side" else "Резко поверните $side" }
            }
            out.add(Step(pts[i], t)); lastCum = cum[i]
        }
        var j = 0; var k = 0; var runIdx = -1; var runD = 0.0
        for (i in 1 until n - 1) {
            while (j + 1 < i && cum[i] - cum[j + 1] >= 30) j++
            k = maxOf(k, i + 1); while (k < n - 1 && cum[k] - cum[i] < 30) k++
            if (cum[i] - cum[j] < 15 || cum[k] - cum[i] < 15) continue
            val d = ((pts[i].bearingTo(pts[k]) - pts[j].bearingTo(pts[i]) + 540) % 360) - 180
            if (abs(d) > 40) { if (abs(d) > abs(runD)) { runD = d; runIdx = i } }
            else if (runIdx >= 0) { emit(runIdx, runD); runIdx = -1; runD = 0.0 }
        }
        if (runIdx >= 0) emit(runIdx, runD)
        out.add(Step(pts[n - 1], "Вы прибыли"))
        route = pts; steps = out; stepIdx = if (out.size > 1) 1 else 0; announced = 0
    }

    fun buildRoute() {
        val p = pos; val d = dest
        if (p == null || d == null) { toast("Нужны позиция и цель"); return }
        Thread {
            var err = ""
            val br = brouter
            if (br == null) err = "BRouter не установлен"
            else try {
                val b = Bundle().apply {
                    putString("trackFormat", "gpx")
                    putDoubleArray("lats", doubleArrayOf(p.latitude, d.latitude))
                    putDoubleArray("lons", doubleArrayOf(p.longitude, d.longitude))
                    putString("v", "motorcar"); putString("fast", "1"); putString("profile", "car-fast")
                }
                val gpx = br.getTrackFromParams(b)
                if (gpx != null && gpx.contains("<trkpt")) {
                    val pts = parseGpx(gpx)
                    File(filesDir, "route.gpx").writeText(gpx); File(filesDir, "route.json").delete()
                    setRouteFromPoints(pts); routeSrc = "BRouter (офлайн)"
                    h.post { say("Маршрут построен"); updatePois(false) }
                    return@Thread
                }
                err = gpx?.take(80) ?: "пустой ответ"
            } catch (e: Exception) { err = e.message ?: "ошибка" }
            try {
                val u = "https://router.project-osrm.org/route/v1/driving/${p.longitude},${p.latitude};${d.longitude},${d.latitude}?overview=full&geometries=geojson&steps=true"
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                val js = c.inputStream.bufferedReader().readText()
                parseRoute(js); File(filesDir, "route.json").writeText(js); File(filesDir, "route.gpx").delete()
                routeSrc = "OSRM (онлайн)"
                h.post { say("Маршрут построен"); updatePois(false) }
            } catch (e: Exception) { h.post { toast("Маршрут не построен. BRouter: $err; онлайн недоступен") } }
        }.start()
    }

    private fun segDist(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val k = cos(Math.toRadians(p.latitude))
        val ax = (a.longitude - p.longitude) * k; val ay = a.latitude - p.latitude
        val dx = (b.longitude - p.longitude) * k - ax; val dy = (b.latitude - p.latitude) - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / l2
        val tc = t.coerceIn(0.0, 1.0)
        val x = ax + tc * dx; val y = ay + tc * dy
        return sqrt(x * x + y * y) * 111320.0
    }

    private fun follow() {
        val p = pos ?: return
        val rt = route
        if (rt.size < 2) { offRoute = false; return }
        var best = Double.MAX_VALUE
        for (i in 0 until rt.size - 1) best = min(best, segDist(p, rt[i], rt[i + 1]))
        offRoute = best > 50
        val now = System.currentTimeMillis()
        if (offRoute && now - lastReroute > 20000) {
            lastReroute = now
            say("Вы вне маршрута")
            if (dest != null) buildRoute()
        }
        val st = steps.getOrNull(stepIdx) ?: return
        val d = p.distanceToAsDouble(st.p)
        val txt = st.text.replaceFirstChar { it.lowercase() }
        when {
            d < 30 -> { stepIdx++; announced = 0 }
            d < 60 && announced < 2 -> { say(st.text); announced = 2 }
            d < 300 && announced < 1 -> { say("Через ${(d / 10).toInt() * 10} метров $txt"); announced = 1 }
        }
    }

    // ---------- камеры и дорожные знаки (данные OSM, кэшируются для работы офлайн) ----------
    private fun classify(t: JSONObject): Pair<String, String>? {
        val hw = t.optString("highway"); val en = t.optString("enforcement")
        fun digits(k: String) = t.optString(k).filter { it.isDigit() }
        return when {
            hw == "speed_camera" || en == "maxspeed" || en == "average_speed" || en == "traffic_signals" -> "cam" to digits("maxspeed")
            hw == "stop" -> "stop" to ""
            hw == "give_way" -> "yield" to ""
            t.optString("railway") == "level_crossing" -> "rail" to ""
            t.optString("traffic_calming") in setOf("bump", "hump", "table", "cushion") -> "bump" to ""
            t.has("traffic_sign") && t.has("maxspeed") -> "limit" to digits("maxspeed")
            else -> null
        }
    }

    private fun replacePois(l: List<Poi>) { poiById = HashMap(l.associateBy { it.id }); pois = l }

    private fun savePois(l: List<Poi>) {
        val a = JSONArray()
        for (o in l) a.put(JSONObject().put("i", o.id).put("la", o.lat).put("lo", o.lon).put("t", o.type).put("v", o.value)
            .put("d", if (o.dir.isNaN()) JSONObject.NULL else o.dir))
        File(filesDir, "pois.json").writeText(a.toString())
    }

    private fun loadPois() {
        val f = File(filesDir, "pois.json"); if (!f.exists()) return
        val a = JSONArray(f.readText())
        replacePois(List(a.length()) {
            val o = a.getJSONObject(it)
            Poi(o.getLong("i"), o.getDouble("la"), o.getDouble("lo"), o.getString("t"), o.getString("v"),
                if (o.isNull("d")) Double.NaN else o.getDouble("d"))
        })
    }

    // Качает камеры и знаки вдоль маршрута (или в радиусе 20 км от позиции) и дописывает в локальную базу.
    fun updatePois(manual: Boolean) {
        if (poiBusy) return
        val rt = route; val p = pos
        val area: String = if (rt.size > 1) {
            val step = if (rt.size > 20000) 800.0 else 400.0
            val sb = StringBuilder(); var acc = step; var prev = rt[0]
            for (q in rt) { acc += prev.distanceToAsDouble(q); prev = q; if (acc >= step) { acc = 0.0; sb.append(",${q.latitude},${q.longitude}") } }
            sb.append(",${rt.last().latitude},${rt.last().longitude}")
            "around:200$sb"
        } else if (p != null) "around:20000,${p.latitude},${p.longitude}"
        else { if (manual) toast("Нет позиции или маршрута"); return }
        val sel = listOf("[\"highway\"=\"speed_camera\"]", "[\"enforcement\"~\"maxspeed|average_speed|traffic_signals\"]",
            "[\"highway\"~\"^(stop|give_way)\$\"]", "[\"railway\"=\"level_crossing\"]",
            "[\"traffic_calming\"~\"^(bump|hump|table|cushion)\$\"]", "[\"traffic_sign\"][\"maxspeed\"]")
        val q = "[out:json][timeout:90];(" + sel.joinToString("") { "node$it($area);" } + ");out;"
        poiBusy = true
        Thread {
            try {
                val c = URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 90000
                c.setRequestProperty("User-Agent", "OfflineNav/1.0")
                c.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
                val el = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("elements")
                val map = HashMap(poiById)
                for (i in 0 until el.length()) {
                    val e = el.getJSONObject(i); val t = e.optJSONObject("tags") ?: continue
                    val (type, v) = classify(t) ?: continue
                    val dir = t.optString("direction").ifEmpty { t.optString("camera:direction") }.toDoubleOrNull() ?: Double.NaN
                    val id = e.getLong("id")
                    map[id] = Poi(id, e.getDouble("lat"), e.getDouble("lon"), type, v, dir)
                }
                val l = map.values.toList(); savePois(l); replacePois(l)
                if (manual) h.post { toast("Камеры и знаки: ${l.size} объектов в базе") }
            } catch (e: Exception) {
                if (manual) h.post { toast("Не удалось загрузить камеры/знаки: ${e.message}") }
            } finally { poiBusy = false }
        }.start()
    }

    private fun title(o: Poi) = when (o.type) {
        "cam" -> "Камера контроля скорости" + if (o.value.isNotBlank()) ", ограничение ${o.value}" else ""
        "stop" -> "Знак Стоп"
        "yield" -> "Уступите дорогу"
        "rail" -> "Железнодорожный переезд"
        "bump" -> "Искусственная неровность"
        "limit" -> "Ограничение скорости ${o.value}"
        else -> "Дорожный знак"
    }

    private fun icon(o: Poi) = when (o.type) {
        "cam" -> "📷"; "stop" -> "⛔"; "yield" -> "🔻"; "rail" -> "🚆"; "bump" -> "⚠️"; else -> "🚸"
    }

    private fun distToRoute(q: GeoPoint): Double {
        val rt = route; var b = Double.MAX_VALUE
        for (i in 0 until rt.size - 1) b = min(b, segDist(q, rt[i], rt[i + 1]))
        return b
    }

    // Ближайший объект впереди: в конусе ±45° от курса, при наличии маршрута, только рядом с ним.
    private fun alerts() {
        val p = pos; val sp = speedMs
        if (p == null || sp < 2.0 || pois.isEmpty()) { alertText = ""; alertType = ""; return }
        val kmh = sp * 3.6
        var best: Poi? = null; var bd = Double.MAX_VALUE
        val checkRoute = route.size > 1 && !offRoute
        for (o in pois) {
            if (abs(o.lat - p.latitude) > 0.01) continue
            val q = GeoPoint(o.lat, o.lon); val d = p.distanceToAsDouble(q)
            if (d > maxOf(if (o.type == "cam") 500.0 else 250.0, sp * 10)) continue
            if (abs(((p.bearingTo(q) - heading + 540) % 360) - 180) > 45) continue
            if (!o.dir.isNaN() && abs(((o.dir - heading + 540) % 360) - 180) > 100) continue
            if (checkRoute && distToRoute(q) > 40) continue
            if (d < bd) { bd = d; best = o }
        }
        warned.retainAll { id -> poiById[id]?.let { GeoPoint(it.lat, it.lon).distanceToAsDouble(p) < 1500 } ?: false }
        overWarn.retainAll(warned)
        val b = best
        if (b == null) { alertText = ""; alertType = ""; return }
        alertType = b.type
        alertText = "${icon(b)} ${title(b)} — ${bd.toInt()} м"
        if (warned.add(b.id)) say("${title(b)} через ${(bd / 10).toInt() * 10} метров")
        val lim = b.value.toIntOrNull()
        if ((b.type == "cam" || b.type == "limit") && lim != null && kmh > lim + 5 && overWarn.add(b.id)) say("Превышение скорости. Ограничение $lim")
    }

    // ---------- OBD (ELM327 по Bluetooth) ----------
    fun disconnectObd() { obdRun = false; try { sock?.close() } catch (_: Exception) {}; obdState = "OBD выкл" }

    fun connectObd(d: BluetoothDevice) {
        disconnectObd(); obdRun = true; obdState = "OBD: подключение…"
        Thread {
            try {
                val s = d.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
                s.connect(); sock = s
                val i = s.inputStream; val o = s.outputStream
                fun cmd(c: String): String {
                    o.write((c + "\r").toByteArray()); o.flush()
                    val sb = StringBuilder(); val end = System.currentTimeMillis() + 2500
                    while (System.currentTimeMillis() < end) {
                        if (i.available() > 0) { val ch = i.read(); if (ch == '>'.code) break; sb.append(ch.toChar()) } else Thread.sleep(10)
                    }
                    return sb.toString().replace("\\s".toRegex(), "")
                }
                for (c in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATSP0")) cmd(c)
                var last = System.currentTimeMillis()
                while (obdRun) {
                    val r = cmd("010D"); val k = r.indexOf("410D")
                    val kmh = if (k >= 0 && r.length >= k + 6) r.substring(k + 4, k + 6).toIntOrNull(16) else null
                    if (kmh != null) {
                        val now = System.currentTimeMillis()
                        val dt = ((now - last) / 1000.0).coerceIn(0.0, 1.0); last = now
                        speedMs = kmh / 3.6; obdState = "OBD: $kmh км/ч"
                        if (speedMs > 0) h.post { if (!gpsGood()) advance(speedMs * dt) }
                    } else obdState = "OBD: нет данных"
                    Thread.sleep(100)
                }
            } catch (e: Exception) { obdState = "OBD: ошибка ${e.message}" }
            finally { try { sock?.close() } catch (_: Exception) {} }
        }.start()
    }

    // ---------- подмена местоположения для других навигаторов ----------
    private fun startMock(): Boolean = try {
        try { lm.removeTestProvider(GPS) } catch (_: Exception) {}
        lm.addTestProvider(GPS, false, false, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
        lm.setTestProviderEnabled(GPS, true)
        mockActive = true; mockSince = System.currentTimeMillis(); true
    } catch (e: Exception) {
        toast("Включите: Параметры разработчика → Приложение для фиктивных местоположений → OfflineNav"); false
    }

    private fun stopMock() {
        try { lm.setTestProviderEnabled(GPS, false); lm.removeTestProvider(GPS) } catch (_: Exception) {}
        mockActive = false
    }

    private fun pushMock() {
        val p = pos ?: return
        val l = Location(GPS).apply {
            latitude = p.latitude; longitude = p.longitude; accuracy = 20f; altitude = 0.0
            bearing = heading.toFloat(); speed = speedMs.toFloat()
            time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        try { lm.setTestProviderLocation(GPS, l) } catch (_: Exception) {}
    }

    fun setMock(on: Boolean) {
        mockUser = on
        if (on) { if (startMock()) stopMock() else mockUser = false } else stopMock()
    }

    // Пока подмена активна, настоящий GPS заблокирован, поэтому раз в 20 с отключаем её на 6 с и проверяем, вернулся ли GPS.
    private fun mockTick(g: Boolean) {
        val now = System.currentTimeMillis()
        if (mockActive) {
            if (now - mockSince > 20000) { stopMock(); probeUntil = now + 6000 } else pushMock()
        } else if (!g && now > probeUntil) { if (startMock()) pushMock() }
    }
}
