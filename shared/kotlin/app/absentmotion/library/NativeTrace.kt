package app.absentmotion.library

/** Reads the public debuggerd tombstone.proto fields without retaining memory/register dumps.
 * Schema: platform/system/core/debuggerd/proto/tombstone.proto (AOSP).
 */
private class TraceSlice(val data: ByteArray, val start: Int = 0, val end: Int = data.size) {
    fun text(): String = data.copyOfRange(start,end).decodeToString()
    fun fields(): Map<Int,List<TraceValue>> {
        val result = mutableMapOf<Int,MutableList<TraceValue>>()
        var at = start
        fun uint(): Long {
            var value = 0L
            for (shift in 0..63 step 7) {
                require(at < end)
                val b = data[at++].toInt() and 255
                if (shift == 63) require(b <= 1)
                value = value or ((b and 127).toLong() shl shift)
                if (b < 128) return value
            }
            error("Invalid trace varint")
        }
        var count = 0
        while (at < end) {
            require(++count <= 100_000)
            val tag = uint()
            val key = (tag ushr 3).toInt()
            require(key > 0)
            val value = when ((tag and 7).toInt()) {
                0 -> TraceValue(number=uint())
                1 -> { require(at <= end-8); at+=8; null }
                2 -> {
                    val size = uint()
                    require(size >= 0 && size <= end-at)
                    val slice = TraceSlice(data,at,at+size.toInt()); at+=size.toInt()
                    TraceValue(bytes=slice)
                }
                5 -> { require(at <= end-4); at+=4; null }
                else -> error("Invalid trace wire type")
            }
            if (value != null) result.getOrPut(key) { mutableListOf() }.add(value)
        }
        return result
    }
}
private data class TraceValue(val number: Long = 0, val bytes: TraceSlice? = null)
private fun Map<Int,List<TraceValue>>.number(key: Int) = get(key)?.firstOrNull()?.number ?: 0
private fun Map<Int,List<TraceValue>>.text(key: Int) = get(key)?.firstOrNull()?.bytes?.text().orEmpty()

internal fun nativeTrace(bytes: ByteArray, maxChars: Int): String? = runCatching {
    val top = TraceSlice(bytes).fields()
    val tid = top.number(6)
    val threads = top[16].orEmpty().mapNotNull { entry ->
        val map = entry.bytes?.fields() ?: return@mapNotNull null
        val body = map[2]?.firstOrNull()?.bytes?.fields() ?: return@mapNotNull null
        map.number(1) to body
    }
    require(tid > 0 && threads.any { it.first == tid })
    buildString {
        append("Build: ").append(top.text(2)).append('\n')
        append("PID: ").append(top.number(5)).append("; crashed TID: ").append(tid).append('\n')
        top[10]?.firstOrNull()?.bytes?.fields()?.let { signal ->
            append("Signal: ").append(signal.text(2)).append(" (").append(signal.number(1)).append(") ").append(signal.text(4)).append('\n')
            if (signal.number(8) != 0L) append("Fault address: 0x").append(signal.number(9).toULong().toString(16)).append('\n')
        }
        if (top.text(14).isNotEmpty()) append("Abort: ").append(top.text(14)).append('\n')
        top[15].orEmpty().forEach { it.bytes?.fields()?.text(1)?.let { message -> append("Cause: ").append(message).append('\n') } }
        for ((id,thread) in threads.sortedBy { if (it.first == tid) 0 else if (it.first == top.number(5)) 1 else 2 }) {
            if (length >= maxChars) break
            append("\nThread ").append(id).append(" · ").append(thread.text(2))
            if (id == tid) append(" [CRASHED]")
            append('\n')
            thread[4].orEmpty().take(if (id == tid) 96 else 12).forEachIndexed { index, frame ->
                frame.bytes?.fields()?.let { f ->
                    append('#').append(index.toString().padStart(2,'0')).append(" pc ").append(f.number(1).toULong().toString(16).padStart(16,'0'))
                    append(' ').append(f.text(6))
                    if (f.text(4).isNotEmpty()) append(" (").append(f.text(4)).append('+').append(f.number(5)).append(')')
                    if (f.text(8).isNotEmpty()) append(" [BuildId ").append(f.text(8)).append(']')
                    append('\n')
                }
            }
            thread[7].orEmpty().forEach { it.bytes?.text()?.let { note -> append(note).append('\n') } }
        }
    }.take(maxChars)
}.getOrNull()
