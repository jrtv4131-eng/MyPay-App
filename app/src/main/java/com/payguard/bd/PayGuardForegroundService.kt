package com.payguard.bd

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PayGuardForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        startPersistentNotification()
        connectWebSocketTunnel()
        startOfflineQueueSyncLoop()
    }

    private fun startPersistentNotification() {
        val channelId = "payguard_live_tunnel"
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            channelId,
            "PayGuard 24/7 Gateway Tunnel",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("MyPay BD — 24/7 Auto-Verify Active")
            .setContentText("WebSocket Connected • Listening to Push, SMS & App Statement")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    private fun connectWebSocketTunnel() {
        val prefs = getSharedPreferences("payguard_prefs", Context.MODE_PRIVATE)
        val wsUrl = prefs.getString("ws_url", "wss://pay.yourdomain.com:8080/app/payguard-key") ?: return

        val request = Request.Builder().url(wsUrl).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sendHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // If server sends RequestStatementScanEvent when SMS is delayed
                if (text.contains("RequestStatementScanEvent")) {
                    syncUnsyncedTransactions()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                serviceScope.launch {
                    delay(3000)
                    connectWebSocketTunnel()
                }
            }
        })
    }

    private fun startOfflineQueueSyncLoop() {
        serviceScope.launch {
            while (isActive) {
                syncUnsyncedTransactions()
                sendHeartbeat()
                delay(5000)
            }
        }
    }

    private fun syncUnsyncedTransactions() {
        serviceScope.launch {
            val db = OfflineQueueDatabase.get(applicationContext)
            val pendingList = db.dao().getUnsynced()
            if (pendingList.isEmpty()) return@launch

            val prefs = getSharedPreferences("payguard_prefs", Context.MODE_PRIVATE)
            val apiUrl = prefs.getString("api_url", "https://pay.yourdomain.com/api/device/webhook") ?: return@launch
            val secretToken = prefs.getString("secret_token", "PG_LIVE_SECRET_998877") ?: ""

            for (tx in pendingList) {
                val payload = JSONObject().apply {
                    put("provider", tx.provider)
                    put("trx_id", tx.trxId)
                    put("amount", tx.amount)
                    put("customer_number", tx.customerPhone)
                    put("sim_number", tx.simNumber)
                    put("source_type", tx.sourceType)
                    put("sender_id", tx.senderId)
                    put("raw_body", tx.rawBody)
                }

                // 1. Push instant WebSocket frame
                webSocket?.send(payload.toString())

                // 2. Confirm via REST API with Bearer Secret Token
                val req = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer $secretToken")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                runCatching {
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            db.dao().markSynced(tx.trxId)
                        }
                    }
                }
            }
        }
    }

    private fun sendHeartbeat() {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val hb = JSONObject().apply {
            put("event", "device.heartbeat")
            put("battery", battery)
            put("timestamp", System.currentTimeMillis())
        }
        webSocket?.send(hb.toString())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        webSocket?.close(1000, "Service stopped")
        super.onDestroy()
    }

    companion object {
        fun enqueueAndPushInstant(context: Context, tx: QueuedTransaction) {
            CoroutineScope(Dispatchers.IO).launch {
                val db = OfflineQueueDatabase.get(context)
                val rowId = db.dao().insert(tx)
                if (rowId != -1L) {
                    val intent = Intent(context, PayGuardForegroundService::class.java)
                    context.startForegroundService(intent)
                }
            }
        }
    }
}
