package com.fenfutao.echo.dataengine

import com.fenfutao.echo.util.AppLogger

/**
 * RawData 协议引擎
 *
 * 协议特点：纯透传模式，不做任何采样数据解析和图片解析。
 * 接收到什么字节，便一五一十输出什么数据。
 *
 * 适用场景：将 VOFA+ 当成串口调试助手使用，仅查看原始字节流。
 */
class RawDataEngine : DataEngine {

    /** 内部字节缓冲区（累积不完整的数据） */
    private val buffer = mutableListOf<Byte>()

    /** 最大缓冲区大小，防止内存泄漏 */
    private val maxBufferSize = 1024 * 1024 // 1MB

    override fun feed(connectionId: String, data: ByteArray): List<DataEngineOutput> {
        if (data.isEmpty()) return emptyList()

        // 将新数据加入缓冲区
        buffer.addAll(data.toList())

        // 截断过大的缓冲区
        if (buffer.size > maxBufferSize) {
            val excess = buffer.size - maxBufferSize
            AppLogger.w("RawDataEngine", "缓冲区超出上限，丢弃 $excess 字节")
            for (i in 0 until excess) {
                buffer.removeAt(0)
            }
        }

        // RawData：直接将接收到的原始字节转换为可读格式
        val output = buildRawDataOutput(data, connectionId)

        return listOf(output)
    }

    /**
     * 将原始字节构建为输出文本
     * RawData 直接输出原始字节的连续十六进制字符串。
     * hex→UTF-8 的转换在显示函数中内嵌处理。
     */
    private fun buildRawDataOutput(data: ByteArray, connectionId: String): DataEngineOutput {
        // 输出连续十六进制（无空格），交由显示函数决定显示为 hex 还是 UTF-8
        val text = data.joinToString("") { String.format("%02X", it) }
        // escapeControlChars=true：Abc 模式显示时，\n \r \t 等控制字符转义为 \n \r \t 等字符串
        return DataEngineOutput(text = text, escapeControlChars = true)
    }

    /**
     * 获取当前缓冲区中的全部原始字节（用于调试）
     */
    fun getBufferedBytes(): ByteArray = buffer.toByteArray()

    override fun reset() {
        buffer.clear()
        AppLogger.i("RawDataEngine", "引擎状态已重置")
    }

    override fun getEngineName(): String = "RawData"
}
