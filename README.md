# Depth3D — v1.0.0

Android app for real-time distance estimation using a single camera and checkerboard calibration.
Target device: **Samsung Galaxy Note 20**.

---

## 🚀 APK 빌드 방법 (GitHub Actions — Android Studio 불필요)

### Step 1: GitHub 저장소 생성

```bash
git init
git add .
git commit -m "feat: initial release v1.0.0"
git remote add origin https://github.com/<YOUR_USERNAME>/Depth3D.git
git push -u origin main
```

### Step 2: APK 다운로드

1. GitHub 저장소 → **Actions** 탭
2. 최신 `Build Depth3D APK` 워크플로 클릭
3. 하단 **Artifacts** 섹션 → `Depth3D-v1.0.0-release` 다운로드
4. ZIP 압축 해제 → `app-release.apk`

### Step 3: 핸드폰에 설치

```
설정 → 보안 → 출처를 알 수 없는 앱 → 허용
```
APK 파일 클릭 → 설치

---

## 📋 앱 사용 방법

### 1단계: 체커보드 준비
- A4 용지에 **9×6 내부 코너 (10×7 칸)** 체커보드 인쇄
- 각 칸 = 정확히 **30mm × 30mm**
- 무광 용지 권장 (반사 방지)

### 2단계: 캘리브레이션
1. `📐 캘리브레이션` 버튼 클릭
2. 체커보드를 화면 중앙 원 안에 위치
3. 초록색 테두리 감지 확인 → `📷 캡처` 클릭
4. **15번** 다른 각도/거리에서 반복
5. 자동으로 캘리브레이션 계산 (RMS < 2.0 → 성공)

### 3단계: 거리 측정
1. `📏 측정 시작` 클릭
2. 체커보드를 카메라에 보여주면 거리 자동 표시

---

## 🔧 기술 스택

| 항목 | 스펙 |
|---|---|
| 언어 | Kotlin 1.9 |
| 카메라 | CameraX 1.3.1 |
| 영상처리 | OpenCV 4.9.0 |
| 거리 추정 | solvePnP (PnP method) |
| min SDK | 26 (Android 8.0+) |
| target SDK | 34 (Android 14) |

---

## 📁 프로젝트 구조

```
app/src/main/java/com/depth3d/app/
├── MainActivity.kt              # 메인 액티비티
├── camera/
│   └── FrameAnalyzer.kt        # CameraX 프레임 분석
├── calibration/
│   ├── CalibrationManager.kt   # 체커보드 캘리브레이션
│   └── CalibrationData.kt      # 카메라 파라미터 저장
├── detection/
│   └── DistanceEstimator.kt    # 거리 계산 (solvePnP)
└── ui/
    └── OverlayView.kt          # 원형 ROI 오버레이
```

---

## 📝 버전 이력

See [CHANGELOG.md](CHANGELOG.md)
