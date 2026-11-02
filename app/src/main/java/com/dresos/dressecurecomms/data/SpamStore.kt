/* Copyright © 2026 The DresOS Foundation. Licensed under the Apache License, Version 2.0. */
package com.dresos.dressecurecomms.data

import android.content.Context
import com.dresos.dressecurecomms.crypto.CryptoManager
import com.dresos.dressecurecomms.util.PhoneKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object SpamStore {
    private const val FILE = "spam_rules.dat"

    data class Rule(val number: String, val blocked: Boolean, val reason: String, val at: Long)

    fun load(ctx: Context): List<Rule> {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(CryptoManager.decrypt(f.readText()))
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Rule(o.getString("n"), o.optBoolean("b", true), o.optString("r", ""), o.optLong("t", 0L))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, list: List<Rule>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("n", it.number).put("b", it.blocked)
                    .put("r", it.reason).put("t", it.at)
            )
        }
        File(ctx.filesDir, FILE).writeText(CryptoManager.encrypt(arr.toString()))
    }

    private fun digits(s: String): String = s.filter { it.isDigit() }

    fun matches(rulePattern: String, candidate: String): Boolean {
        val p = rulePattern.trim()
        val c = candidate.trim()
        if (p.isEmpty() || c.isEmpty()) return false

        // Prefix wildcard rule (e.g. "0*", "0800*", "+1*")
        if (p.endsWith("*")) {
            val prefix = p.dropLast(1).trim()
            if (prefix.isEmpty()) return true

            val cDigits = digits(c)
            val pDigits = digits(prefix)

            // Direct string prefix or digits prefix match
            if (c.startsWith(prefix) || (pDigits.isNotEmpty() && cDigits.startsWith(pDigits))) {
                return true
            }
            return false
        }

        return PhoneKey.same(p, c) || digits(p) == digits(c)
    }

    fun ruleFor(ctx: Context, number: String): Rule? {
        val list = load(ctx)
        // First check exact match
        list.firstOrNull { !it.number.endsWith("*") && matches(it.number, number) }?.let { return it }
        // Then check wildcard pattern matches
        return list.firstOrNull { it.number.endsWith("*") && matches(it.number, number) }
    }

    fun isBlocked(ctx: Context, number: String): Boolean = ruleFor(ctx, number)?.blocked == true

    fun isAllowed(ctx: Context, number: String): Boolean = ruleFor(ctx, number)?.blocked == false

    fun block(ctx: Context, pattern: String, reason: String) = put(ctx, pattern, true, reason)

    fun allow(ctx: Context, pattern: String) = put(ctx, pattern, false, "allowed by you")

    private fun put(ctx: Context, pattern: String, blocked: Boolean, reason: String) {
        val pat = pattern.trim()
        if (pat.isBlank()) return
        val list = load(ctx).filterNot { it.number.equals(pat, ignoreCase = true) || matches(it.number, pat) }.toMutableList()
        list.add(Rule(pat, blocked, reason, System.currentTimeMillis()))
        save(ctx, list)
    }

    fun remove(ctx: Context, pattern: String) {
        val pat = pattern.trim()
        save(ctx, load(ctx).filterNot { it.number.equals(pat, ignoreCase = true) })
    }

    fun clear(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
    }
}
