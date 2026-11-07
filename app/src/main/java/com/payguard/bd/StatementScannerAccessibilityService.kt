package com.payguard.bd

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Layer 3 Backup Verification Engine:
 * Automatically reads the bKash / Nagad / Rocket / Upay App Statement / Inbox screen
 * when SMS delivery is delayed by the telecom operator.
 */
class StatementScannerAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val rootNode = rootInActiveWindow ?: return
        val pkg = event?.packageName?.toString() ?: return
        val prefs = applicationContext.getSharedPreferences("payguard_prefs", Context.MODE_PRIVATE)
        val simNumber = prefs.getString("sim_number", "01711-889900") ?: "01711-889900"

        scanNodesRecursive(pkg, rootNode, simNumber)
    }

    private fun scanNodesRecursive(pkg: String, node: AccessibilityNodeInfo?, simNumber: String) {
        if (node == null) return
        val text = node.text?.toString() ?: ""
        if (text.contains("TrxID", ignoreCase = true) || text.contains("Txn", ignoreCase = true)) {
            PaymentRegexParser.parseAppNotification(pkg, text, simNumber, source = "statement_scanner")?.let { tx ->
                PayGuardForegroundService.enqueueAndPushInstant(applicationContext, tx)
            }
        }
        for (i in 0 until node.childCount) {
            scanNodesRecursive(pkg, node.getChild(i), simNumber)
        }
    }

    override fun onInterrupt() {}
}
