package com.fleetracker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Ön planda çalışan (bildirimli) konum takip servisi.
 * Ekran kapalıyken de her 3 saniyede / 5 metrede bir konumu veritabanına yazar.
 */
class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fused: FusedLocationProviderClient
    private var callback: LocationCallback? = null
    private var started = false

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Konum Takibi", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            callback?.let { fused.removeLocationUpdates(it) }
            callback = null
            started = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIF_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Fleetracker")
                .setContentText("Araç konumu izleniyor")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build()
        )

        if (!started) startUpdates()
        return START_STICKY
    }

    private fun startUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateDistanceMeters(5f)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                scope.launch {
                    AppDatabase.get(applicationContext).dao().insert(
                        LocationEntity(
                            lat = loc.latitude,
                            lon = loc.longitude,
                            timestamp = loc.time
                        )
                    )
                }
            }
        }
        callback = cb
        try {
            fused.requestLocationUpdates(request, cb, Looper.getMainLooper())
            started = true
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        callback?.let { fused.removeLocationUpdates(it) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.fleetracker.STOP"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIF_ID = 1
    }
}

