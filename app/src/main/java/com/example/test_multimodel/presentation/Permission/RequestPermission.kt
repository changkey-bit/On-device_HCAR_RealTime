package com.example.test_multimodel.presentation.Permission

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.provider.Settings
import android.net.Uri

// 권한 요청 Class
class RequestPermission(private val activity: Activity) {

    companion object {
        const val REQUEST_CODE_PERMISSIONS = 1001
        const val REQUEST_CODE_BACKGROUND_LOCATION = 1002
    }

    // 권한 요청 메서드
    fun requestPermissions() {
        if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(activity, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(activity, android.Manifest.permission.HIGH_SAMPLING_RATE_SENSORS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION, // 자세한 위치 정보 권한
                    android.Manifest.permission.RECORD_AUDIO // 오디오 녹음 권한
                ),
                REQUEST_CODE_PERMISSIONS
            )
        } else if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // 백그라운드 위치 권한 요청 (자세한 위치 정보 권한을 받은 후에 사용할 수 있음)
            requestBackgroundLocationPermission()
        }

//        // 오버레이 권한 확인 및 요청 / 갤럭시 울트라에서 X
//        if (!checkOverlayPermission(activity)) {
//            requestOverlayPermission(activity)
//        }
    }

    // 배터리 최적화 무시 요청
    fun requestDisableBatteryOptimization() {
        val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = activity.packageName
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            try {
                activity.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(activity, "배터리 최적화 무시를 허용해야 합니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 백그라운드 위치 권한 요청
    private fun requestBackgroundLocationPermission() {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            REQUEST_CODE_BACKGROUND_LOCATION
        )
    }

    // 오버레이 권한 확인 (Validtaion 설문지 때문에 넣어 놨는데, 지금 설문지를 마지막에 표시하지는 않음.)
    private fun checkOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }
}