package com.payguard.bd

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("payguard_prefs", Context.MODE_PRIVATE)
        val edtWsUrl = findViewById<EditText>(R.id.edtWsUrl)
        val edtServerUrl = findViewById<EditText>(R.id.edtServerUrl)
        val edtSecretToken = findViewById<EditText>(R.id.edtSecretToken)
        val edtSimNumber = findViewById<EditText>(R.id.edtSimNumber)
        val txtStatus = findViewById<TextView>(R.id.txtServiceStatus)
        val txtLog = findViewById<TextView>(R.id.txtLiveLog)
        val txtCounters = findViewById<TextView>(R.id.txtQueueCounters)

        edtWsUrl.setText(prefs.getString("ws_url", "wss://pay.yourdomain.com:8080/app/payguard-key"))
        edtServerUrl.setText(prefs.getString("api_url", "https://pay.yourdomain.com/api/device/webhook"))
        edtSecretToken.setText(prefs.getString("secret_token", "PG_LIVE_SECRET_998877"))
        edtSimNumber.setText(prefs.getString("sim_number", "01711-889900"))

        findViewById<Button>(R.id.btnGrantSms).setOnClickListener {
            val perms = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 101)
        }

        findViewById<Button>(R.id.btnGrantPushListener).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.btnGrantStatementScanner).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnIgnoreBattery).setOnClickListener {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Battery Optimization Already Ignored!", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnStartGateway).setOnClickListener {
            prefs.edit()
                .putString("ws_url", edtWsUrl.text.toString().trim())
                .putString("api_url", edtServerUrl.text.toString().trim())
                .putString("secret_token", edtSecretToken.text.toString().trim())
                .putString("sim_number", edtSimNumber.text.toString().trim())
                .apply()

            startForegroundService(Intent(this, PayGuardForegroundService::class.java))
            txtStatus.text = "STATUS: 24/7 WEBSOCKET TUNNEL ONLINE"
            txtLog.text = "[Connected] Listening to bKash, Nagad, Rocket & Upay in real time..."
            Toast.makeText(this, "PayGuard 24/7 Service Started!", Toast.LENGTH_SHORT).show()
        }

        // 1-Click Test Transaction Push Button
        findViewById<Button>(R.id.btnTestWebhook).setOnClickListener {
            val testTrx = "BK" + (10000000..99999999).random().toString()
            val sim = edtSimNumber.text.toString().trim()
            val sample = QueuedTransaction(
                provider = "bkash",
                trxId = testTrx,
                amount = 1250.00,
                customerPhone = "01812345678",
                simNumber = sim,
                sourceType = "push_notification",
                senderId = "APP_NOTIFICATION",
                rawBody = "You have received Tk 1250.00 from 01812345678. TrxID $testTrx"
            )
            PayGuardForegroundService.enqueueAndPushInstant(this, sample)
            txtLog.text = "[Test Push Queued] TrxID: $testTrx (Tk 1250.00) -> Pushing to Server..."
            Toast.makeText(this, "Queued Test TrxID: $testTrx", Toast.LENGTH_SHORT).show()
        }

        // Poll local Room DB counters every 2 seconds for live status
        uiScope.launch {
            val db = OfflineQueueDatabase.get(applicationContext)
            while (isActive) {
                val pending = withContext(Dispatchers.IO) { db.dao().getPendingCount() }
                txtCounters.text = "Offline Pending Queue: $pending | 24/7 Dual-Reader Ready"
                delay(2000)
            }
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }
}
