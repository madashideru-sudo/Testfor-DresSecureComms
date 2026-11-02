/* Copyright © 2026 The DresOS Foundation. Licensed under the Apache License, Version 2.0. */
package com.dresos.dressecurecomms

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.dresos.dressecurecomms.crypto.AppLockManager
import com.dresos.dressecurecomms.databinding.ActivityMainBinding
import com.dresos.dressecurecomms.databinding.CardItemBinding
import com.dresos.dressecurecomms.util.applyScreenshotPolicy

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    private val requestNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenshotPolicy()
        binding = ActivityMainBinding.inflate(layoutInflater)

        if (prefs.getBoolean("app_lock", true)) {
            promptUnlock()
        } else {
            wireUi()
        }
    }

    private fun promptUnlock() {
        val bm = BiometricManager.from(this)
        when {
            bm.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS -> cryptoUnlock()
            bm.canAuthenticate(DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS -> credentialUnlock()
            else -> wireUi()
        }
    }

    private fun cryptoUnlock() {
        val enrolling = !AppLockManager.isEnrolled(this)
        val cipher = try {
            if (enrolling) AppLockManager.newEncryptCipher() else AppLockManager.newDecryptCipher(this)
        } catch (e: KeyPermanentlyInvalidatedException) {
            AppLockManager.reset(this)
            try { AppLockManager.newEncryptCipher() } catch (e2: Exception) { credentialUnlock(); return }
        } catch (e: Exception) {
            AppLockManager.reset(this)
            try { AppLockManager.newEncryptCipher() } catch (e2: Exception) { credentialUnlock(); return }
        }

        val reEnrolling = enrolling || !AppLockManager.isEnrolled(this)
        val prompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher ?: return denyUnlock()
                    val ok = try {
                        if (reEnrolling) { AppLockManager.finishEnroll(this@MainActivity, c); true }
                        else AppLockManager.verifyUnlock(this@MainActivity, c)
                    } catch (e: Exception) { false }
                    if (ok) wireUi() else denyUnlock()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) finish() else denyUnlock()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock DresSecureComms")
            .setSubtitle("Authenticate to decrypt your vault")
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel")
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun credentialUnlock() {
        val prompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = wireUi()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = finish()
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock DresSecureComms")
            .setAllowedAuthenticators(DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info)
    }

    private fun denyUnlock() {
        Toast.makeText(this, "Unlock failed", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun maybeRequestNotif() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun wireUi() {
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        card(binding.cardMessages, R.drawable.ic_sms, R.string.card_messages_title, R.string.card_messages_sub) {
            startActivity(Intent(this, MessagesActivity::class.java))
        }

        card(binding.cardCalls, R.drawable.ic_call, R.string.card_calls_title, R.string.card_calls_sub) {
            startActivity(Intent(this, CallsActivity::class.java))
        }

        card(binding.cardContacts, R.drawable.ic_contacts, R.string.card_contacts_title, R.string.card_contacts_sub) {
            startActivity(Intent(this, ContactsActivity::class.java))
        }

        card(binding.cardSettings, R.drawable.ic_settings, R.string.card_settings_title, R.string.card_settings_sub) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        card(binding.cardAbout, R.drawable.ic_info, R.string.card_about_title, R.string.card_about_sub) {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        maybeRequestNotif()
    }

    private fun card(
        b: CardItemBinding, @DrawableRes icon: Int, @StringRes title: Int, @StringRes sub: Int, onClick: () -> Unit
    ) {
        b.ic.setImageResource(icon)
        b.cardTitle.setText(title)
        b.cardSub.setText(sub)
        b.root.setOnClickListener { onClick() }
    }
}
