package com.example.test_multimodel.presentation.FileLogger

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.util.Calendar

class FileLogger(subjectID: String, baseFolder: File) {

    // 각 데이터 타입별 CSV 파일 경로와 이름 설정 (파일 이름에 타임스탬프 포함)
    private val imuFile = File(baseFolder, "${subjectID}_${System.currentTimeMillis()}_SensorData.csv")
    private val audioFile = File(baseFolder, "${subjectID}_${System.currentTimeMillis()}_AudioData.csv")
    private val inferenceFile = File(baseFolder, "${subjectID}_${System.currentTimeMillis()}_Predicted_Activity.csv")

    // 파일에 데이터를 효율적으로 쓰기 위한 BufferedWriter 인스턴스 (UTF-8 인코딩 사용)
    private val imuWriter = imuFile.bufferedWriter(Charsets.UTF_8)
    private val audioWriter = audioFile.bufferedWriter(Charsets.UTF_8)
    private val inferenceWriter = inferenceFile.bufferedWriter(Charsets.UTF_8)

    // 데이터를 메모리에 모아두기 위한 StringBuilder 버퍼 (입출력 성능 최적화)
    private val imuLogBuffer = StringBuilder()
    private val audioLogBuffer = StringBuilder()
    private val inferenceLogBuffer = StringBuilder()

    // 버퍼 크기 제한 (8KB), 이 크기를 넘으면 파일에 기록하고 버퍼를 비움
    private val LOG_BUFFER_SIZE = 8192

    init {
        if (!baseFolder.exists()) baseFolder.mkdirs() // 디렉토리가 없으면 재귀적으로 생성
        createHeaders() // 각 CSV 파일에 헤더를 작성
    }

    suspend fun logImuData(data: FloatArray, serviceStartTime: Long) =
        logToFile(imuWriter, data.joinToString(","), imuLogBuffer, serviceStartTime)

    suspend fun logAudioData(data: ShortArray, readCount: Int, serviceStartTime: Long) =
        logToFile(audioWriter, data.take(readCount).joinToString(","), audioLogBuffer, serviceStartTime)


    suspend fun logInferenceResult(probabilities: FloatArray, action: String, model: String, serviceStartTime: Long) =
        logToFile(inferenceWriter, "${probabilities.joinToString(",")},$action,$model", inferenceLogBuffer, serviceStartTime)

    private suspend fun logToFile(writer: BufferedWriter, data: String, logBuffer: StringBuilder, serviceStartTime: Long) {
        withContext(Dispatchers.IO) { // IO 작업을 별도 스레드에서 실행
            val currentTime = System.currentTimeMillis() // 현재 시간 (Unix 타임스탬프)
            val cal = Calendar.getInstance().apply { timeInMillis = currentTime } // 시간 분해용 캘린더 객체
            // 버퍼에 타임스탬프와 데이터 추가 (형식: UnixTime, 상대 시간, 연, 월, 일, 시, 분, 초, 데이터)
            logBuffer.append("$currentTime,${(currentTime - serviceStartTime) / 1000.0}," +
                    "${cal.get(Calendar.YEAR)},${cal.get(Calendar.MONTH) + 1},${cal.get(Calendar.DAY_OF_MONTH)}," +
                    "${cal.get(Calendar.HOUR_OF_DAY)},${cal.get(Calendar.MINUTE)},${cal.get(Calendar.SECOND)},$data\n")
            if (logBuffer.length > LOG_BUFFER_SIZE) { // 버퍼가 8KB를 넘으면 파일에 기록
                writer.write(logBuffer.toString()) // 버퍼 내용을 파일에 기록
                writer.flush() // 즉시 디스크에 반영
                logBuffer.clear() // 버퍼 초기화
            }
        }
    }

    private fun createHeaders() {
        // IMU 데이터 헤더
        if (!imuFile.exists()) {
            imuFile.createNewFile()
        }
        imuWriter.write("UnixTime,Time,Year,Month,Day,Hour,Min,Sec,AccX,AccY,AccZ,GyroX,GyroY,GyroZ,RotVecX,RotVecY,RotVecZ")
        imuWriter.newLine()
        imuWriter.flush()

        // 오디오 데이터 헤더 (1280개 샘플 열)
        if (!audioFile.exists()) {
            audioFile.createNewFile()
        }
        val audioDataColumns = (1..1280).joinToString(",") { "AudioData$it" }
        audioWriter.write("UnixTime,Time,Year,Month,Day,Hour,Min,Sec,$audioDataColumns")
        audioWriter.newLine()
        audioWriter.flush()

        // 추론 결과 헤더
        if (!inferenceFile.exists()) {
            inferenceFile.createNewFile()
        }
        inferenceWriter.write("UnixTime,Time,Year,Month,Day,Hour,Min,Sec,Other,Shower,ToothBrushing,VacuumCleaner,WashingHands,Wiping,Predict,Model")
        inferenceWriter.newLine()
        inferenceWriter.flush()
    }

    fun flushAndClose() {
        listOf(imuWriter, audioWriter, inferenceWriter).forEach { writer ->
            try {
                writer.flush() // 남은 버퍼 데이터를 파일에 기록
                writer.close() // 파일 스트림 닫기
            } catch (e: Exception) {
                Log.e("FileLogger", "Failed to close writer: ${e.message}") // 예외 발생 시 로그 기록
            }
        }
    }
}