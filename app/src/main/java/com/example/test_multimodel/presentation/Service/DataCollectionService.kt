package com.example.test_multimodel.presentation.Service

import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import androidx.lifecycle.LifecycleService
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import com.example.test_multimodel.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.test_multimodel.presentation.Activity.TrackActivity
import com.example.test_multimodel.presentation.Collector.AudioDataCollector
import com.example.test_multimodel.presentation.FileLogger.FileLogger
import com.example.test_multimodel.presentation.Collector.ImuDataCollector
import com.example.test_multimodel.presentation.InferenceEngine
import com.example.test_multimodel.presentation.InferenceResult

class DataCollectionService : LifecycleService() {
    // IMU 데이터 수집기 (센서 데이터 처리)
    private lateinit var imuCollector: ImuDataCollector
    // 오디오 데이터 수집기 (마이크 입력 처리)
    private lateinit var audioCollector: AudioDataCollector
    // 추론 엔진 (RandomForest 및 딥러닝 모델 실행)
    private lateinit var inferenceEngine: InferenceEngine
    // 파일 로거 (CSV 파일에 데이터 기록)
    private lateinit var fileLogger: FileLogger

    // 서비스 종료 시간 (밀리초 단위)
    private var endTime = 0L
    // 서비스 시작 시간 (밀리초 단위, 상대 시간 계산용)
    private var serviceStartTime = 0L
    // 서비스 내 코루틴 작업을 관리하는 스코프 (IO 스레드 + 작업 실패 시 전체 중단 방지)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var isAudioActive = false

    override fun onCreate() {
        super.onCreate()
        // IMU 정규화 파라미터 로드 (assets에서 CSV 파일 읽기)
        val imuMin = loadCSV("normalization_params/Right_min_ver36.csv")
        val imuMax = loadCSV("normalization_params/Right_max_ver36.csv")
        val imuMean = loadCSV("normalization_params/Right_mean_ver36.csv")
        val imuStd = loadCSV("normalization_params/Right_std_ver36.csv")

        imuCollector = ImuDataCollector(this, imuMin, imuMax, imuMean, imuStd, 20L) // 20ms 간격 = 50Hz
        audioCollector = AudioDataCollector(this)

        // 머신러닝 모델 초기화 (TFLite와 ONNX 모델 로드)
        inferenceEngine = InferenceEngine(
            loadModelFile("log_mel_model.tflite"), // 오디오 전처리 모델
            loadModelFile("quan_multimodal_cnn_ver36.tflite"), // 최종 예측 모델
            assets.open("random_forest_ver36.onnx").readBytes() // RandomForest 모델
        )

        // 피험자 ID와 저장 폴더 설정 (SharedPreferences에서 가져옴)
        val subjectPrefs = getSharedPreferences("subject", Context.MODE_PRIVATE)
        val subjectID = subjectPrefs.getString("subject_id", "1") ?: "1"
        val baseFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HCILab_Pilot/$subjectID")
        if (!baseFolder.exists()) {
            if (baseFolder.mkdirs()) {
                Log.i("File", "Created directory: ${baseFolder.absolutePath}")
            }
        }

        fileLogger = FileLogger(subjectID, baseFolder)

        // 포그라운드 서비스로 실행 (알림 표시)
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        serviceStartTime = System.currentTimeMillis() // 서비스 시작 시간 기록
        // 종료 시간 계산: 시작 시간 + 설정된 지속 시간 (기본 1시간)
        endTime = serviceStartTime + (getSharedPreferences("subject", Context.MODE_PRIVATE).getString("duration", "1")?.toInt() ?: 1) * 3600 * 1000L
        audioCollector.startRecording(
            serviceScope
        ) { buffer: ShortArray, readCount: Int ->
        }
        startDataCollection() // 데이터 수집 시작
        return START_STICKY // 서비스가 강제 종료되면 재시작
    }

