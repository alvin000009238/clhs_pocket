package com.clhs.score.data

import java.lang.management.ManagementFactory
import org.junit.Test

class CollectionAllocationProbeTest {
    @Volatile private var sink: Any? = null
    private fun before(priority: List<String>, existing: Set<String>, limit: Int): Set<String> =
        buildList { addAll(priority); addAll(existing) }.distinct().take(limit).toSet()
    private fun after(priority: List<String>, existing: Set<String>, limit: Int): Set<String> {
        val result = LinkedHashSet<String>()
        for (id in priority) { result.add(id); if (result.size == limit) return result }
        for (id in existing) { result.add(id); if (result.size == limit) return result }
        return result
    }
    @Test fun probe() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().threadId()
        val output = StringBuilder("case,round,side,ns_per_call,bytes_per_call\n")
        for ((name, count, limit) in listOf(Triple("small", 20, 500), Triple("baseline", 20, 500), Triple("known-cap", 600, 500), Triple("unread-cap", 120, 100))) {
            val priority = List(count) { "id-${it / 2}" }
            val existing = if (name == "baseline") emptySet() else (0 until limit).map { "id-$it" }.toSet()
            check(before(priority, existing, limit).toList() == after(priority, existing, limit).toList())
            repeat(10000) { sink = before(priority, existing, limit); sink = after(priority, existing, limit) }
            repeat(10) { round ->
                for (side in if (round % 2 == 0) listOf("before", "after") else listOf("after", "before")) {
                    val bytes = bean.getThreadAllocatedBytes(tid)
                    val start = System.nanoTime()
                    repeat(3000) { sink = if (side == "before") before(priority, existing, limit) else after(priority, existing, limit) }
                    val elapsed = System.nanoTime() - start
                    output.append("$name,$round,$side,${elapsed / 3000.0},${(bean.getThreadAllocatedBytes(tid) - bytes) / 3000.0}\n")
                }
            }
        }
        java.io.File("build/reports/bounded.csv").apply { parentFile?.mkdirs() }.writeText(output.toString())
    }
}
