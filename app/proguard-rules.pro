# WebRTC invokes these optional generated JNI bindings indirectly. They are not
# shipped as Java classes in the selected AAR, so R8 must not treat them as
# unresolved program dependencies.
-dontwarn org.webrtc.BuiltinAudioDecoderFactoryFactoryJni
-dontwarn org.webrtc.BuiltinAudioEncoderFactoryFactoryJni
-dontwarn org.webrtc.EnvironmentJni
-dontwarn org.webrtc.HistogramJni
-dontwarn org.webrtc.JniCommonJni
-dontwarn org.webrtc.LoggingJni
-dontwarn org.webrtc.PeerConnectionFactoryJni
-dontwarn org.webrtc.SoftwareVideoDecoderFactoryJni
-dontwarn org.webrtc.SoftwareVideoEncoderFactoryJni
-dontwarn org.webrtc.TimestampAlignerJni
-dontwarn org.webrtc.YuvHelperJni
-dontwarn org.webrtc.audio.JavaAudioDeviceModuleJni
