package com.lhzkml.jasmine.core.data

import com.lhzkml.jasmine.core.kernel.Kernel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The process-wide plugin kernel. Created lazily on first plugin work and
 * alive for the process lifetime — plugin fibers mount into it and its
 * confining thread owns every registry mutation.
 */
@Singleton
class KernelHolder @Inject constructor() {

    val kernel: Kernel by lazy { Kernel() }
}
