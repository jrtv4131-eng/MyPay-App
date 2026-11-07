package com.payguard.bd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

object PaymentRegexParser {
    // Strict Official Sender ID Whitelist (Prevents Fake SMS from Personal Numbers)
    private val OFFICIAL_SENDERS = setOf("BKASH", "NAGAD", "16216", "UPAY")

    private val OFFICIAL_PACKAGES = mapOf(
        "com.bKash.customerapp" to "bkash",
        "com.konasl.nagad" to "nagad",
        "com.dbbl.mbs.apps.main" to "rocket",
        "bd.com.upay.customer" to "upay"
    )

    private val AMOUNT_REGEX = Regex("(?:received|Cash-In|Tk|BDT)\\s*(?:Tk\\.?|BDT)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)
    private val TRX_REGEX = Regex("(?:TrxID|TxnId|Trans ID|Txn ID)[:\\s]+([A-Z0-9]{6,20})", RegexOption.IGNORE_CASE)
    private val SENDER_PHONE_REGEX = Regex("(?:from|by|A/C)\\s*(01[3-9][0-9]{8})", RegexOption.IGNORE_CASE)

    fun parseSms(senderAddress: String, body: String, simNumber: String): QueuedTransaction? {
        val cleanSender = senderAddress.uppercase().trim()
        if (cleanSender !in OFFICIAL_SENDERS) return null

        val provider = when {
            cleanSender.contains("BKASH") -> "bkash"
            cleanSender.contains("NAGAD") -> "nagad"
            cleanSender.contains("16216") -> "rocket"
            cleanSender.contains("UPAY") -> "upay"
            else -> return null
        }

        val amount = AMOUNT_REGEX.find(body)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: return null
        val trxId = TRX_REGEX.find(body)?.groupValues?.get(1)?.uppercase() ?: return null
        val phone = SENDER_PHONE_REGEX.find(body)?.groupValues?.get(1) ?: "UNKNOWN"

        return QueuedTransaction(
            provider = provider,
            trxId = trxId,
            amount = amount,
            customerPhone = phone,
            simNumber = simNumber,
            sourceType = "sms_reader",
            senderId = cleanSender,
            rawBody = body
        )
    }

    fun parseAppNotification(packageName: String, text: String, simNumber: String, source: String = "push_notification"): QueuedTransaction? {
        val provider = OFFICIAL_PACKAGES[packageName] ?: return null
        val amount = AMOUNT_REGEX.find(text)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: return null
        val trxId = TRX_REGEX.find(text)?.groupValues?.get(1)?.uppercase() ?: return null
        val phone = SENDER_PHONE_REGEX.find(text)?.groupValues?.get(1) ?: "UNKNOWN"

        return QueuedTransaction(
            provider = provider,
            trxId = trxId,
            amount = amount,
            customerPhone = phone,
            simNumber = simNumber,
            sourceType = source,
            senderId = if (source == "statement_scanner") "STATEMENT_SCANNER" else "APP_NOTIFICATION",
            rawBody = text
        )
    }
}

class SmsBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val prefs = context.getSharedPreferences("payguard_prefs", Context.MODE_PRIVATE)
            val simNumber = prefs.getString("sim_number", "01711-889900") ?: "01711-889900"

            for (msg in Telephony.Sms.Intents.getMessagesFromIntent(intent)) {
                val sender = msg.displayOriginatingAddress ?: continue
                val body = msg.displayMessageBody ?: continue
                PaymentRegexParser.parseSms(sender, body, simNumber)?.let { tx ->
                    PayGuardForegroundService.enqueueAndPushInstant(context, tx)
                }
            }
        }
    }
}

class PaymentNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = applicationContext.getSharedPreferences("payguard_prefs", Context.MODE_PRIVATE)
        val simNumber = prefs.getString("sim_number", "01711-889900") ?: "01711-889900"

        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val fullText = "$title $text"

        PaymentRegexParser.parseAppNotification(sbn.packageName, fullText, simNumber)?.let { tx ->
            PayGuardForegroundService.enqueueAndPushInstant(applicationContext, tx)
        }
    }
}
