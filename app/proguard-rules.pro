# OpenCV – keep all native bindings
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# App classes
-keep class com.depth3d.app.** { *; }
