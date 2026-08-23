cmake_minimum_required(VERSION 3.22.1)

project("nativelib")

add_library(${CMAKE_PROJECT_NAME} SHARED nativelib.cpp)

target_link_libraries(${CMAKE_PROJECT_NAME} android log)

# PIE executable for the ExecBridge dlopen verification. Built as a PIE
# exporting `main`; the native shim dlopen()s it and runs `main`, proving
# executables work without an execve-capable mount. Gradle copies the built
# binary into assets/exec/hello (see build.gradle.kts).
add_executable(hello hello.c)
set_target_properties(hello PROPERTIES PIE TRUE)
