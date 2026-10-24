package ktron.compression.bench

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path

/** What the process holds outside the JVM heap, for the leak checks, and this thread's heap allocation. */
object NativeStats {
    private val linker = Linker.nativeLinker()

    // struct mallinfo2 { size_t arena, ordblks, smblks, hblks, hblkhd, usmblks, fsmblks, uordblks, fordblks, keepcost; }
    private val MALLINFO2_LAYOUT = MemoryLayout.structLayout(*Array(10) { JAVA_LONG })
    private val MALLINFO2 = linker.downcallHandle(linker.defaultLookup().find("mallinfo2").orElseThrow(), FunctionDescriptor.of(MALLINFO2_LAYOUT))
    private val arena = Arena.ofAuto()
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /** Bytes malloc has handed out and not had back, over every arena: uordblks plus the mmapped hblkhd. */
    fun mallocInUse(): Long {
        val m = MALLINFO2.invokeExact(arena as java.lang.foreign.SegmentAllocator) as java.lang.foreign.MemorySegment
        return m.getAtIndex(JAVA_LONG, 7) + m.getAtIndex(JAVA_LONG, 4)
    }

    fun rssBytes(): Long = Files.readString(Path.of("/proc/self/statm")).trim().split(' ')[1].toLong() * 4096

    /** This thread's minor page faults: field 10 of /proc/thread-self/stat. */
    fun minorFaults(): Long {
        val stat = Files.readString(Path.of("/proc/thread-self/stat"))
        return stat.substring(stat.lastIndexOf(')') + 2).split(' ')[7].toLong()
    }

    fun allocatedBytes(): Long = threads.currentThreadAllocatedBytes
}
