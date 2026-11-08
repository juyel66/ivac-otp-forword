package com.ivac.assistant.core.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.util.Locale

/**
 * Helper to check battery optimization state and guide the user through manufacturer background policies.
 */
object BatteryOptimizationHelper {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    @SuppressLint("BatteryLife")
    fun createIgnoreBatteryOptimizationsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    fun getManufacturerGuide(): String {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> {
                "Xiaomi / MIUI Device Detected:\n" +
                        "1. Enable 'Autostart' for IVAC Assistant Mobile.\n" +
                        "2. Set Battery Saver to 'No Restrictions'.\n" +
                        "3. In Recent Apps, lock the app window with the padlock icon."
            }
            manufacturer.contains("samsung") -> {
                "Samsung OneUI Device Detected:\n" +
                        "1. Go to Settings > Battery > Background usage limits.\n" +
                        "2. Add IVAC Assistant to 'Never sleeping apps'.\n" +
                        "3. Turn off 'Put unused apps to sleep'."
            }
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                "Huawei / Honor Device Detected:\n" +
                        "1. Go to Settings > Battery > App Launch.\n" +
                        "2. Find IVAC Assistant, switch to 'Manage Manually'.\n" +
                        "3. Enable 'Auto-launch', 'Secondary launch', and 'Run in background'."
            }
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> {
                "ColorOS / Realme / OnePlus Device Detected:\n" +
                        "1. Go to App Info > Battery usage > Allow background activity.\n" +
                        "2. In App Management, enable 'Auto launch'."
            }
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                "Vivo / FuntouchOS Device Detected:\n" +
                        "1. Go to Settings > Battery > High background power consumption.\n" +
                        "2. Allow IVAC Assistant to run in the background."
            }
            else -> {
                "Standard Android Device:\n" +
                        "Please disable Battery Optimization for IVAC Assistant to ensure timely OTP delivery when your screen is locked."
            }
        }
    }
}

