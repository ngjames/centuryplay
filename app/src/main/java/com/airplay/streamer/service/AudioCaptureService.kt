package com.airplay.streamer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.airplay.streamer.MainActivity
import com.airplay.streamer.R
import com.airplay.streamer.airplay2.AirPlay2Client
import com.airplay.streamer.airplay2.TimingMode
import com.airplay.streamer.raop.RaopCapabilities
import com.airplay.streamer.raop.RaopClient
import com.airplay.streamer.util.LogServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Foreground service that captures system audio using MediaProjection/AudioPlaybackCapture
 * and streams it to an AirPlay speaker via RAOP
 */
class AudioCaptureService : Service() {
    companion object {
        private const val TAG = "AudioCaptureService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "airplay_streaming"

        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAMES_PER_PACKET = 352
        private const val BYTES_PER_FRAME = 4 // 16-bit stereo = 4 bytes
        private const val BUFFER_SIZE = FRAMES_PER_PACKET * BYTES_PER_FRAME

        private const val AP2_PORT = 7000
        private const val HEALTH_CHECK_INTERVAL_MS = 10_000L

        const val ACTION_START = "com.airplay.streamer.START"
        const val ACTION_STOP = "com.airplay.streamer.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_RAOP_PORT = "raop_port"
        const val EXTRA_PROTOCOL_PREFERENCE = "protocol_preference"
        const val EXTRA_AP2_TIMING = "ap2_timing"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_DEVICE_FEATURES = "device_features"

        // Singleton for accessing streaming state
        var instance: AudioCaptureService? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var raopClient: RaopClient? = null
    private var ap2Client: AirPlay2Client? = null
    private var captureJob: Job? = null
    private var healthJob: Job? = null

    private var isCapturing = false
    private var deviceName: String = "AirPlay Speaker"

    // Callback for UI updates
    var onStateChanged: ((Boolean) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onDestroy() {
        stopCapture()
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, 0)
                val raopPort = intent.getIntExtra(EXTRA_RAOP_PORT, -1).takeIf { it > 0 }
                // Auto-connect restores the protocol/timing snapshot via extras (-1 = use prefs).
                val protocolPrefOverride = intent.getIntExtra(EXTRA_PROTOCOL_PREFERENCE, -1).takeIf { it >= 0 }
                val ap2TimingOverride = intent.getIntExtra(EXTRA_AP2_TIMING, -1).takeIf { it >= 0 }
                deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "AirPlay Speaker"
                val featuresJson = intent.getStringExtra(EXTRA_DEVICE_FEATURES) ?: ""

                if (resultData != null) {
                    startCapture(resultCode, resultData, host, port, raopPort, featuresJson, protocolPrefOverride, ap2TimingOverride)
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent, host: String, port: Int, raopPort: Int?, featuresJson: String, protocolPrefOverride: Int?, ap2TimingOverride: Int?) {
        if (isCapturing) return

        // Start foreground with notification
        startForeground(NOTIFICATION_ID, createNotification())

        serviceScope.launch {
            try {
                // Get MediaProjection
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) 
                    as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

                if (mediaProjection == null) {
                    stopSelf()
                    return@launch
                }

                // Protocol preference from SharedPreferences 'airplay_prefs':
                // 0=Auto, 1=AirPlay 1 (RAOP), 2=AirPlay 2. EXTRA_PORT carries the
                // AP2 port (device.port, typically 7000); the RAOP port arrives
                // separately in EXTRA_RAOP_PORT. Auto-connect may override the
                // preference via extras (todo 13).
                val prefs = getSharedPreferences("airplay_prefs", MODE_PRIVATE)
                val protocolPref = protocolPrefOverride ?: prefs.getInt("protocol_preference", 0)
                val protocol = resolveProtocol(protocolPref, port, raopPort)
                LogServer.log("Protocol preference: $protocolPref -> $protocol")

                if (protocol == Protocol.AIRPLAY2) {
                    startAirPlay2Stream(host, prefs, ap2TimingOverride)
                    return@launch
                }

                // AirPlay 1 (RAOP) Path
                val raopConnectPort = raopPort ?: port
                LogServer.log("Starting AirPlay 1 (RAOP) connection to $host:$raopConnectPort")
                val deviceFeatures = featuresJson.split(";")
                    .mapNotNull { pair ->
                        val parts = pair.split("=", limit = 2)
                        if (parts.size == 2) parts[0] to parts[1] else null
                    }.toMap()

                if (RaopCapabilities.requiresUnsupportedFairPlay(deviceFeatures)) {
                    LogServer.log(getString(R.string.fairplay_required_message, deviceName))
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }

                raopClient = RaopClient(host, raopConnectPort, deviceFeatures)
                
                // Set callback to handle server disconnects
                raopClient?.callback = object : RaopClient.StreamingCallback {
                    override fun onConnected() {
                        LogServer.log("RAOP callback: Connected")
                    }
                    
                    override fun onDisconnected() {
                        LogServer.log("RAOP callback: Server disconnected - stopping service")
                        // Post to main thread to stop the service
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            stopCapture()
                            stopSelf()
                        }
                    }
                    
                    override fun onError(error: String) {
                        LogServer.log("RAOP callback: Error - $error")
                    }
                }
                
                val connected = raopClient?.connect() ?: false

                if (!connected) {
                    LogServer.log("Failed to connect to RAOP server")
                    stopCapture()
                    return@launch
                }

                LogServer.log("RAOP connection established, setting initial volume")
                // Set initial volume
                raopClient?.setVolume(0.8f)

                // Try AudioPlaybackCapture
                val captureStarted = tryAudioPlaybackCapture()

                if (captureStarted) {
                    isCapturing = true
                    onStateChanged?.invoke(true)
                    LogServer.log("Audio capture started, beginning stream loop")
                    startAudioStreamLoop()
                } else {
                    LogServer.log("Audio capture failed (possibly DRM blocked)")
                    stopCapture()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                LogServer.log("Streaming/Pairing Error: ${e.message}")
                stopCapture()
            }
        }
    }

    private fun tryAudioPlaybackCapture(): Boolean {
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val bufferSize = maxOf(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
                BUFFER_SIZE * 4
            )

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord?.startRecording()
                true
            } else {
                audioRecord?.release()
                audioRecord = null
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun startAudioStreamLoop() {
        captureJob = serviceScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_SIZE)

            while (isActive && isCapturing) {
                val bytesRead = audioRecord?.read(buffer, 0, BUFFER_SIZE) ?: -1

                if (bytesRead > 0) {
                    raopClient?.streamAudio(buffer.copyOf(bytesRead))
                } else if (bytesRead < 0) {
                    // Error reading audio
                    break
                }
            }
        }
    }

