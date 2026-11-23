# Consumer R8/ProGuard rules for the :native-ui:android module (libemucoreh_dcui.so).
# JNI_OnLoad in dc_jni.cpp resolves these classes and their @JvmStatic methods by name,
# and DcShellNative exports external native entry points.
-keep class com.sbro.emucoreh.dcui.DcShellNative { *; }
-keep class com.sbro.emucoreh.dcui.DcShellHost { *; }
-keep class com.sbro.emucoreh.dcui.DcTextRasterizer { *; }
-keep class com.sbro.emucoreh.dcui.DcImageLoader { *; }
-keep class com.sbro.emucoreh.dcui.DcShellAudio { *; }
-keep class com.sbro.emucoreh.dcui.DcShellSurfaceRenderer { *; }
