#include <stdio.h>

// PIE executable used to verify the ExecBridge dlopen path. Built with
// -fPIE -pie and placed in assets/exec/hello; the framework extracts it to
// the (noexec) filesDir and the native shim dlopen()s it, resolves `main`,
// and runs it — proving executables work without an execve-capable mount.
int main(int argc, char **argv) {
    printf("hello from dlopen bridge! argc=%d\n", argc);
    for (int i = 0; i < argc; i++) {
        printf("  argv[%d]=%s\n", i, argv[i]);
    }
    return 42;
}
