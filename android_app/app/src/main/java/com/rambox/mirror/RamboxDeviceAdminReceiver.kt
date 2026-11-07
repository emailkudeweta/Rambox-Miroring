package com.rambox.mirror

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager

class RamboxDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        val COMPONENT_NAME get() = { ctx: Context ->
            ComponentName(ctx, RamboxDeviceAdminReceiver::class.java)
        }

        fun isDeviceOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            return dpm?.isDeviceOwnerApp(context.packageName) == true
        }

        fun applyMirroringPolicies(context: Context) {
            try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
                val admin = ComponentName(context, RamboxDeviceAdminReceiver::class.java)

                if (!dpm.isDeviceOwnerApp(context.packageName)) return

                try {
                    dpm.setKeyguardDisabled(admin, true)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                try {
                    dpm.setMaximumTimeToLock(admin, 0L)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                try {
                    dpm.setScreenCaptureDisabled(admin, false)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                try {
                    dpm.setUninstallBlocked(admin, context.packageName, true)
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS)
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                try {
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
                } catch (_: Exception) {}
                try {
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
                } catch (_: Exception) {}

                android.util.Log.d("RamboxDO", "Device Owner policies applied: keyguard=disabled, timeout=unlimited, capture=allowed")

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun revertMirroringPolicies(context: Context) {
            try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
                val admin = ComponentName(context, RamboxDeviceAdminReceiver::class.java)
                if (!dpm.isDeviceOwnerApp(context.packageName)) return

                try { dpm.setKeyguardDisabled(admin, false) } catch (e: Exception) {}
                try { dpm.setMaximumTimeToLock(admin, 30_000L) } catch (e: Exception) {}
                // M2 FIX: Bersihkan seluruh restriksi Device Owner saat revert policies
                try { dpm.setUninstallBlocked(admin, context.packageName, false) } catch (e: Exception) {}
                try { dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_UNINSTALL_APPS) } catch (e: Exception) {}
                try { dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_APPS_CONTROL) } catch (e: Exception) {}
                try { dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_FACTORY_RESET) } catch (e: Exception) {}
                try { dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_SAFE_BOOT) } catch (e: Exception) {}
                android.util.Log.d("RamboxDO", "Device Owner policies reverted.")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun dismissKeyguard(context: Context) {
            try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
                val admin = ComponentName(context, RamboxDeviceAdminReceiver::class.java)
                if (dpm.isDeviceOwnerApp(context.packageName)) {
                    dpm.setKeyguardDisabled(admin, true)
                    android.util.Log.d("RamboxDO", "Keyguard dismissed via Device Owner.")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        applyMirroringPolicies(context)
        PrivilegeBootstrap.grantSilentPermissions(context)
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        applyMirroringPolicies(context)
        PrivilegeBootstrap.grantSilentPermissions(context)
        android.util.Log.d("RamboxDO", "Device Admin enabled.")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "⚠️ Peringatan: Menonaktifkan Pengelola Perangkat akan mencabut seluruh proteksi Rambox Mirror!"
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        android.util.Log.d("RamboxDO", "Device Admin disabled.")
    }
}