    /**
     * AirPlay 2 path: connect -> transient pair -> setupStreaming -> capture
     * loop. Any connect/pair/setup failure goes through [failStream] so the
     * service is never left half-running (dev-branch catch-path bug fix).
     */
    private suspend fun startAirPlay2Stream(host: String, prefs: android.content.SharedPreferences, ap2TimingOverride: Int?) {
        val timingMode = if ((ap2TimingOverride ?: prefs.getInt("ap2_timing", 0)) == 1) TimingMode.PTP else TimingMode.NTP
        LogServer.log("Starting AirPlay 2 connection to $host:$AP2_PORT (timing: $timingMode)")
        val client = AirPlay2Client(host, AP2_PORT, timingMode)
        ap2Client = client

        try {
            client.connect()
            LogServer.log("Connected. Attempting Pair-Setup (transient)...")

            if (!client.pair()) {
                failStream("AirPlay 2 pairing failed")
                return
            }
            LogServer.log("AirPlay 2 Pair-Setup Successful!")

            if (!client.setupStreaming()) {
                failStream("AirPlay 2 audio setup failed")
                return
            }
            LogServer.log("Audio stream setup complete")

            val captureStarted = tryAudioPlaybackCapture()
            if (!captureStarted) {
                LogServer.log("AirPlay 2 audio capture failed (possibly DRM blocked)")
                failStream("AirPlay 2 audio capture failed")
                return
            }

            isCapturing = true
            onStateChanged?.invoke(true)
            LogServer.log("Audio capture started for AirPlay 2")
            startAirPlay2StreamLoop(client)
            startHealthMonitor()
        } catch (e: Exception) {
            e.printStackTrace()
            failStream("AirPlay 2 error: ${e.message}")
        }
    }

    private fun startAirPlay2StreamLoop(client: AirPlay2Client) {
        captureJob = serviceScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_SIZE)
            var packetCount = 0L

