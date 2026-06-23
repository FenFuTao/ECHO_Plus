package com.fenfutao.echo.dataengine

/**
 * 参数缓存区 - 记录数据引擎解析后回传的参数列表。
 *
 * 应用启动并首次建立连接后，根据 FireWater 或 JustFloat 引擎解析的
 * 第一帧数据锁定期望的参数数量。后续接收的数据若参数数量不匹配则丢弃。
 *
 * 存储格式：每个条目包含时间戳和参数值列表。
 */
class ParamBuffer {

    /** 缓存条目：时间戳 + 参数值列表 */
    data class ParamEntry(
        val timestamp: Long,
        val values: List<String>
    ) {
        /** 获取显示格式字符串，如 "[12:34:56.789] 12.6, 58.0, 65.0" */
        fun toDisplayString(): String {
            val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            val timeStr = sdf.format(java.util.Date(timestamp))
            return "[$timeStr] ${values.joinToString(", ")}"
        }
    }

    /** 是否已完成参数数量锁定 */
    var isLocked: Boolean = false
        private set

    /** 期望的参数数量（锁定后有效） */
    var expectedCount: Int = 0
        private set

    /** 参数名称列表 */
    val paramNames = mutableListOf<String>()

    /** 最近一帧数据段的标题（无冒号），锁定后有效，为空表示无前缀 */
    var lastPrefix: String = ""
        private set

    /** 用于 FPS 估算的接收时间戳队列（保存最近 ~500ms 的帧到达时间） */
    private val frameTimestamps = java.util.ArrayDeque<Long>()

    /** FPS 估算窗口（毫秒） */
    private val fpsWindowMs = 1000L

    /**
     * 获取估算的 FPS。
     * 统计最近 fpsWindowMs 毫秒内接收的有效数据帧数。
     */
    fun getEstimatedFps(): Int {
        val now = System.currentTimeMillis()
        val cutoff = now - fpsWindowMs
        // 移除过期的时间戳
        while (frameTimestamps.isNotEmpty() && frameTimestamps.peekFirst() < cutoff) {
            frameTimestamps.pollFirst()
        }
        if (frameTimestamps.isEmpty()) return 0
        // fps = count / windowSeconds
        val windowSeconds = fpsWindowMs / 1000.0
        return (frameTimestamps.size / windowSeconds).toInt().coerceAtLeast(0)
    }

    /** 缓存条目 */
    private val entries = mutableListOf<ParamEntry>()

    /** 每个 data 值的文本最大长度（不超过 int16 范围，-32768 共 6 位） */
    private val maxValueLength = 6

    /** 参数数量上限 */
    private val maxParamCount = 32

    /** 最大缓存条目数 */
    private val maxEntries = 5000

    /** 当前缓存条目数 */
    val entryCount: Int get() = entries.size

    /** 获取全部缓存条目的只读副本 */
    fun getEntries(): List<ParamEntry> = entries.toList()

    /**
     * 馈送已解码的文本行到缓存区。
     *
     * @param engineName 引擎名称（"FireWater" / "JustFloat"）
     * @param text 已解码为 UTF-8 的可读文本（经过 hexToString 转换后）
     * @return true 表示该帧数据被接受并写入缓存，false 表示被丢弃
     */
    fun feed(engineName: String, text: String): Boolean {
        if (text.isBlank()) return false

        val values = when (engineName) {
            "FireWater" -> parseFireWaterLine(text)
            "JustFloat" -> parseJustFloatLine(text)
            else -> return false
        }

        if (values == null || values.isEmpty()) return false

        // ── 首次有效数据：锁定参数数量 ──
        if (!isLocked) {
            isLocked = true
            expectedCount = values.size
            // 若 paramNames 尚未由解析方法填充，生成默认名称
            if (paramNames.isEmpty()) {
                for (i in 0 until expectedCount) {
                    paramNames.add("param${i + 1}")
                }
            }
            // 写入首帧
            entries.add(ParamEntry(System.currentTimeMillis(), values))
            return true
        }

        // ── 参数数量不匹配则丢弃 ──
        if (values.size != expectedCount) return false

        // ── 写入缓存 ──
        val now = System.currentTimeMillis()
        entries.add(ParamEntry(now, values))
        if (entries.size > maxEntries) {
            entries.removeAt(0)
        }
        // ★ 记录时间戳用于 FPS 估算
        frameTimestamps.addLast(now)
        return true
    }

