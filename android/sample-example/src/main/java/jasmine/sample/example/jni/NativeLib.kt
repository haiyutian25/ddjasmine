package jasmine.sample.example.jni

/** Native 库接口类（演示 JNI；.so 缺失时方法调用会抛出 UnsatisfiedLinkError）。 */
class NativeLib {

    external fun stringFromJNI(): String
    external fun addNumbers(a: Int, b: Int): Int
    external fun calculateSquareRoot(number: Double): Double
    external fun processStringArray(stringArray: Array<String>): String
    external fun getSystemInfo(): String

    companion object {
        init {
            runCatching { System.loadLibrary("nativelib") }
        }
    }
}
