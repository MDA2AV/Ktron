package ktron.compression

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

// The codecs' native side, the way ioxide's Kotlin port does FFM: pointers travel as Long addresses, and one
// segment over the whole address space reads and writes them (global scope, so no liveness check per access).
@JvmField
internal val MEM: MemorySegment = MemorySegment.NULL.reinterpret(Long.MAX_VALUE)

internal val LINKER: Linker = Linker.nativeLinker()

/** A shared library by soname, loaded once for the process. */
internal fun library(soname: String, apt: String): SymbolLookup =
    try {
        SymbolLookup.libraryLookup(soname, Arena.global())
    } catch (e: IllegalArgumentException) {
        throw UnsatisfiedLinkError("$soname could not be loaded (the $apt package provides it): ${e.message}")
    }

internal fun SymbolLookup.downcall(name: String, descriptor: FunctionDescriptor): MethodHandle =
    LINKER.downcallHandle(find(name).orElseThrow { UnsatisfiedLinkError(name) }, descriptor)

internal object Libc {
    private val lookup = LINKER.defaultLookup()
    private val MALLOC = lookup.downcall("malloc", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG))
    private val FREE = lookup.downcall("free", FunctionDescriptor.ofVoid(JAVA_LONG))

    fun malloc(size: Long): Long {
        val p = MALLOC.invokeExact(size) as Long
        if (p == 0L) throw OutOfMemoryError("malloc($size)")
        return p
    }

    fun free(addr: Long) {
        if (addr != 0L) FREE.invokeExact(addr)
    }
}

/** A malloc'd block that only grows; growing drops the contents. Its owner frees it. */
internal class NativeBuffer {
    var address = 0L
        private set
    var capacity = 0L
        private set

    fun reserve(size: Long) {
        if (size <= capacity) return
        val p = Libc.malloc(size)
        Libc.free(address)
        address = p
        capacity = size
    }

    fun free() {
        Libc.free(address)
        address = 0L
        capacity = 0L
    }
}
