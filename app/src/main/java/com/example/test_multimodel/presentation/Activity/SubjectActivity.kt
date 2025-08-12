package com.example.test_multimodel.presentation.Activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.test_multimodel.R
import com.example.test_multimodel.presentation.Service.DataCollectionService

class SubjectActivity : AppCompatActivity() {

    private lateinit var IDEditText: EditText
    private lateinit var durationEditText: EditText
    private lateinit var resetBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subject)

        IDEditText = findViewById(R.id.edit_subject)
        durationEditText = findViewById(R.id.edit_duration)

        resetBtn = findViewById(R.id.resetBtn)

        // SharedPreferences 초기화
        val sharedPreferences = getSharedPreferences("subject", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()

        // Switch 상태를 저장하고 불러오기 위한 설정
        IDEditText.setText(sharedPreferences.getString("subject_id", "1"))
        durationEditText.setText(sharedPreferences.getString("duration", "1"))

        editor.putString("subject_id", sharedPreferences.getString("subject_id", "1"))
        editor.putString("duration", sharedPreferences.getString("duration", "1"))

        editor.apply()

        IDEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }
            override fun afterTextChanged(s: Editable?) {
                editor.putString("subject_id", s.toString())
                editor.apply()
            }
        })
        durationEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }
            override fun afterTextChanged(s: Editable?) {
                editor.putString("duration", s.toString())
                editor.apply()
            }
        })
        // resetBtn 클릭 처리: 1초 내에 연속 3회 클릭하면 포그라운드 서비스 종료
        var clickCount = 0
        var lastClickTime = 0L
        val clickTimeout = 3000L  // 1초 내에 연속 클릭해야 함
        resetBtn.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastClickTime > clickTimeout) {
                // 너무 오래 걸렸다면 카운터 초기화
                clickCount = 0
            }
            lastClickTime = currentTime
            clickCount++
            if (clickCount >= 3) {
                // 포그라운드 서비스 종료
                val serviceIntent = Intent(this, DataCollectionService::class.java)
                stopService(serviceIntent)
                Toast.makeText(this, "데이터 수집이 종료 되었습니다.", Toast.LENGTH_SHORT).show()
                clickCount = 0
            }
        }
    }
}