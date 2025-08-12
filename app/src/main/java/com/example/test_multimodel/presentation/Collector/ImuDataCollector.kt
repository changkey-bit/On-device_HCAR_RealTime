package com.example.test_multimodel.presentation.Collector

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ImuDataCollector(
    private val context: Context,
    private val imuMin: Array<FloatArray>,
    private val imuMax: Array<FloatArray>,
    private val imuMean: Array<FloatArray>,
    private val imuStd: Array<FloatArray>,
    private val samplingInterval: Long
) : SensorEventListener {
    // 센서 관리 객체 (센서 등록 및 해제에 사용)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    // 센서 객체 (각 센서 타입별 기본 센서 가져오기)
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) // 가속도계
    private val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) // 자이로스코프
    private val rotVecSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) // 회전 벡터

    // 최신 센서 데이터 (Volatile로 멀티스레드 환경에서 안전성 보장)
    @Volatile private var latestAccel = FloatArray(3) { 0f } // 가속도계 데이터 (x, y, z)
    @Volatile private var latestGyro = FloatArray(3) { 0f } // 자이로스코프 데이터 (x, y, z)
    @Volatile private var latestRotVec = FloatArray(3) { 0f } // 회전 벡터 데이터 (x, y, z)
    // 정규화된 IMU 데이터를 저장하는 동적 큐 (최대 100개 샘플 유지)
    private val normalizedImuDeque: ArrayDeque<FloatArray> = ArrayDeque()
    // IMU 데이터 수집 작업을 관리하는 코루틴
    private var imuJob: Job? = null

    // IMU 데이터 윈도우 크기 상수 (100개 샘플 = 2초, 50Hz 기준)
    private val IMU_WINDOW_SIZE = 100
    private val normFactor = FloatArray(9)
    private val normBias   = FloatArray(9)

    init {
        for (j in 0 until 9) {
            val minV = imuMin[0][j]
            val maxV = imuMax[0][j]
            val std  = imuStd[0][j]
            val mean = imuMean[0][j]

            val scale = if (maxV != minV) 2f / (maxV - minV) else 0f
            val offset = 1f - maxV * scale

            if (std != 0f) {
                normFactor[j] = scale / std
                normBias[j]   = (offset - mean) / std
            } else {
                normFactor[j] = 0f
                normBias[j]   = 0f
            }
        }
    }

    fun startCollecting(endTime: Long, scope: CoroutineScope, onSample: (FloatArray) -> Unit) {
        // 센서 리스너 등록
        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        rotVecSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }

        // 데이터 수집 코루틴 (IO 스레드에서 실행)
        imuJob = scope.launch(Dispatchers.IO) {
            while (System.currentTimeMillis() < endTime) { // 종료 시간까지 반복
                val sample = getLatestSample() // 최신 9축 데이터 가져오기
                onSample(sample) // 외부로 샘플 전달 (로깅 등에 사용)
                val normalizedSample = normalizeSample(sample) // 데이터 정규화
                synchronized(normalizedImuDeque) { // 큐 동기화 (스레드 안전성 보장)
                    normalizedImuDeque.add(normalizedSample) // 정규화된 샘플 추가
                    if (normalizedImuDeque.size > IMU_WINDOW_SIZE) normalizedImuDeque.removeFirst() // 크기 초과 시 오래된 데이터 제거
                }
                delay(samplingInterval) // 지정된 간격만큼 대기 (예: 20ms)
            }
        }
    }
    /*
     * IMU 데이터 수집을 중지하고 리소스를 정리합니다.
     */
    fun stopCollecting() {
        imuJob?.cancel() // 코루틴 작업 취소
        sensorManager.unregisterListener(this) // 센서 리스너 해제
    }

    /*
     * 정규화된 IMU 데이터 윈도우를 반환합니다.
     * @return 100개 샘플의 정규화된 데이터 배열 (충분한 데이터 없으면 null)
     */
    fun getNormalizedWindow(): Array<FloatArray>? = synchronized(normalizedImuDeque) {
        if (normalizedImuDeque.size >= IMU_WINDOW_SIZE) { // 필요한 샘플 수 충족 시
            normalizedImuDeque.toList().toTypedArray() // 큐를 배열로 변환 후 반환
        } else null // 데이터 부족 시 null 반환
    }

    private fun getLatestSample() = FloatArray(9).apply {
        latestAccel.copyInto(this, 0, 0, 3) // 가속도 데이터 복사 (0~2)
        latestGyro.copyInto(this, 3, 0, 3) // 자이로 데이터 복사 (3~5)
        latestRotVec.copyInto(this, 6, 0, 3) // 회전 벡터 데이터 복사 (6~8)
    }

    /*
     * IMU 샘플을 정규화 (Min-Max 정규화, -1 ~ 1 범위).
     * @param sample 원본 9축 데이터
     * @return 정규화된 9축 데이터
     */
    private fun normalizeSample(sample: FloatArray) = FloatArray(9) { j ->
        sample[j] * normFactor[j] + normBias[j]
    }

    /*
     * 센서 데이터가 변경될 때 호출하고, 최신 값을 업데이트
     */
    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> synchronized(latestAccel) { // 가속도계 데이터 동기화
                    if (it.values.size >= 3) { // 데이터 길이 확인
                        it.values.copyInto(latestAccel, 0, 0, minOf(3, it.values.size)) // 최신 값 복사
                    } else {
                        Log.w("DataCollectionService", "Accelerometer data too short: ${it.values.size}")
                    }
                }
                Sensor.TYPE_GYROSCOPE -> synchronized(latestGyro) { // 자이로스코프 데이터 동기화
                    if (it.values.size >= 3) {
                        it.values.copyInto(latestGyro, 0, 0, minOf(3, it.values.size))
                    } else {
                        Log.w("DataCollectionService", "Gyroscope data too short: ${it.values.size}")
                    }
                }
                Sensor.TYPE_ROTATION_VECTOR -> synchronized(latestRotVec) { // 회전 벡터 데이터 동기화
                    if (it.values.size >= 3) {
                        it.values.copyInto(latestRotVec, 0, 0, minOf(3, it.values.size))
                    } else {
                        Log.w("DataCollectionService", "Rotation vector data too short: ${it.values.size}")
                    }
                }
                else -> {
                    Log.d("DataCollectionService", "Unhandled sensor type: ${it.sensor.type}") // 처리되지 않은 센서 타입 로그
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}