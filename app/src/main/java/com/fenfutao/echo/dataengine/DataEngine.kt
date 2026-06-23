package com.fenfutao.echo.dataengine

/**
 * 数据引擎接口
 *
 * 所有协议引擎（RawData、FireWater、JustFloat）均实现此接口。
 * 输入原始字节流，输出供显示窗口渲染的文本条目。
 */
interface DataEngine {

    /**
     * 向引擎喂入原始字节数据
     * @param connectionId 连接标识（如 "TCP:192.168.1.1:8080"、"串口:COM3"）
     * @param data 从连接接收到的原始字节
     * @return 引擎处理产生的输出条目列表，需在 UI 线程显示
     */
    fun feed(connectionId: String, data: ByteArray): List<DataEngineOutput>

    /**
     * 设置是否过滤图像数据包。
     * 当 filtered=true 时，引擎不应接收/累积任何图像数据，
     * 图像前导帧被视为普通数据处理。
     */
    fun setImagePacketFiltered(filtered: Boolean) { }

    /**
     * 重置引擎内部状态（切换协议/断开连接时调用）
     */
    fun reset()

    /** 获取引擎名称 */
    fun getEngineName(): String
}

/**
 * 数据引擎输出条目
 */
data class DataEngineOutput(
    /** 要显示的文本内容 */
    val text: String,
    /** 输出类型 */
    val type: OutputType = OutputType.TEXT,
    /**
     * 是否需要对显示文本中的控制字符（\n \r \t 等）进行转义显示。
     * RawData 模式下为 true，FireWater / JustFloat 下为 false。
     */
    val escapeControlChars: Boolean = false,
    /**
     * 是否为图像原始数据载荷（原始 hex 数据）。
     * 为 true 时仅进入 ImageBuffer，不应在显示区渲染。
     */
    val isImageDataPayload: Boolean = false
)

/** 输出类型 */
enum class OutputType {
    /** 普通文本 */
    TEXT,
    /** 图片数据包（前导帧 + 原始数据）的摘要 */
    IMAGE_PACKET
}
