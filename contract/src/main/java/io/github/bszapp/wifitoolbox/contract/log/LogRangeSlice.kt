package io.github.bszapp.wifitoolbox.contract.log

/** 日志 ID 连续；按保留范围计算下标，读取一页无需遍历全部历史。 */
fun <T> List<T>.sliceLogRange(oldestId: Long, fromId: Long, toId: Long, limit: Int): List<T> {
    if (isEmpty() || toId < oldestId || fromId > toId) return emptyList()
    val start = (maxOf(oldestId, fromId) - oldestId).coerceAtMost(size.toLong()).toInt()
    val end = minOf(size.toLong(), toId - oldestId + 1L, start.toLong() + limit).toInt()
    return if (end <= start) emptyList() else subList(start, end).toList()
}
