package com.example.test_multimodel.presentation.Collector

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AudioDataCollector(private val context: Context) {
    // AudioRecord 객체 (마이크로부터 오디오 데이터를 읽어옴), 초기화 지연
    private lateinit var audioRecord: AudioRecord
    // 녹음 상태를 추적하는 플래그
    private var isRecordingAudio = false
    // 추론에 사용할 오디오 샘플을 저장하는 동적 큐 (Short 타입: 16비트 PCM 데이터)
    private val inferenceAudioBuffer: ArrayDeque<Short> = ArrayDeque()
    // 오디오 녹음 작업을 관리하는 코루틴 Job
    private var audioJob: Job? = null

    // 오디오 처리 상수
    private val AUDIO_INITIAL_SAMPLES = 9600 // 초기 오디오 세그먼트 크기
    private val AUDIO_HOP_LENGTH = 480 // 슬라이딩 윈도우 이동 간격 (hop length)

    fun startRecording(scope: CoroutineScope, onAudioData: (ShortArray, Int) -> Unit) {
        // RECORD_AUDIO 권한 확인 (없으면 함수 종료)
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("Audio", "Audio recording permission not granted")
            return
        }

        // 오디오 설정: 16kHz 샘플레이트, 모노 채널, 16비트 PCM 포맷
        val sampleRate = 16000
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) // 최소 버퍼 크기 계산
        // AudioRecord 초기화 (마이크 입력, 설정값 사용)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize
        )

        isRecordingAudio = true // 녹음 상태 활성화
        audioRecord.startRecording() // 녹음 시작

        // 오디오 데이터를 비동기적으로 읽는 코루틴 작업
        audioJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(minBufferSize) // 오디오 데이터를 읽을 임시 버퍼
            while (isRecordingAudio) { // 녹음 상태일 동안 반복
                val readCount = audioRecord.read(buffer, 0, buffer.size) // 버퍼에 데이터 읽기
                if (readCount > 0) { // 유효한 데이터가 읽힌 경우
                    onAudioData(buffer, readCount) // 외부 콜백으로 데이터 전달
                    synchronized(inferenceAudioBuffer) { // 버퍼 동기화 (스레드 안전성 보장)
                        inferenceAudioBuffer.addAll(buffer.take(readCount)) // 읽은 데이터를 큐에 추가
                        // 버퍼 크기 제한: 필요한 샘플 수(9600 + 95 * 480 = 55,040)를 초과하면 오래된 데이터 제거
                        while (inferenceAudioBuffer.size > AUDIO_INITIAL_SAMPLES + 95 * AUDIO_HOP_LENGTH) {
                            inferenceAudioBuffer.removeFirst()
                        }
                    }
                }
            }
        }
    }

    fun stopRecording() {
        isRecordingAudio = false // 녹음 상태 비활성화
        audioJob?.cancel() // 코루틴 작업 취소
        // AudioRecord가 녹음 중일 때만 중지 (예외 방지)
        if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop()
        audioRecord.release() // AudioRecord 리소스 해제
    }
    fun clearBuffer() {
        synchronized(inferenceAudioBuffer) { inferenceAudioBuffer.clear() }
    }

    fun getAudioWindow(requiredSamples: Int): List<Short>? = synchronized(inferenceAudioBuffer) {
        if (inferenceAudioBuffer.size >= requiredSamples) { // 필요한 샘플 수를 충족하면
            val window = inferenceAudioBuffer.take(requiredSamples).toList() // 윈도우 데이터 추출
            // 윈도우 이동: hop length만큼 오래된 데이터 제거
            repeat(AUDIO_HOP_LENGTH) { if (inferenceAudioBuffer.isNotEmpty()) inferenceAudioBuffer.removeFirst() }
            window // 추출된 윈도우 반환
        } else null // 데이터 부족 시 null 반환
    }
}