            while (isActive && isCapturing) {
                val bytesRead = audioRecord?.read(buffer, 0, BUFFER_SIZE) ?: -1

                if (bytesRead > 0) {
                    // ALAC encoding + RTP framing handled inside the client
                    client.sendAudioData(buffer.copyOf(bytesRead))
                    packetCount++
                    if (packetCount % 1000 == 0L) {
                        LogServer.log("AirPlay 2: Sent $packetCount packets")
                    }
                } else if (bytesRead < 0) {
                    // Error reading audio
                    LogServer.log("AirPlay 2: Audio read error")
                    if (isCapturing) failStream("AirPlay 2 audio read error")
                    break
                }
            }
            LogServer.log("AirPlay 2: Stream loop ended")
        }
    }

    /**
     * Mid-stream health monitor mirroring the RAOP path's socket-EOF
     * detection: probes the AP2 RTSP control connection periodically and
     * tears the stream down when the receiver has dropped it. No auto-reconnect;
     * a fresh user start reconnects.
     */
    private fun startHealthMonitor() {
        healthJob?.cancel()
        healthJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive && isCapturing) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                val alive = ap2Client?.connectionAlive() ?: false
                if (!alive && isCapturing) {
                    failStream("AirPlay 2 connection lost")
                    break
                }
            }
        }
    }

    /**
     * Surface an AirPlay 2 error and tear the service down completely, matching
     * how the RAOP path surfaces failures (LogServer + onStateChanged(false)).
     */
    private fun failStream(error: String) {
        LogServer.log("AirPlay 2 stream error: $error")
        onStateChanged?.invoke(false)
        stopCapture()
        stopSelf()
    }

    private fun stopCapture() {
        if (!isCapturing && raopClient == null && ap2Client == null) return // Already stopped
        
        LogServer.log("stopCapture() called - cleaning up")
        
        // Pause media playback so audio doesn't continue on phone speaker
        pauseMediaPlayback()
        
        // Set flag first to stop loops
        isCapturing = false
        
        // Cancel the capture job
        captureJob?.cancel()
        captureJob = null
        healthJob?.cancel()
        healthJob = null

        // Stop and release audio record
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            LogServer.log("Error stopping audioRecord: ${e.message}")
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            LogServer.log("Error releasing audioRecord: ${e.message}")
        }
        audioRecord = null

        // Disconnect clients in background to avoid blocking main thread
        // IMPORTANT: Clear callback first to prevent recursion (disconnect triggers callback -> triggers stopCapture)
        raopClient?.callback = null
        
        serviceScope.launch(Dispatchers.IO) {
            try {
                raopClient?.disconnect()
            } catch (e: Exception) {
                LogServer.log("Error disconnecting RAOP client: ${e.message}")
            } finally {
                raopClient = null
            }
        }

        // Full AP2 teardown: disconnect() stops the NTP responder/PTP clock,
        // sends TEARDOWN, stops /feedback keepalive and closes the RTSP socket.
        serviceScope.launch(Dispatchers.IO) {
            try {
                ap2Client?.disconnect()
            } catch (e: Exception) {
                LogServer.log("Error disconnecting AirPlay 2 client: ${e.message}")
            } finally {
                ap2Client = null
            }
        }

        // Stop MediaProjection - this MUST be called to stop screen sharing indicator
        try {
            mediaProjection?.stop()
            LogServer.log("MediaProjection stopped")
        } catch (e: Exception) {
            LogServer.log("Error stopping MediaProjection: ${e.message}")
        }
        mediaProjection = null

        // Notify UI
        onStateChanged?.invoke(false)
        
        // Remove foreground notification
        stopForeground(STOP_FOREGROUND_REMOVE)
        
        LogServer.log("stopCapture() complete")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AirPlay Streaming",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when streaming to AirPlay speaker"
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.streaming_notification_title, deviceName))
            .setContentText(getString(R.string.streaming_notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.stop_streaming), stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    fun isCurrentlyStreaming(): Boolean = isCapturing

    /**
     * Set volume on the AirPlay speaker (0.0 to 1.0)
     */
    fun setVolume(volume: Float) {
        serviceScope.launch {
            try {
                if (ap2Client != null) {
                    ap2Client?.setVolume(volume)
                } else {
                    raopClient?.setVolume(volume)
                }
                LogServer.log("Volume set to ${(volume * 100).toInt()}%")
            } catch (e: Exception) {
                LogServer.log("Failed to set volume: ${e.message}")
            }
        }
    }

    /**
     * Pause media playback using AudioManager's media key event
     * This prevents audio from suddenly playing through phone speakers after disconnect
     */
    private fun pauseMediaPlayback() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audioManager.isMusicActive) {
                // Send media pause key event
                val eventTime = android.os.SystemClock.uptimeMillis()
                val downEvent = android.view.KeyEvent(
                    eventTime, eventTime,
                    android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, 0
                )
                val upEvent = android.view.KeyEvent(
                    eventTime, eventTime,
                    android.view.KeyEvent.ACTION_UP,
                    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, 0
                )
                audioManager.dispatchMediaKeyEvent(downEvent)
                audioManager.dispatchMediaKeyEvent(upEvent)
                LogServer.log("Paused media playback")
            }
        } catch (e: Exception) {
            LogServer.log("Failed to pause media: ${e.message}")
        }
    }
}
