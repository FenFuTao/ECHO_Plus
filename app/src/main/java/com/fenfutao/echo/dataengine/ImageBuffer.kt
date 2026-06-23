package com.fenfutao.echo.dataengine

import com.fenfutao.echo.util.AppLogger

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

    /** 图像原始字节数据（完整，供 GPU 直通渲染） */
    var rawBytes: ByteArray? = null
        private set

    /** 图像宽度（像素） */
    var imageWidth: Int = 0
        private set

    /** 图像高度（像素） */
    var imageHeight: Int = 0
        private set

    /** 图像格式枚举值（与 VOFA+ 一致，24=Grayscale8） */
    var imageFormat: Int = 0
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

    /**
     * 新帧就绪标志。
     * 引擎喂入完整图像数据后置为 true，
     * 显示层通过 [consumeNewFrame] 读取并清除。
     */
    @Volatile
    var newFrameReady: Boolean = false
        private set

    private val maxHexLength = 20 * 1024 * 1024 // 20MB hex ≈ 10MB raw

    /**
     * 馈送 IMAGE_PACKET 类型的输出。
     * 第一次调用存储前导帧，第二次调用存储数据帧，标记完成。
     *
     * @param hexText 引擎输出的连续 hex 字符串
     * @param rawBytes 图像原始字节（完整，供 GPU 渲染）
     * @param width 图像宽度（仅前导帧有效）
     * @param height 图像高度（仅前导帧有效）
     * @param format 图像格式枚举值（仅前导帧有效）
     */
    fun feed(hexText: String, rawBytes: ByteArray? = null, width: Int = 0, height: Int = 0, format: Int = 0) {
        if (hexText.isEmpty()) return

        if (!isCollecting) {
            // 第一帧：图像前导帧
            preambleHex = hexText
            dataHex = ""
            this.rawBytes = null
            imageWidth = width
            imageHeight = height
            imageFormat = format
            rawByteCount = 0
            hasCompleteImage = false
            newFrameReady = false
            isCollecting = true
        } else {
            // 第二帧：图像数据
            dataHex = hexText
            this.rawBytes = rawBytes
            rawByteCount = hexText.length / 2
            hasCompleteImage = true
            isCollecting = false
            // ★ 标记新帧就绪，供显示层消费
            if (rawBytes != null && rawBytes!!.isNotEmpty()) {
                newFrameReady = true
                AppLogger.i("ImageBuffer", "新图像帧就绪: ${imageWidth}x${imageHeight}, format=$imageFormat, ${rawBytes!!.size}B")
            }

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
        rawBytes = null
        imageWidth = 0
        imageHeight = 0
        imageFormat = 0
        hasCompleteImage = false
        rawByteCount = 0
        isCollecting = false
        newFrameReady = false
    }

    /**
     * 消费新帧通知。
     * 调用后 [newFrameReady] 置为 false，返回当前图像帧数据。
     *
     * @return 图像帧数据，若无新帧或原始数据为空则返回 null
     */
    fun consumeNewFrame(): ImageFrame? {
        if (!newFrameReady) return null
        val bytes = rawBytes ?: return null
        if (bytes.isEmpty()) return null
        newFrameReady = false
        return ImageFrame(
            rawBytes = bytes,
            width = imageWidth,
            height = imageHeight,
            format = imageFormat
        )
    }
}

/**
 * 一帧完整图像数据，供显示层渲染。
 */
data class ImageFrame(
    val rawBytes: ByteArray,
    val width: Int,
    val height: Int,
    val format: Int
) {
    /** Grayscale8 格式枚举值 */
    companion object {
        const val FORMAT_GRAYSCALE8 = 24

        /** 将格式枚举值转为可读名称（与 VOFA+ 一致） */
        fun getFormatName(format: Int): String = when (format) {
            0 -> "Invalid"
            1 -> "Mono(MSB)"
            2 -> "Mono(LSB)"
            3 -> "Indexed8"
            4 -> "RGB32"
            5 -> "ARGB32"
            6 -> "ARGB32_Premultiplied"
            7 -> "RGB16(5-6-5)"
            8 -> "ARGB8565_Premultiplied"
            9 -> "RGB666"
            10 -> "ARGB6666_Premultiplied"
            11 -> "RGB555"
            12 -> "ARGB8555_Premultiplied"
            13 -> "RGB888"
            14 -> "RGB444"
            15 -> "ARGB4444_Premultiplied"
            16 -> "RGBX8888"
            17 -> "RGBA8888"
            18 -> "RGBA8888_Premultiplied"
            19 -> "BGR30"
            20 -> "A2BGR30_Premultiplied"
            21 -> "RGB30"
            22 -> "A2RGB30_Premultiplied"
            23 -> "Alpha8"
            24 -> "Grayscale8"
            25 -> "BMP"
            26 -> "GIF"
            27 -> "JPG"
            28 -> "PNG"
            29 -> "PBM"
            30 -> "PGM"
            31 -> "PPM"
            32 -> "XBM"
            33 -> "XPM"
            34 -> "SVG"
            else -> "未知($format)"
        }
    }

    val isGrayscale8: Boolean get() = format == FORMAT_GRAYSCALE8
    val dataSize: Int get() = rawBytes.size
    val isValid: Boolean
        get() = rawBytes.size == width * height && width > 0 && height > 0
}
