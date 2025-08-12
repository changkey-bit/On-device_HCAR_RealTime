package com.example.test_multimodel.presentation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import kotlin.math.pow
import kotlin.math.sqrt

class InferenceEngine(
    private val preProcessModel: MappedByteBuffer,
    private val finalModel: MappedByteBuffer,
    private val rfModelBytes: ByteArray
) {
    // TFLite 모델 인터프리터 (오디오 전처리와 최종 예측용)
    private val preProcessInterpreter = Interpreter(preProcessModel)
    private val finalInterpreter = Interpreter(finalModel)
    // ONNX 환경 및 RandomForest 세션
    private val ortEnv = OrtEnvironment.getEnvironment()
    private val randomForestSession = ortEnv.createSession(rfModelBytes, OrtSession.SessionOptions())

    // 오디오 데이터 처리 상수
    private val AUDIO_INITIAL_SAMPLES = 9600 // 초기 오디오 샘플 수 (첫 번째 세그먼트 크기)
    private val AUDIO_HOP_LENGTH = 480 // 슬라이딩 윈도우 이동 간격 (hop length)

    fun predictActivity(imuWindow: Array<FloatArray>, audioWindow: List<Short>?): Triple<FloatArray, String, String> {
        val features = extractIMUFeatures(imuWindow) // IMU 데이터에서 통계적 특징 추출
        val rfResult = runRandomForestInference(arrayOf(features)) // RandomForest로 1차 추론

        // RandomForest 결과 처리
        when (rfResult) {
            is InferenceResult.Success -> {
                if (rfResult.value == 0) {
                    // 이벤트 없으면 ML 분류 결과(Other)만 기록
                    return Triple(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f), "Other", "ML")
                }
            }
            is InferenceResult.Failure -> {
                Log.e("InferenceEngine", "RandomForest failed: ${rfResult.message}")
            }
        }

        // 오디오 윈도우 없으면 종료
        if (audioWindow == null) {
            return Triple(FloatArray(6) { 0f }, "Other", "ML")
        }

        // 오디오 데이터를 Float로 변환 (16비트 PCM 값을 -1.0 ~ 1.0 범위로 정규화)
        val audioFloat = audioWindow.map { it.toFloat() / 32768f }.toFloatArray()
        val audioSegments = mutableListOf<FloatArray>()
        // 96개의 오디오 세그먼트 생성 (각 세그먼트는 9600 샘플, 480씩 이동)
        for (start in 0 until (96 * AUDIO_HOP_LENGTH) step AUDIO_HOP_LENGTH) {
            audioSegments.add(audioFloat.sliceArray(start until (start + AUDIO_INITIAL_SAMPLES)))
        }

        // 전처리 모델로 각 세그먼트를 처리 (Log Mel Spectrogram 생성)
        val preProcessedOutputs = mutableListOf<FloatArray>()
        for (segment in audioSegments) {
            val outputBuffer = Array(1) { Array(1) { FloatArray(64) } } // 출력: (1, 1, 64)
            preProcessInterpreter.run(arrayOf(segment), outputBuffer)
            preProcessedOutputs.add(outputBuffer[0][0])
        }

        // 최종 모델 입력용 4D 배열 생성 (1, 96, 64, 1)
        val finalAudioInput = Array(1) { Array(96) { Array(64) { FloatArray(1) } } }
        preProcessedOutputs.forEachIndexed { i, output ->
            output.forEachIndexed { j, value -> finalAudioInput[0][i][j][0] = value }
        }

        // IMU와 오디오 데이터를 결합하여 최종 예측 수행
        val inputs = arrayOf<Any>(arrayOf(imuWindow), finalAudioInput) // 입력: [(1,100,9), (1,96,64,1)]
        val finalOutput = Array(1) { FloatArray(6) } // 출력: (1, 6) 확률 배열
        finalInterpreter.runForMultipleInputsOutputs(inputs, mapOf(0 to finalOutput))

        // 결과 정리
        val probabilities = finalOutput[0]
        val predictedIndex = probabilities.withIndex().maxByOrNull { it.value }?.index ?: -1
        val action = if (predictedIndex in 0..5) actionNames[predictedIndex] else "Other"

        return Triple(probabilities, action, "DL")
    }

    public fun extractIMUFeatures(data: Array<FloatArray>): FloatArray {
        if (data.isEmpty()) {
            Log.e("DataCollectionService", "extractIMUFeatures: Empty data received")
            return FloatArray(72) { 0f } // 빈 데이터면 0으로 채운 배열 반환
        }
        val numAxes = data[0].size // 축 수 (9: Accel 3, Gyro 3, RotVec 3)
        val numSamples = data.size // 샘플 수 (예상: 100)
        val features = FloatArray(numAxes * 8) // 각 축당 8개 특징
        for (axis in 0 until numAxes) {
            val values = FloatArray(numSamples) { i -> data[i].getOrElse(axis) { 0f } } // 축별 데이터 추출
            val mean = computeMean(values)
            val std = computeStd(values, mean)
            val max = values.maxOrNull() ?: 0f
            val min = values.minOrNull() ?: 0f
            val median = computeMedian(values)
            val variance = computeVariance(values, mean)
            val skew = computeSkew(values, mean, std)
            val kurtosis = computeKurtosis(values, mean, std)
            val startIdx = axis * 8 // 특징 배열 내 시작 위치
            features[startIdx] = mean
            features[startIdx + 1] = std
            features[startIdx + 2] = max
            features[startIdx + 3] = min
            features[startIdx + 4] = median
            features[startIdx + 5] = variance
            features[startIdx + 6] = skew
            features[startIdx + 7] = kurtosis
        }
        return features
    }

    // 통계 계산 함수들
    private fun computeMean(data: FloatArray): Float = data.sum() / data.size // 평균
    private fun computeVariance(data: FloatArray, mean: Float): Float = data.map { (it - mean).pow(2) }.sum() / data.size // 분산
    private fun computeStd(data: FloatArray, mean: Float): Float = sqrt(computeVariance(data, mean)) // 표준편차
    private fun computeMedian(data: FloatArray): Float {
        val sorted = data.sorted()
        val n = sorted.size
        return if (n % 2 == 0) (sorted[n / 2 - 1] + sorted[n / 2]) / 2 else sorted[n / 2] // 중앙값
    }
    private fun computeSkew(data: FloatArray, mean: Float, std: Float): Float {
        if (std == 0f) return 0f // 표준편차가 0이면 왜도 0 반환
        val n = data.size.toFloat()
        return data.map { ((it - mean) / std).pow(3) }.sum() / n // 왜도
    }
    private fun computeKurtosis(data: FloatArray, mean: Float, std: Float): Float {
        if (std == 0f) return 0f // 표준편차가 0이면 첨도 0 반환
        val n = data.size.toFloat()
        return data.map { ((it - mean) / std).pow(4) }.sum() / n - 3f // 첨도
    }

    private fun runRandomForestInferenceInternal(input: Array<FloatArray>): InferenceResult {
        val session = randomForestSession ?: return InferenceResult.Failure("Session not initialized")
        return try {
            val nFeatures = input[0].size // 특징 수 (72)
            val inputShape = longArrayOf(1, nFeatures.toLong()) // 입력 형태: (1, 72)
            val byteBuffer = ByteBuffer.allocateDirect(nFeatures * 4).order(ByteOrder.nativeOrder()) // Float당 4바이트
            val fb = byteBuffer.asFloatBuffer()
            fb.put(input[0]) // 특징 데이터를 버퍼에 입력
            fb.rewind()
            val tensor = OnnxTensor.createTensor(ortEnv, fb, inputShape) // ONNX 텐서 생성
            val inputName = session.inputNames.iterator().next() // 모델 입력 이름
            val results = session.run(mapOf(inputName to tensor)) // 추론 실행
            val outputVal = results[0].value // 결과값
            val predictedIndex = when (outputVal) {
                is LongArray -> outputVal[0].toInt()
                is IntArray -> outputVal[0]
                else -> -1 // 예상치 못한 출력 형식
            }
            results.forEach { (it as? AutoCloseable)?.close() } // 리소스 해제
            tensor.close()
            byteBuffer.clear()
            InferenceResult.Success(predictedIndex)
        } catch (e: Exception) {
            Log.e("ONNX", "ONNX inference error: ${e.message}")
            InferenceResult.Failure(e.message ?: "Unknown error")
        }
    }

    fun predictWithDl(imuWindow: Array<FloatArray>, audioWindow: List<Short>?): Triple<FloatArray, String, String> {
        // 오디오 윈도우 없으면 종료
        if (audioWindow == null) {
            return Triple(FloatArray(6) { 0f }, "Other", "ML")
        }

        // 오디오 데이터를 Float로 변환 (16비트 PCM 값을 -1.0 ~ 1.0 범위로 정규화)
        val audioFloat = audioWindow.map { it.toFloat() / 32768f }.toFloatArray()
        val audioSegments = mutableListOf<FloatArray>()
        // 96개의 오디오 세그먼트 생성 (각 세그먼트는 9600 샘플, 480씩 이동)
        for (start in 0 until (96 * AUDIO_HOP_LENGTH) step AUDIO_HOP_LENGTH) {
            audioSegments.add(audioFloat.sliceArray(start until (start + AUDIO_INITIAL_SAMPLES)))
        }

        // 전처리 모델로 각 세그먼트를 처리 (Log Mel Spectrogram 생성)
        val preProcessedOutputs = mutableListOf<FloatArray>()
        for (segment in audioSegments) {
            val outputBuffer = Array(1) { Array(1) { FloatArray(64) } } // 출력: (1, 1, 64)
            preProcessInterpreter.run(arrayOf(segment), outputBuffer)
            preProcessedOutputs.add(outputBuffer[0][0])
        }

        // 최종 모델 입력용 4D 배열 생성 (1, 96, 64, 1)
        val finalAudioInput = Array(1) { Array(96) { Array(64) { FloatArray(1) } } }
        preProcessedOutputs.forEachIndexed { i, output ->
            output.forEachIndexed { j, value -> finalAudioInput[0][i][j][0] = value }
        }

        // IMU와 오디오 데이터를 결합하여 최종 예측 수행
        val inputs = arrayOf<Any>(arrayOf(imuWindow), finalAudioInput) // 입력: [(1,100,9), (1,96,64,1)]
        val finalOutput = Array(1) { FloatArray(6) } // 출력: (1, 6) 확률 배열
        finalInterpreter.runForMultipleInputsOutputs(inputs, mapOf(0 to finalOutput))

        // 결과 정리
        val probabilities = finalOutput[0]
        val predictedIndex = probabilities.withIndex().maxByOrNull { it.value }?.index ?: -1
        val action = if (predictedIndex in 0..5) actionNames[predictedIndex] else "Other"

        return Triple(probabilities, action, "DL")
    }

    // RF inference만 따로 노출
    fun runRandomForestInference(input: Array<FloatArray>): InferenceResult {
        return runRandomForestInferenceInternal(input)  // 기존 private 메서드 호출
    }

    fun close() {
        preProcessInterpreter.close()
        finalInterpreter.close()
        randomForestSession.close()
        ortEnv.close()
    }

    companion object {
        // 예측 가능한 활동 이름 목록 (인덱스 0~5)
        val actionNames = arrayOf("Other", "Shower", "Tooth brushing", "Vacuum Cleaner", "Washing hands", "Wiping")
    }
}

sealed class InferenceResult {
    data class Success(val value: Int) : InferenceResult() // 성공 시 예측값
    data class Failure(val message: String) : InferenceResult() // 실패 시 에러 메시지
}