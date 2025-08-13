# On-device_HCAR_RealTime

---

## ⏱️ Framework of On-device_HCAR 

<img src="[https://github.com/user-attachments/assets/d79c0bf6-ab4c-4f2f-93e1-b0fcbbde8d37](https://github.com/user-attachments/assets/b13bcb1b-f096-4cbd-9853-ed8028732595)">

## ⏱️ 스마트워치 기반 실시간 위생 및 청결 행동 인식 시스템

<img src="https://github.com/user-attachments/assets/d79c0bf6-ab4c-4f2f-93e1-b0fcbbde8d37">

---

## 📑 프로젝트 소개
### 👤 행동 데이터 라벨링 애플리케이션
- 스마트워치와 스마트폰을 연동하여 **위생 행동(Hygiene Behavior)** 데이터를 수집
- **Set-Up Subject**: 피험자 번호 입력 후 스마트워치 데이터 수집 앱과 동시에 START
- **Track Activity**: 기록할 행동 선택 → 해당 행동의 사진 촬영 → 행동 시작/종료 시각 기록
- 기록 후 해당 행동 데이터의 정확성 검증 설문 진행  
  - ‘정확’ → 데이터 저장  
  - ‘부정확’ → 데이터 폐기
- **Saved Data**: 기록된 데이터의 누적 시간 및 횟수를 확인 가능

> **특징**  
> - 실시간 라벨링과 동기화된 센서 데이터 수집
> - 행동별 메타데이터(사진, 시작·종료 시각, 정확성 평가) 자동 저장
> - 스마트워치와 연동하여 IMU·오디오 등 멀티모달 데이터 라벨링 지원
> - 연구용 데이터셋 구축을 위한 효율적인 현장 수집·검증 프로세스

---

## 🛠 사용 기술 스택
- **Android** : Kotlin  
- **Data Format** : Timestamp 기반 CSV (행동 메타데이터 포함)  
- **Architecture** : MVVM, Activity-Fragment 구조  
