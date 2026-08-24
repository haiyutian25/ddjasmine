# Plugin classes are loaded by name at runtime (entry class, components),
# so shrinking/renaming must keep plugin-facing contracts intact.
-keep interface com.lhzkml.jasmine.core.plugin.PluginEntry { *; }
-keep interface com.lhzkml.jasmine.core.plugin.component.PluginActivity { *; }
-keep interface com.lhzkml.jasmine.core.plugin.component.PluginService { *; }
-keep interface com.lhzkml.jasmine.core.plugin.component.PluginReceiver { *; }

# UniFFI 生成的 Rust 绑定（com.lhzkml.jasmine.core.plugin.rust）经 JNA 按
# 方法名/字段名反射调用；混淆重命名会让 native 调用全部失联。宿主当前
# isMinifyEnabled=false 暂未触发，但任何开启混淆的宿主接入即崩，故在此兜底。
-keep class com.lhzkml.jasmine.core.plugin.rust.** { *; }

# JNA 依赖类名/方法名/字段名做 native 绑定，结构与回调均不可混淆。
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }
-keepclassmembers class * implements com.sun.jna.Callback { *; }
-keep class * implements com.sun.jna.Library { *; }
