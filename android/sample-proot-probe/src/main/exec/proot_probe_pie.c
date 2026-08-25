// 极小静态 PIE：仅以状态码 42 退出（exit(42)），无 libc、无动态依赖。
//
// 用途：作为"探针 A（nativeLibraryDir execve）"的地基。把它打包进宿主
// app 的 jniLibs（arm64-v8a/libproot_probe_pie.so），安装后随其它 .so 一起
// 被提取到宿主 nativeLibraryDir。探针从该目录 execve 它：
//   - 退出码 42        -> nativeLibraryDir 的 execve 被放行（proot/loader 有落点）
//   - errno 13 EACCES  -> 被 SELinux 拒（nativeLibraryDir 也不可 execve）
//   - errno 8  ENOEXEC -> 放行但 ELF 不被识别（理论上不应出现）
//
// 与 proot 本体的相似性：同为 ET_DYN（PIE）、经内核 execve 装载。proot 是
// 动态链接（需 /system/bin/linker64 作 PT_INTERP），本探针为静态（无 interp），
// 是最小的自包含可执行证明。
//
// 编译（NDK，arm64）：
//   clang --target=aarch64-linux-android26 -static-pie -nostdlib \
//         -o libproot_probe_pie.so proot_probe_pie.c

#if defined(__aarch64__)

void _start(void) {
    register long x0 __asm__("x0") = 42;  // exit status
    register long x8 __asm__("x8") = 93;  // __NR_exit (aarch64)
    __asm__ volatile("svc #0" :: "r"(x0), "r"(x8) : "memory");
    __builtin_unreachable();
}

#else
/* 非 arm64 兜底（测试机为 arm64-v8a，此分支仅防编译报错）。 */
void _start(void) {
    for (;;) {
    }
}
#endif
