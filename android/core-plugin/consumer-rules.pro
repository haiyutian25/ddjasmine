# Plugin classes are loaded by name at runtime (entry class, components),
# so shrinking/renaming must keep plugin-facing contracts intact.
-keep interface com.lhzkml.jasmine.core.plugin.PluginEntry { *; }
-keep interface com.lhzkml.jasmine.core.plugin.component.PluginActivity { *; }
-keep interface com.lhzkml.jasmine.core.plugin.component.PluginService { *; }
-keep interface com.lhzkml.jasmine.core.plugin.component.PluginReceiver { *; }
