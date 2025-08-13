# On-device HCAR Real-Time

---

## ⏱️ Framework of On-device HCAR
<img src="https://github.com/user-attachments/assets/b13bcb1b-f096-4cbd-9853-ed8028732595">

## ⏱️ 스마트워치 기반 실시간 위생·청결 행동 인식 시스템
<img src="https://github.com/user-attachments/assets/d79c0bf6-ab4c-4f2f-93e1-b0fcbbde8d37">

---

## 📑 프로젝트 소개
### 👤 실시간 행동 인식 프로세스
1. **데이터 수집**  
   - IMU 데이터를 **50Hz**로 실시간 수집  
   - 데이터를 **2초 윈도우**로 분할 후 **[-1, 1] 정규화** 수행

2. **특징 추출**  
   - 각 윈도우에서 **8가지 통계 특징 벡터** 추출  
     *(mean, standard deviation, maximum, minimum, median, variance, skewness, kurtosis)*  
   - 총 72차원 입력 벡터 생성

3. **이벤트 감지**  
   - **랜덤 포레스트 기반 이벤트 감지기**에 입력  
   - 이벤트 발생 시 오디오 데이터 수집 트리거

4. **오디오 처리**  
   - **16kHz** 샘플링으로 오디오 수집  
   - **64-bin 로그 멜 스펙트로그램**으로 변환

5. **다중 모달 행동 인식**  
   - 전처리된 IMU 및 Audio 데이터를 결합  
   - **5가지 위생·청결 행동 클래스**로 분류  
   - 예측 결과를 기기 내 저장

---

> **특징**  
> - 스마트워치 단독 동작(On-device)으로 네트워크 연결 없이 실시간 인식 가능  
> - IMU·오디오 멀티모달 결합으로 높은 행동 인식 정확도 확보  
> - 이벤트 기반 오디오 수집으로 연산량 절감 및 배터리 효율 향상  
> - 개인정보가 외부로 전송되지 않아 보안·프라이버시 강화  
> - 연구 및 상용화 모두 대응 가능한 모듈형 구조

---

## 🛠 사용 기술 스택
- **Android** : Kotlin  
- **Models** : TensorFlow, TensorFlow Lite, Scikit-learn, ONNX  
- **Architecture** : Multi-CNN, Random Forest  
