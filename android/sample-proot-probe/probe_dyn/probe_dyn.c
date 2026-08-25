// 探针 D（system_linker_exec）用的极小"动态链接"可执行程序：main 返回 42。
//
// 与探针 A 的静态 PIE 不同：本程序按默认方式编译为动态链接（依赖 libc.so，
// PT_INTERP=/system/bin/linker64），用于验证 Termux 的 system_linker_exec 绕法：
//   不直接 execve 本文件（app_data_file 会被 SELinux 拒），而是
//   execve("/system/bin/linker64", "linker64", <本文件绝对路径>)，
//   由系统链接器 mmap 装载并运行。内核只看到执行了 linker64。
//
// 它被放进探针插件的 jniLibs，框架安装插件时解压到
// filesDir/plugins/<id>/lib/<abi>/（app_data_file）。探针 D 对该路径做
// system_linker_exec：
//   退出码 42      -> 插件目录里的动态二进制可经 linker64 装载运行（框架可据此增强 runner）
//   非 42 / 被拒   -> 本机该绕法不成立，执行底座仍须完全依赖 nativeLibraryDir
int main(void) {
    return 42;
}
