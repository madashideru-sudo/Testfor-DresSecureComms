/* Copyright © 2026 The DresOS Foundation. Licensed under the Apache License, Version 2.0. */
package com.dresos.dressecurecomms.data

import android.content.ContentValues
import android.content.Context
import android.provider.CallLog
import com.dresos.dressecurecomms.crypto.CryptoManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object CallHistory {

    data class Entry(
        val id: Long,
        val number: String,
        val type: Int,
        val date: Long,
        val duration: Long,
        val reason: String = ""
    )

    private const val MAX = 500
    private const val LOCAL_BLOCKED_FILE = "local_call_history.dat"

    fun load(ctx: Context): List<Entry> {
        val out = ArrayList<Entry>()
        val cols = arrayOf(
            CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE,
            CallLog.Calls.DATE, CallLog.Calls.DURATION
        )

        // 1. Query Android System CallLog
        try {
            val cursor = ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI, cols, null, null, "${CallLog.Calls.DATE} DESC"
            )
            cursor?.use { c ->
                val idi = c.getColumnIndexOrThrow(CallLog.Calls._ID)
                val ni = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val ti = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val di = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val dui = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                while (c.moveToNext() && out.size < MAX) {
                    out.add(
                        Entry(
                            c.getLong(idi),
                            c.getString(ni) ?: "Unknown",
                            c.getInt(ti),
                            c.getLong(di),
                            c.getLong(dui)
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
        }

        // 2. Merge local records (blocked calls and rejected calls stored safely)
        val localRecords = loadLocal(ctx)
        for (local in localRecords) {
            // Check if not already in system log (within 2 seconds)
            if (out.none { it.number == local.number && Math.abs(it.date - local.date) < 2000 }) {
                out.add(local)
            }
        }

        out.sortByDescending { it.date }
        return out.take(MAX)
    }

    private fun loadLocal(ctx: Context): List<Entry> {
        val f = File(ctx.filesDir, LOCAL_BLOCKED_FILE)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(CryptoManager.decrypt(f.readText()))
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Entry(
                    o.optLong("id", -1L),
                    o.getString("n"),
                    o.getInt("t"),
                    o.getLong("d"),
                    o.optLong("du", 0L),
                    o.optString("r", "")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveLocal(ctx: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.take(200).forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("n", it.number)
                    .put("t", it.type)
                    .put("d", it.date)
                    .put("du", it.duration)
                    .put("r", it.reason)
            )
        }
        File(ctx.filesDir, LOCAL_BLOCKED_FILE).writeText(CryptoManager.encrypt(arr.toString()))
    }

    fun recordBlocked(ctx: Context, number: String, reason: String) {
        val entry = Entry(
            System.currentTimeMillis(),
            number.trim(),
            CallLog.Calls.BLOCKED_TYPE,
            System.currentTimeMillis(),
            0L,
            reason
        )
        // Store in local secure list
        val current = loadLocal(ctx).toMutableList()
        current.add(0, entry)
        saveLocal(ctx, current)

        // Also try writing to system CallLog
        try {
            val values = ContentValues().apply {
                put(CallLog.Calls.NUMBER, number.trim())
                put(CallLog.Calls.TYPE, CallLog.Calls.BLOCKED_TYPE)
                put(CallLog.Calls.DATE, entry.date)
                put(CallLog.Calls.DURATION, 0)
                put(CallLog.Calls.NEW, 1)
            }
            ctx.contentResolver.insert(CallLog.Calls.CONTENT_URI, values)
        } catch (_: Exception) {
        }
    }

    fun recordRejected(ctx: Context, number: String) {
        val entry = Entry(
            System.currentTimeMillis(),
            number.trim(),
            CallLog.Calls.REJECTED_TYPE,
            System.currentTimeMillis(),
            0L,
            "Recusada"
        )
        val current = loadLocal(ctx).toMutableList()
        current.add(0, entry)
        saveLocal(ctx, current)
    }

    fun delete(ctx: Context, id: Long) {
        try {
            ctx.contentResolver.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID}=?", arrayOf(id.toString()))
        } catch (_: Exception) {
        }
        val current = loadLocal(ctx).filterNot { it.id == id }
        saveLocal(ctx, current)
    }

    fun clear(ctx: Context) {
        try {
            ctx.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null)
        } catch (_: Exception) {
        }
        File(ctx.filesDir, LOCAL_BLOCKED_FILE).delete()
    }

    fun typeLabel(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "Recebida (Aceita)"
        CallLog.Calls.OUTGOING_TYPE -> "Realizada (Aceita)"
        CallLog.Calls.MISSED_TYPE -> "Perdida"
        CallLog.Calls.REJECTED_TYPE -> "Recusada"
        CallLog.Calls.BLOCKED_TYPE -> "Bloqueada (0* ou Regra)"
        CallLog.Calls.VOICEMAIL_TYPE -> "Caixa Postal"
        else -> "Chamada"
    }

    // Color definitions: Bloqueadas = Vermelho, Aceitas = Verde, Recusadas = Cinza
    fun colorForType(type: Int): Int = when (type) {
        CallLog.Calls.BLOCKED_TYPE -> 0xFFEF4444.toInt() // Vermelho (#EF4444)
        CallLog.Calls.INCOMING_TYPE, CallLog.Calls.OUTGOING_TYPE -> 0xFF22C55E.toInt() // Verde (#22C55E)
        CallLog.Calls.REJECTED_TYPE, CallLog.Calls.MISSED_TYPE -> 0xFF94A3B8.toInt() // Cinza (#94A3B8)
        else -> 0xFF94A3B8.toInt()
    }
}
