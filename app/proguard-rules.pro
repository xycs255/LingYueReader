# sherpa-onnx 的原生库按名字反射读取配置类的字段、回调 GeneratedAudio 的构造函数，不能混淆或删掉。
-keep class com.k2fsa.sherpa.onnx.** { *; }

# MediaPipe 的 LLM 推理：原生库按名字回调 Java 类（结果监听、配置），不能混淆或删掉。
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.auto.value.**

# 闪退记录（CrashLog）里的堆栈要带行号：配合打包时生成的 mapping.txt 才查得到出错的那一行。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