    private fun startDataCollection() {
        // IMU 데이터 수집 시작
        imuCollector.startCollecting(endTime, serviceScope) { sample ->
            serviceScope.launch { fileLogger.logImuData(sample, serviceStartTime) }
        }

        // 추론 및 활동 업데이트 코루틴 (Default 스레드에서 실행)
        serviceScope.launch(Dispatchers.Default) {
            while (System.currentTimeMillis() < endTime) {
                // IMU 윈도우 가져오기
                val imuWindow = imuCollector.getNormalizedWindow() ?: continue

                // RF로 1차 추론
                val rfResult = inferenceEngine.runRandomForestInference(arrayOf(inferenceEngine.extractIMUFeatures(imuWindow)))
                if (rfResult is InferenceResult.Success && rfResult.value == 0) {

                    audioCollector.stopRecording()
                    audioCollector.clearBuffer()
                    isAudioActive = false

                    fileLogger.logInferenceResult(
                        floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f),
                        "Other",
                        "ML",
                        serviceStartTime
                    )
                    // UI 업데이트도 필요하면 브로드캐스트
                    LocalBroadcastManager.getInstance(this@DataCollectionService)
                        .sendBroadcast(Intent(ACTION_UPDATE_ACTIVITY).putExtra("predictedActivity", "Other"))
                    delay(200)  // 5Hz 유지
                    continue
                }

                if (!isAudioActive) {
                    audioCollector.startRecording(serviceScope) { _, _ -> /* no-op */ }
                    isAudioActive = true
                }

                // RF 결과가 0이 아닌 경우에만 오디오 수집 및 DL 추론
                val audioWindow = audioCollector.getAudioWindow(AUDIO_INITIAL_SAMPLES + 95 * AUDIO_HOP_LENGTH)
                val (probabilities, action, modelType) = inferenceEngine.predictWithDl(imuWindow, audioWindow)
                fileLogger.logInferenceResult(
                    probabilities,
                    action,
                    modelType,
                    serviceStartTime
                )

                LocalBroadcastManager.getInstance(this@DataCollectionService)
                    .sendBroadcast(Intent(ACTION_UPDATE_ACTIVITY).putExtra("predictedActivity", action))

                delay(200)
            }
            stopDataCollection()
            stopSelf()
        }

        // 남은 시간 업데이트 코루틴 (IO 스레드에서 실행)
        serviceScope.launch(Dispatchers.IO) {
            while (System.currentTimeMillis() < endTime) {
                val remainingTime = formatTime((endTime - System.currentTimeMillis()).coerceAtLeast(0)) // 남은 시간 계산
                // UI에 남은 시간 브로드캐스트
                LocalBroadcastManager.getInstance(this@DataCollectionService).sendBroadcast(
                    Intent(ACTION_UPDATE_TIME).putExtra("remainingTime", remainingTime)
                )
                delay(1000) // 1초마다 업데이트
            }
        }
    }

    private fun loadCSV(fileName: String): Array<FloatArray> {
        val result = mutableListOf<FloatArray>()
        assets.open(fileName).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val values = line.split(",").map { it.trim().toFloat() }.toFloatArray()
                result.add(values)
            }
        }
        return result.toTypedArray()
    }

    private fun loadModelFile(modelName: String): MappedByteBuffer {
        val fd = assets.openFd(modelName)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val channel = inputStream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    private fun stopDataCollection() {
        imuCollector.stopCollecting() // IMU 수집 중지
        audioCollector.stopRecording() // 오디오 수집 중지
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel() // 모든 코루틴 작업 취소
        inferenceEngine.close() // 모델 리소스 해제
        fileLogger.flushAndClose() // 파일 버퍼 플러시 및 닫기
    }

    private fun createNotification(): Notification {
        val channelId = "Data_collection_channel"
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 알림 채널 생성 (Android 8.0 이상)
            if (notificationManager.getNotificationChannel(channelId) == null) {
                val channel = NotificationChannel(
                    channelId,
                    "Data Collection",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "데이터 수집 채널"
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
        // 알림 클릭 시 TrackActivity로 이동
        val intent = Intent(this, TrackActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 알림 빌더 설정
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("데이터 수집 중")
            .setContentText("실시간 Detection 중 입니다.")
            .setSmallIcon(R.drawable.android) // 알림 아이콘 (리소스 필요)
            .setContentIntent(pendingIntent)
            .setOngoing(true) // 알림 고정
            .setAutoCancel(false) // 탭해도 자동 삭제 방지
            .build()
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%02dh:%02dm:%02ds", hours, minutes, seconds)
    }

    companion object {
        // 알림 ID
        private const val NOTIFICATION_ID = 1
        // 브로드캐스트 액션 이름
        const val ACTION_UPDATE_TIME = "com.example.UPDATE_TIME" // 남은 시간 업데이트
        const val ACTION_UPDATE_ACTIVITY = "com.example.UPDATE_ACTIVITY" // 예측 활동 업데이트
        const val ACTION_UPDATE_UI = "com.example.UPDATE_UI" // 서비스 종료 알림
        // 오디오 처리 상수
        private const val AUDIO_INITIAL_SAMPLES = 9600 // 초기 오디오 샘플 수
        private const val AUDIO_HOP_LENGTH = 480 // 오디오 hop 길이
    }
}