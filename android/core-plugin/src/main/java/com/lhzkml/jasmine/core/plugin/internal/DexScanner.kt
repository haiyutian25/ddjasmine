package com.lhzkml.jasmine.core.plugin.internal

import android.os.Build
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.MultiDexContainer
import org.jf.dexlib2.immutable.ImmutableDexFile
import java.io.File

/**
 * Runtime DEX class-index scan. The build-time scan (class_index emitted by
 * the packaging plugin) is the primary source; this is the fallback for
 * packages that arrive without one.
 */
internal object DexScanner {

    /** Every concrete class declared in the package's DEX files. */
    fun scanClassNames(apk: File): List<String> {
        val container: MultiDexContainer<out DexBackedDexFile> =
            DexFileFactory.loadDexContainer(apk, Opcodes.forApi(Build.VERSION.SDK_INT))
        val names = mutableListOf<String>()
        for (dexName in container.dexEntryNames) {
            val entry = container.getEntry(dexName) ?: continue
            val dexFile = ImmutableDexFile.of(entry.dexFile)
            for (classDef in dexFile.classes) {
                if (classDef.type.startsWith("L") && classDef.type.endsWith(";")) {
                    names += classDef.type.substring(1, classDef.type.length - 1).replace('/', '.')
                }
            }
        }
        return names
    }
}
