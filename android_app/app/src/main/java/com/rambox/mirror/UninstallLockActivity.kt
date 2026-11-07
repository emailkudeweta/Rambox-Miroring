package com.rambox.mirror

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class UninstallLockActivity : AppCompatActivity() {

    companion object {
        var isShowing: Boolean = false
    }

    private lateinit var etLockPassword: EditText
    private lateinit var tvLockError: TextView
    private lateinit var btnUnlockProceed: Button
    private lateinit var btnCancelHome: Button
    private lateinit var prefs: SharedPreferences
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_uninstall_lock)

        isShowing = true
        prefs = getSharedPreferences("rambox_prefs", Context.MODE_PRIVATE)
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, RamboxDeviceAdminReceiver::class.java)

        etLockPassword = findViewById(R.id.etLockPassword)
        tvLockError = findViewById(R.id.tvLockError)
        btnUnlockProceed = findViewById(R.id.btnUnlockProceed)
        btnCancelHome = findViewById(R.id.btnCancelHome)

        btnUnlockProceed.setOnClickListener {
            handleUnlock()
        }

        btnCancelHome.setOnClickListener {
            cancelAndGoHome()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                cancelAndGoHome()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        isShowing = true
    }

    override fun onPause() {
        super.onPause()
        isShowing = false
    }

    override fun onDestroy() {
        super.onDestroy()
        isShowing = false
    }

    private fun handleUnlock() {
        val now = System.currentTimeMillis()
        val lockedUntil = prefs.getLong("uninstall_lock_until", 0L)
        if (now < lockedUntil) {
            val remainSec = (lockedUntil - now) / 1000
            tvLockError.text = "⚠️ Terlalu banyak percobaan salah. Coba lagi dalam ${remainSec} detik."
            tvLockError.visibility = View.VISIBLE
            return
        }

        val inputPass = etLockPassword.text.toString().trim()
        val defaultPin = SecureConfig.DEFAULT_PIN
        val savedSecret = prefs.getString("secret_dial_key", "") ?: ""
        val roomPin = prefs.getString("room_pin", defaultPin) ?: defaultPin

        if (inputPass.isEmpty()) {
            tvLockError.text = "❌ Masukkan password!"
            tvLockError.visibility = View.VISIBLE
            return
        }

        // K1 & M6 FIX: Verifikasi custom PIN & rate limiting
        val hasCustomPin = (savedSecret.isNotEmpty() && savedSecret != defaultPin) ||
                           (roomPin.isNotEmpty() && roomPin != defaultPin)
        val isMatch = if (hasCustomPin) {
            (savedSecret.isNotEmpty() && inputPass == savedSecret) || inputPass == roomPin
        } else {
            MasterPasswords.isValidPassword(inputPass) || inputPass == defaultPin
        }

        if (!isMatch) {
            val failed = prefs.getInt("uninstall_fail_count", 0) + 1
            if (failed >= 5) {
                prefs.edit()
                    .putLong("uninstall_lock_until", now + 300_000L)
                    .putInt("uninstall_fail_count", 0)
                    .apply()
                tvLockError.text = "❌ 5x salah! Layar uninstall dikunci selama 5 menit."
            } else {
                prefs.edit().putInt("uninstall_fail_count", failed).apply()
                tvLockError.text = "❌ Password salah! Sisa percobaan: ${5 - failed}"
            }
            tvLockError.visibility = View.VISIBLE
            etLockPassword.setText("")
            return
        }

        prefs.edit().putInt("uninstall_fail_count", 0).putLong("uninstall_lock_until", 0L).apply()
        tvLockError.visibility = View.GONE

        prefs.edit().putLong("uninstall_authorized_until", System.currentTimeMillis() + 60000).apply()

        try {
            if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
                devicePolicyManager.setUninstallBlocked(adminComponent, packageName, false)
                devicePolicyManager.clearUserRestriction(adminComponent, android.os.UserManager.DISALLOW_UNINSTALL_APPS)
                devicePolicyManager.clearUserRestriction(adminComponent, android.os.UserManager.DISALLOW_APPS_CONTROL)
                devicePolicyManager.clearDeviceOwnerApp(packageName)
            }
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                devicePolicyManager.removeActiveAdmin(adminComponent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        Toast.makeText(this, "🔓 Verifikasi Sukses! Membuka dialog uninstall...", Toast.LENGTH_SHORT).show()

        try {
            val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(uninstallIntent)
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                    data = Uri.parse("package:$packageName")
                    putExtra(Intent.EXTRA_RETURN_RESULT, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(fallbackIntent)
            } catch (e2: Exception) {}
        }

        finishAndRemoveTask()
    }

    private fun cancelAndGoHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finishAndRemoveTask()
    }
}
