package com.example.test_multimodel.presentation.Activity

import android.app.ActivityManager
import android.content.*
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.test_multimodel.R
import com.example.test_multimodel.presentation.Service.DataCollectionService

class TrackActivity : AppCompatActivity() {

    private lateinit var startButton: Button
    private lateinit var timerTextView: TextView
    private lateinit var activityTextview: TextView

    // 백그라운드 서비스(DataCollectionService)로부터 전달 받은 인텐트를 처리하는 브로드캐스트 리시버
    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                // 남은 시간 업데이트 (ACTION_UPDATE_TIME)
                if (it.hasExtra("remainingTime")) {
                    val remainingTime = it.getStringExtra("remainingTime") ?: ""
                    timerTextView.text = remainingTime
                }
                // 추론 결과 업데이트 (ACTION_UPDATE_ACTIVITY)
                if (it.hasExtra("predictedActivity")) {
                    val predictedActivity = it.getStringExtra("predictedActivity") ?: ""
                    activityTextview.text = predictedActivity
                }
                // 서비스 종료 여부 (ACTION_UPDATE_UI)
                if (it.hasExtra("serviceStopped")) {
                    val serviceStopped = it.getBooleanExtra("serviceStopped", false)
                    if (serviceStopped) {
                        startButton.isEnabled = true
                        activityTextview.text = "Complete!"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_track)

        startButton = findViewById(R.id.button_start) ?: throw IllegalStateException("button_start not found")
        timerTextView = findViewById(R.id.text_timer) ?: throw IllegalStateException("text_timer not found")
        activityTextview = findViewById(R.id.text_activity) ?: throw IllegalStateException("text_activity not found")

        startButton.setOnClickListener {
            if (isServiceRunning(DataCollectionService::class.java)) {
                Toast.makeText(this, "서비스가 이미 실행중입니다", Toast.LENGTH_SHORT).show()
            } else {
                val serviceIntent = Intent(this, DataCollectionService::class.java)
                ContextCompat.startForegroundService(this, serviceIntent)
                startButton.isEnabled = false
                activityTextview.text = "Loading..."
            }
        }
        // 액티비티 생성 시 서비스 상태에 따라 버튼 초기화
        updateButtonState()
    }

    override fun onResume() {
        super.onResume()
        // 세 가지 액션 모두 수신하도록 필터 등록
        val filter = IntentFilter().apply {
            addAction(DataCollectionService.ACTION_UPDATE_TIME)
            addAction(DataCollectionService.ACTION_UPDATE_ACTIVITY)
            addAction(DataCollectionService.ACTION_UPDATE_UI)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(updateReceiver, filter)
        // 화면에 들어올 때 버튼 상태 업데이트
        updateButtonState()
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(updateReceiver)
    }

    // 서비스가 실행 중인지 확인하는 함수
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    // 버튼 상태를 서비스 실행 여부에 따라 업데이트
    private fun updateButtonState() {
        startButton.isEnabled = !isServiceRunning(DataCollectionService::class.java)
        if (!startButton.isEnabled && activityTextview.text == "Complete!") {
            activityTextview.text = "Loading..." // 서비스가 실행 중일 때 텍스트 유지
        }
    }
}