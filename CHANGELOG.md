# Changelog — Depth3D

All notable changes will be documented in this file.
Format: [SemVer](https://semver.org/) | Date: YYYY-MM-DD

---

## [1.0.2] — 2026-03-08  ← OpenCV 4.9.0 타입 호환성 + 추가 버그수정

### Fixed — 컴파일 오류 (2건)
- **TYPE-1** `DistanceEstimator`: `solvePnP` rvec/tvec 타입 `Mat` → `MatOfDouble`
  OpenCV 4.9.0 Maven API 시그니처 변경 대응
- **TYPE-2** `DistanceEstimator`: `solvePnP` distCoeffs 타입 `Mat` → `MatOfDouble`
  `getDistCoeffs()` 반환값(Mat) 대신 `MatOfDouble(k1..k3)` 직접 생성으로 교체

### Fixed — 데이터 정밀도 (1건)
- **PREC-1** `CalibrationData`: `SharedPreferences` 저장 방식 `putFloat` → `putString`
  Float 정밀도(~7자리)로 인해 fx/fy(~1000–4000) 저장 시 소수 정밀도 손실 발생.
  `Double.toString()` / `toDouble()` 방식으로 완전 정밀도 보존

### Fixed — 렌더링 (1건)
- **RENDER-1** `OverlayView`: `setLayerType(LAYER_TYPE_SOFTWARE, null)` 추가
  `setShadowLayer()`는 API 28 미만 기기에서 하드웨어 가속 Canvas의 텍스트에 적용 안 됨.
  minSdk=26 대응: 소프트웨어 레이어 강제 지정으로 API 26-27에서도 그림자 렌더링 보장

### Fixed — 코드 정확성 (1건)
- **SHAPE-1** `CalibrationData.getDistCoeffs()`: Mat 형상 `(1,5)` → `(5,1)`
  `calibrateCamera()` 출력과 동일한 5×1 열벡터 형상으로 수정

---

## [1.0.1] — 2026-03-08  ← 진단 및 버그수정

### Fixed — CRITICAL (4건)
- **CRITICAL-1** `FrameAnalyzer`: `captureFrame()` 소유권 이전 후 이중 release → native crash
- **CRITICAL-2** `OverlayView.drawDistanceText()`: 지역변수 `r`이 Float 파라미터 shadow → 컴파일 에러
- **CRITICAL-3** `FrameAnalyzer`: `image.close()` 전 `calibrateCamera()` 실행 → CameraX 계약 위반
- **CRITICAL-4** `CalibrationManager`: `distCoeffs = Mat.zeros(8,1)` → `Mat.zeros(5,1)`

### Fixed — HIGH (3건)
- **HIGH-1** `OverlayView`: `BlurMaskFilter` → `setShadowLayer()`
- **HIGH-2** `build.yml`: `gradle` → `./gradlew`
- **HIGH-3** `CalibrationManager`: 매 프레임 CLAHE 객체 생성 → 클래스 멤버로 이동

### Fixed — MEDIUM (3건)
- **MEDIUM-1** `MainActivity`: deprecated `setTargetResolution()` → `ResolutionSelector`
- **MEDIUM-2** `DistanceEstimator`: 미사용 import 제거
- **MEDIUM-3** `MainActivity.onDestroy()`: `shutdown()` → `shutdownNow()` + `awaitTermination(2s)`

---

## [1.0.0] — 2026-03-08

### Added
- Initial release
- CameraX 1.3.1 preview + ImageAnalysis
- 원형 ROI 오버레이
- OpenCV 4.9.0 체커보드 감지 (9×6, 30mm)
- calibrateCamera + cornerSubPix, RMS < 2.0 기준
- SharedPreferences 캘리브레이션 영속화
- solvePnP 거리 측정 + 핀홀 fallback
- GitHub Actions CI/CD → APK 자동 빌드

---

## [Unreleased]
- Phase 2: Depth map visualization
- 다중 객체 거리 추적
- 캘리브레이션 JSON 내보내기
