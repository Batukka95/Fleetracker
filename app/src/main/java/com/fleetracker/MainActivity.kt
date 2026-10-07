package com.fleetracker

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var btnStartStop: Button
    private lateinit var btnShow: Button
    private lateinit var btnPlay: Button
    private lateinit var tvDate: TextView
    private lateinit var tvFrom: TextView
    private lateinit var tvTo: TextView
    private lateinit var tvStatus: TextView

    private var tracking = false
    private var routePoints: List<GeoPoint> = emptyList()
    private var playbackJob: Job? = null
    private var isPaused = false
    private var playSpeed = 1.0

    private var carMarker: Marker? = null
    private var trailLine: Polyline? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid ayarları (API anahtarı gerekmez)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }

        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.map)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(16.0)

        btnStartStop = findViewById(R.id.btnStartStop)
        btnShow = findViewById(R.id.btnShow)
        btnPlay = findViewById(R.id.btnPlay)
        tvDate = findViewById(R.id.tvDate)
        tvFrom = findViewById(R.id.tvFrom)
        tvTo = findViewById(R.id.tvTo)
        tvStatus = findViewById(R.id.tvStatus)

        tvDate.text = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        btnPlay.isEnabled = false

        btnStartStop.setOnClickListener { toggleTracking() }
        btnShow.setOnClickListener { showRoute() }
        btnPlay.setOnClickListener { togglePlay() }

        tvDate.setOnClickListener {
            val c = Calendar.getInstance()
            DatePickerDialog(this, { _, y, m, d ->
                tvDate.text = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }
        tvFrom.setOnClickListener { pickTime(tvFrom) }
        tvTo.setOnClickListener { pickTime(tvTo) }

        // Hız düğmeleri: 1x … 16x
        listOf(
            R.id.btnSpeed1 to 1.0,
            R.id.btnSpeed2 to 2.0,
            R.id.btnSpeed4 to 4.0,
            R.id.btnSpeed8 to 8.0,
            R.id.btnSpeed16 to 16.0
        ).forEach { (id, sp) ->
            findViewById<Button>(id).setOnClickListener {
                playSpeed = sp
                tvStatus.text = "Oynatma hızı: ${sp.toInt()}x"
            }
        }
    }

    private fun pickTime(tv: TextView) {
        val c = Calendar.getInstance()
        TimePickerDialog(this, { _, h, m ->
            tv.text = String.format(Locale.US, "%02d:%02d", h, m)
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
    }

    // ---------- 1) Takibi başlat / durdur ----------

    private fun toggleTracking() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS
                ),
                1
            )
            return
        }
        // Arka plan izni ayrı istenir ("Her zaman izin ver")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                2
            )
        }

        if (tracking) {
            val stop = Intent(this, TrackingService::class.java)
                .setAction(TrackingService.ACTION_STOP)
            startService(stop)
            tracking = false
            btnStartStop.text = "Takibi Başlat"
            tvStatus.text = "Takip durduruldu."
        } else {
            ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
            tracking = true
            btnStartStop.text = "Takibi Durdur"
            tvStatus.text = "Takip çalışıyor — konum verileri kaydediliyor."
        }
    }

    // ---------- 2) Seçilen saat aralığındaki izi yükle ----------

    private fun showRoute() {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val from = fmt.parse("${tvDate.text} ${tvFrom.text}")?.time ?: return
        val to = fmt.parse("${tvDate.text} ${tvTo.text}")?.time ?: return
        if (to <= from) {
            toast("Bitiş saati başlangıçtan sonra olmalı.")
            return
        }

        stopPlayback()
        tvStatus.text = "Kayıtlar yükleniyor..."

        lifecycleScope.launch {
            val dbPoints = withContext(Dispatchers.IO) {
                AppDatabase.get(this@MainActivity).dao().getBetween(from, to)
            }
            if (dbPoints.size < 2) {
                toast("Bu tarih/saat aralığında yeterli konum kaydı yok.")
                tvStatus.text = "Kayıt bulunamadı."
                return@launch
            }

            tvStatus.text = "İz yollara oturtuluyor (OSRM)..."
            val snapped = OsrmSnapper.snap(dbPoints.map { GeoPoint(it.lat, it.lon) })
            routePoints = snapped
            drawRoute(snapped)
            tvStatus.text = "İz hazır: ${snapped.size} nokta. 'Oynat' ile animasyonu başlatın."
            btnPlay.isEnabled = true
        }
    }

    private fun drawRoute(pts: List<GeoPoint>) {
        mapView.overlays.removeAll { it is Polyline || it is Marker }
        carMarker = null
        trailLine = null

        val line = Polyline(mapView)
        line.setPoints(pts)
        line.outlinePaint.color = Color.rgb(33, 150, 243) // mavi
        line.outlinePaint.strokeWidth = 10f
        mapView.overlays.add(line)

        val start = Marker(mapView).apply {
            position = pts.first()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Başlangıç"
        }
        val end = Marker(mapView).apply {
            position = pts.last()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Bitiş"
        }
        mapView.overlays.add(start)
        mapView.overlays.add(end)

        var cLat = 0.0
        var cLon = 0.0
        pts.forEach { cLat += it.latitude; cLon += it.longitude }
        mapView.controller.setCenter(GeoPoint(cLat / pts.size, cLon / pts.size))
        mapView.invalidate()
    }

    // ---------- 3) Animasyonlu oynatma ----------

    private fun togglePlay() {
        if (routePoints.size < 2) {
            toast("Önce 'İzi Göster' ile bir yol yükleyin.")
            return
        }
        if (playbackJob?.isActive == true) {
            isPaused = !isPaused
            btnPlay.text = if (isPaused) "Devam Et" else "Duraklat"
        } else {
            startPlayback()
        }
    }

    private fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        isPaused = false
        btnPlay.text = "Oynat"
    }

    private fun startPlayback() {
        stopPlayback()
        btnPlay.text = "Duraklat"

        // Önceki animasyon kalıntılarını temizle
        carMarker?.let { mapView.overlays.remove(it) }
        trailLine?.let { mapView.overlays.remove(it) }

        val car = Marker(mapView).apply {
            position = routePoints.first()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.ic_menu_directions)
        }
        mapView.overlays.add(car)
        carMarker = car

        val trail = Polyline(mapView)
        trail.outlinePaint.color = Color.rgb(211, 47, 47) // kırmızı — geçilen yol
        trail.outlinePaint.strokeWidth = 12f
        val trailPts = mutableListOf(routePoints.first())
        trail.setPoints(ArrayList(trailPts))
        mapView.overlays.add(trail)
        trailLine = trail

        mapView.controller.setCenter(routePoints.first())

        playbackJob = lifecycleScope.launch {
            for (i in 0 until routePoints.size - 1) {
                val a = routePoints[i]
                val b = routePoints[i + 1]
                val steps = 6
                for (s in 1..steps) {
                    while (isPaused) delay(150)
                    val f = s.toDouble() / steps
                    car.position = GeoPoint(
                        a.latitude + (b.latitude - a.latitude) * f,
                        a.longitude + (b.longitude - a.longitude) * f
                    )
                    if (s == steps) {
                        trailPts.add(b)
                        trail.setPoints(ArrayList(trailPts))
                    }
                    mapView.invalidate()
                    delay((50L / playSpeed).toLong())
                }
            }
            tvStatus.text = "Animasyon tamamlandı 🎉"
            btnPlay.text = "Oynat"
        }
    }

    override fun onDestroy() {
        playbackJob?.cancel()
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

