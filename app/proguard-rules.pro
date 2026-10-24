# sherpa-onnx 的原生库按名字反射读取配置类的字段、回调 GeneratedAudio 的构造函数，不能混淆或删掉。
-keep class com.k2fsa.sherpa.onnx.** { *; }
