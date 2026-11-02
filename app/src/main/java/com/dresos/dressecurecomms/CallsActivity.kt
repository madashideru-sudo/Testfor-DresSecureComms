/* Copyright © 2026 The DresOS Foundation. Licensed under the Apache License, Version 2.0. */
package com.dresos.dressecurecomms

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.core.widget.doAfterTextChanged
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dresos.dressecurecomms.data.CallHistory
import com.dresos.dressecurecomms.databinding.ActivityCallsBinding
import com.dresos.dressecurecomms.ui.TwoLineAdapter
import com.dresos.dressecurecomms.util.TimeFmt
import com.dresos.dressecurecomms.util.Actions
import com.dresos.dressecurecomms.util.Contacts
import com.dresos.dressecurecomms.data.SpamStore
import com.dresos.dressecurecomms.util.applyScreenshotPolicy
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CallsActivity : AppCompatActivity() {

    private lateinit var b: ActivityCallsBinding
    private lateinit var adapter: TwoLineAdapter<CallHistory.Entry>
    private var pendingDeleteId: Long? = null
    private var pendingClear = false
    private var nameByNumber: Map<String, String> = emptyMap()

    private val requestCall =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { g ->
            if (g) place() else snack("Call permission denied")
        }

    private val requestReadLog =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { reload() }

    private val requestWriteLog =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { g ->
            if (g) {
                when {
                    pendingClear -> { CallHistory.clear(this); pendingClear = false }
                    pendingDeleteId != null -> { CallHistory.delete(this, pendingDeleteId!!); pendingDeleteId = null }
                }
                reload()
            } else snack("Call log permission denied")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenshotPolicy()
        b = ActivityCallsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toolbar.title = getString(R.string.card_calls_title)
        b.toolbar.setNavigationIcon(R.drawable.ic_back)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.toolbar.menu.add(0, 1, 0, "Clear call history").setOnMenuItemClickListener { confirmClear(); true }

        adapter = TwoLineAdapter(this, emptyList()) { e ->
            val prefix = when (e.type) {
                android.provider.CallLog.Calls.BLOCKED_TYPE -> "[Bloqueada] "
                android.provider.CallLog.Calls.INCOMING_TYPE, android.provider.CallLog.Calls.OUTGOING_TYPE -> "[Aceita] "
                android.provider.CallLog.Calls.REJECTED_TYPE, android.provider.CallLog.Calls.MISSED_TYPE -> "[Recusada] "
                else -> ""
            }
            Triple(nameFor(e.number), "$prefix${CallHistory.typeLabel(e.type)}${durationLabel(e.duration)}", TimeFmt.rel(e.date))
        }
        adapter.colorBind = { e -> CallHistory.colorForType(e.type) }

        b.list.adapter = adapter
        b.list.setOnItemClickListener { _, _, pos, _ -> entryActions(adapter.getItem(pos)) }

        b.number.doAfterTextChanged { applyFilter() }
        intent.getStringExtra("number")?.let { b.number.setText(it) }
        (if (intent?.data?.scheme == "tel") intent.data?.schemeSpecificPart else null)?.let { b.number.setText(it) }

        b.callBtn.setOnClickListener {
            if (b.number.text.toString().isBlank()) return@setOnClickListener
            if (hasCall()) place() else requestCall.launch(Manifest.permission.CALL_PHONE)
        }

        b.pasteBtn.setOnClickListener {
            val text = Actions.clipboardText(this)
            if (text.isNullOrEmpty()) { snack(getString(R.string.clipboard_empty)); return@setOnClickListener }
            val field = b.number.text
            if (field == null) { b.number.setText(text) } else { field.insert(field.length, text) }
            b.number.setSelection(b.number.text?.length ?: 0)
        }

        b.backspaceBtn.setOnClickListener {
            val field = b.number.text ?: return@setOnClickListener
            if (field.isNotEmpty()) field.delete(field.length - 1, field.length)
        }
        b.backspaceBtn.setOnLongClickListener {
            b.number.text?.clear(); true
        }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED)
            reload()
        else
            requestReadLog.launch(Manifest.permission.READ_CALL_LOG)
    }

    private fun reload() {
        lifecycleScope.launch {
            val (entries, names) = withContext(Dispatchers.IO) {
                val e = CallHistory.load(this@CallsActivity)
                e to Contacts.nameMap(this@CallsActivity, e.map { it.number })
            }
            nameByNumber = names
            all = entries
            applyFilter()
        }
    }

    private var all: List<CallHistory.Entry> = emptyList()

    private fun applyFilter() {
        val q = b.number.text?.toString()?.trim()?.lowercase().orEmpty()
        adapter.setItems(
            if (q.isEmpty()) all
            else all.filter {
                it.number.lowercase().contains(q) ||
                    (nameByNumber[it.number]?.lowercase()?.contains(q) == true)
            }
        )
    }

    private fun nameFor(number: String): String {
        val name = nameByNumber[number]
        return if (name.isNullOrBlank()) number else "$name  ($number)"
    }

    private fun durationLabel(d: Long): String =
        if (d <= 0L) "" else " · ${d / 60}m ${d % 60}s"

    private fun entryActions(e: CallHistory.Entry) {
        val blocked = SpamStore.isBlocked(this, e.number)
        val opts = arrayOf(
            "Call back",
            "Copy number",
            if (blocked) "Unblock number" else "Block number",
            "Delete from history"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(nameFor(e.number))
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> { b.number.setText(e.number); b.callBtn.performClick() }
                    1 -> { Actions.copy(this, e.number, "Copied"); snack("Copied to clipboard") }
                    2 -> {
                        if (blocked) {
                            SpamStore.allow(this, e.number)
                            snack("Unblocked ${e.number}")
                        } else {
                            SpamStore.block(this, e.number, "Blocked from call history")
                            snack("Blocked ${e.number}")
                        }
                    }
                    3 -> deleteEntry(e.id)
                }
            }
            .show()
    }

    private fun deleteEntry(id: Long) {
        pendingDeleteId = id
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            CallHistory.delete(this, id)
            reload()
        } else {
            requestWriteLog.launch(Manifest.permission.WRITE_CALL_LOG)
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear call history?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                pendingClear = true
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
                    CallHistory.clear(this)
                    pendingClear = false
                    reload()
                } else {
                    requestWriteLog.launch(Manifest.permission.WRITE_CALL_LOG)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun place() {
        val raw = b.number.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return
        val uri = Uri.parse("tel:${Uri.encode(raw)}")
        val tm = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        try {
            if (tm != null && ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                tm.placeCall(uri, Bundle())
            } else {
                startActivity(Intent(Intent.ACTION_CALL, uri))
            }
        } catch (e: SecurityException) {
            snack("Permission denied")
        } catch (e: Exception) {
            snack("Could not place call: ${e.message}")
        }
    }

    private fun hasCall(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    private fun snack(msg: String) =
        Snackbar.make(b.root, msg, Snackbar.LENGTH_LONG).show()
}
