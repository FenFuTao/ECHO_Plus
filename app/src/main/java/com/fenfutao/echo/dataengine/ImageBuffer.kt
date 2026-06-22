package com.fenfutao.echo.dataengine

/**
 * 图像缓存区 — 存储引擎解析后输出的图像帧头与完整图像 hex 数据。
 *
 * 引擎在检测到图像时依次输出两条 DataEngineOutput(type=IMAGE_PACKET)：
 *   1. 图像前导帧（帧头信息 hex 编码）
 *   2. 图像原始字节的连续 hex 字符串
 *
 * 本缓存区将两者合并存储，只保留最新一帧完整图像。
 * 单帧大小上限 10MB（原始字节），对应 hex 字符串不超过 20MB。
 */
class ImageBuffer {

    /** 图像前导帧 hex 文本（帧头信息） */
    var preambleHex: String = ""
        private set

    /** 图像原始数据的连续 hex 字符串 */
    var dataHex: String = ""
        private set

    /** 是否已收集到一帧完整的图像（前导帧 + 数据） */
    var hasCompleteImage: Boolean = false
        private set

    /** 图像原始字节数 */
    var rawByteCount: Int = 0
        private set

    /** 标记当前是否正在收集图像数据（前导帧已到，等待数据帧） */
    var isCollecting: Boolean = false
        private set

    private val maxHexLength = 20 * 1024 * 1024 // 20MB hex ≈ 10MB raw

    /**
     * 馈送 IMAGE_PACKET 类型的输出。
     * 第一次调用存储前导帧，第二次调用存储数据帧，标记完成。
     *
     * @param hexText 引擎输出的连续 hex 字符串
     */
    fun feed(hexText: String) {
        if (hexText.isEmpty()) return

        if (!isCollecting) {
            // 第一帧：图像前导帧
            preambleHex = hexText
            dataHex = ""
            rawByteCount = 0
            hasCompleteImage = false
            isCollecting = true
        } else {
            // 第二帧：图像数据
            dataHex = hexText
            rawByteCount = hexText.length / 2
            hasCompleteImage = true
            isCollecting = false

            // 上限保护（超过 20MB hex 则截断）
            if (dataHex.length > maxHexLength) {
                dataHex = dataHex.substring(0, maxHexLength)
                rawByteCount = maxHexLength / 2
            }
        }
    }

    /** 重置全部状态 */
    fun reset() {
        preambleHex = ""
        dataHex = ""
        hasCompleteImage = false
        rawByteCount = 0
        isCollecting = false
    }
}
