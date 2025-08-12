/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package com.example.test_multimodel.presentation.Activity

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.test_multimodel.presentation.Permission.RequestPermission
import com.example.test_multimodel.R
import android.content.Intent
import android.os.Build

// 첫 화면을 구성하는 Activity
class MainActivity : AppCompatActivity() {

    private lateinit var requestPermission: RequestPermission

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val settingSubjectButton: Button = findViewById(R.id.setup_subject)
        val trackButton: Button = findViewById(R.id.track_activity)

        settingSubjectButton.setOnClickListener {
            startActivity(Intent(this, SubjectActivity::class.java))
        }
        trackButton.setOnClickListener {
            startActivity(Intent(this, TrackActivity::class.java))
        }

        // 첫 화면 진입 시 권한 요청
        requestPermission = RequestPermission(this)
        requestPermission.requestPermissions()
        requestPermission.requestDisableBatteryOptimization()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RequestPermission.REQUEST_CODE_PERMISSIONS) {
            val deniedPermissions = mutableListOf<String>()
            for ((index, permission) in permissions.withIndex()) {
                if (grantResults[index] != PackageManager.PERMISSION_GRANTED) {
                    deniedPermissions.add(permission)
                }
            }
            if (deniedPermissions.isEmpty()) {
                // 모든 필수 권한 허용됨
                // API 29 이상인 경우 백그라운드 위치 권한도 확인 (ACCESS_BACKGROUND_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    // 백그라운드 위치 권한 요청 (RequestPermission 내부에서 별도 처리하는 것으로 가정)
                    requestPermission.requestPermissions()
                }
            } else {
                Toast.makeText(this, "모든 권한을 허용해야 합니다.", Toast.LENGTH_SHORT).show()
                // 거부된 권한이 있으면 다시 요청
                requestPermission.requestPermissions()
            }
        }
    }
}
