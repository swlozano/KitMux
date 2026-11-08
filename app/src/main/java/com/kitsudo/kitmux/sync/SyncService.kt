package com.kitsudo.kitmux.sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kitsudo.kitmux.R
import com.kitsudo.kitmux.ui.MainActivity
import java.net.Inet4Address

/**
 * Receptor de archivos desde el PC. Mientras está activo:
 *  - solo escucha si el teléfono está conectado a una WiFi (se detiene al perderla),
 *  - se anuncia en la red local por mDNS como `_kitmux._tcp` para que el script lo encuentre,
 *  - mantiene WiFi y CPU despiertos para recibir con la pantalla apagada.
 */
class SyncService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var connectivity: ConnectivityManager
    private lateinit var nsd: NsdManager
    private lateinit var pairing: PairingStore
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock

    private var http: MiniHttpServer? = null
    private var syncServer: SyncServer? = null
    private var wifiNetwork: Network? = null
    private var registration: NsdManager.RegistrationListener? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            val ip = linkProperties.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }?.hostAddress
            mainHandler.post { onWifi(network, ip) }
        }

        override fun onLost(network: Network) {
            mainHandler.post { if (network == wifiNetwork) onWifiLost() }
        }
    }

    @SuppressLint("WakelockTimeout", "InlinedApi") // el wakelock dura lo que el usuario mantenga activo el receptor
    override fun onCreate() {
        super.onCreate()
        connectivity = getSystemService(ConnectivityManager::class.java)
        nsd = getSystemService(NsdManager::class.java)
        pairing = PairingStore(this)

        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.sync_channel))
                .build(),
        )
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KitMux:sync").apply { acquire() }
        @Suppress("DEPRECATION")
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "KitMux:sync").apply { acquire() }

        SyncStatus.update { SyncStatus.State(enabled = true) }
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            networkCallback,
            mainHandler,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        stopServer()
        if (wakeLock.isHeld) wakeLock.release()
        if (wifiLock.isHeld) wifiLock.release()
        SyncStatus.update { SyncStatus.State() }
        super.onDestroy()
    }

    private fun onWifi(network: Network, ip: String?) {
        if (ip == null) return
        wifiNetwork = network
        if (http == null) startServer()
        val port = currentPort ?: return
        SyncStatus.update { it.copy(address = "$ip:$port") }
        refreshNotification()
    }

    private fun onWifiLost() {
        wifiNetwork = null
        stopServer()
        SyncStatus.update { it.copy(address = null) }
        refreshNotification()
    }

    private var currentPort: Int? = null

    private fun startServer() {
        val server = SyncServer(
            storage = SyncStorage.create(this),
            pairing = pairing,
            onPairingCodeChanged = { code ->
                SyncStatus.update { it.copy(pairingCode = code) }
                mainHandler.post(::refreshNotification)
            },
            onActivity = { message ->
                SyncStatus.update { it.copy(lastActivity = message) }
                mainHandler.post(::refreshNotification)
            },
        )
        val httpServer = MiniHttpServer(server::handle) { Log.w(TAG, "Error en petición", it) }
        val port = try {
            // Mismo puerto que la vez anterior si está libre; si no, uno cualquiera.
            runCatching { httpServer.start(pairing.lastPort) }.getOrElse { httpServer.start(0) }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir el puerto", e)
            return
        }
        pairing.lastPort = port
        http = httpServer
        syncServer = server
        currentPort = port
        SyncStatus.update { it.copy(pairingCode = server.currentPairingCode) }
        registerNsd(port)
    }

    private fun stopServer() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        http?.stop()
        http = null
        syncServer = null
        currentPort = null
    }

    private fun registerNsd(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = SyncServer.deviceName()
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("id", pairing.deviceId)
            setAttribute("v", SyncServer.PROTOCOL_VERSION.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "mDNS: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                Log.w(TAG, "mDNS falló: $error")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun buildNotification(): Notification {
        val state = SyncStatus.state.value
        val title = if (state.address != null) {
            getString(R.string.sync_notification_ready)
        } else {
            getString(R.string.sync_waiting_wifi)
        }
        val text = state.lastActivity
            ?: state.pairingCode?.takeIf { state.address != null }?.let { getString(R.string.sync_pairing_code, it) }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SyncService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync)
            .setColor(ContextCompat.getColor(this, R.color.brand_orange))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, getString(R.string.sync_stop), stop)
            .build()
    }

    @SuppressLint("MissingPermission", "InlinedApi")
    private fun refreshNotification() {
        val granted = android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        private const val TAG = "KitMuxSync"
        private const val CHANNEL_ID = "sync"
        private const val NOTIFICATION_ID = 2001
        private const val ACTION_STOP = "com.kitsudo.kitmux.sync.STOP"
        const val SERVICE_TYPE = "_kitmux._tcp"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncService::class.java))
        }
    }
}