    /**
     * 解析 FireWater 行格式：`prefix:val1,val2,...,valN`
     *
     * 例如：`data1:12.6,58,65` → ["12.6", "58", "65"]
     *
     * 规则：
     * - 按 `:` 分割，前段为前缀，后段为逗号分隔的值列表
     * - 排除 `image:` 前缀（图片前导帧）
     * - 排除以 `[` 开头的行（提示信息，如 "[缓冲区溢出]"）
     * - 首次锁定时以前缀生成参数名称 prefix_ch0, prefix_ch1, ...
     */
    private fun parseFireWaterLine(line: String): List<String>? {
        val values: List<String>
        val prefix: String

        val colonIdx = line.indexOf(':')
        if (colonIdx < 0) {
            // ★ 无冒号：整行为逗号分隔的纯数值串（无前缀名称）
            prefix = ""
            val raw = line.trim()
            if (raw.isEmpty()) return null
            values = raw.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } else {
            prefix = line.substring(0, colonIdx).trim()
            // 数据段标题长度不应超过 16
            if (prefix.length > 16) return null
            // 跳过图片前导帧
            if (prefix.equals("image", ignoreCase = true)) return null
            // 跳过提示信息行（引擎输出的 [xxx] 格式）
            if (prefix.startsWith("[")) return null

            val valuesStr = line.substring(colonIdx + 1).trim()
            if (valuesStr.isEmpty()) return null

            values = valuesStr.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        if (values.isEmpty()) return null

        // ★ 检查每个 data 值的文本长度（不超过 int16 范围，-32768 共 6 位）
        if (values.any { it.length > maxValueLength }) return null

        // ★ 检查参数数量是否合理（不超过上限）
        if (values.size > maxParamCount) return null

        // 首次锁定时填充参数名称
        if (!isLocked) {
            paramNames.clear()
            lastPrefix = prefix
            for (i in values.indices) {
                paramNames.add("${prefix}_ch$i")
            }
        }

        return values
    }

    /**
     * 解析 JustFloat 帧行格式：`N通道: val1, val2, ..., valN`
     *
     * 例如：`3通道: 12.600000, 58.000000, 65.000000`
     *      → ["12.600000", "58.000000", "65.000000"]
     *
     * 规则：
     * - 按 `:` 分割，后段为逗号分隔的浮点数值列表
     * - 跳过以 `[` 或 `图片` 开头的非数据行
     * - 首次锁定时生成参数名称 ch0, ch1, ...
     */
    private fun parseJustFloatLine(line: String): List<String>? {
        // 跳过非数据行（提示信息）
        if (line.startsWith("[") || line.startsWith("图片")) return null

        val colonIdx = line.indexOf(':')
        if (colonIdx < 0) return null

        val afterColon = line.substring(colonIdx + 1).trim()
        val values = afterColon.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.toFloatOrNull() != null }
        if (values.isEmpty()) return null

        // ★ 检查每个 data 值的文本长度（不超过 10 位，即 int32 范围）
        if (values.any { it.length > maxValueLength }) return null

        // ★ 检查参数数量是否合理（不超过上限）
        if (values.size > maxParamCount) return null

        // 首次锁定时填充参数名称
        if (!isLocked) {
            paramNames.clear()
            lastPrefix = ""
            for (i in values.indices) {
                paramNames.add("ch$i")
            }
        }

        return values
    }

    /**
     * 重置全部状态。
     * 断开连接或切换数据引擎时调用，清空锁定计数和所有缓存。
     */
    fun reset() {
        isLocked = false
        expectedCount = 0
        paramNames.clear()
        lastPrefix = ""
        entries.clear()
        frameTimestamps.clear()
    }
}
