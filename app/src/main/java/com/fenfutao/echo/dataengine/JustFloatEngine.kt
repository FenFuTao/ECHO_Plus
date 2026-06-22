package com.fenfutao.echo.dataengine

import com.fenfutao.echo.util.AppLogger
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * JustFloat 协议引擎
 *
 * 协议特点：小端浮点数组形式的字节流协议，纯十六进制浮点传输，节省带宽。
 * 帧结束标志：帧尾 { 0x00, 0x00, 0x80, 0x7f }（小端 0x7F800000，即 float 的 +inf）。
 *
 * 采样数据格式：
 *   struct Frame {
 *       float ch_data[CH_COUNT];                   // 小端浮点数组
 *       unsigned char tail[4] {0x00, 0x00, 0x80, 0x7f};  // 帧尾
 *   };
 *
 * 图片前导帧格式（int 数组，共 7 个 int）：
 *   int preFrame[7] = {
 *       IMG_ID,       // 图片通道 ID
 *       IMG_SIZE,     // 图片数据大小
 *       IMG_WIDTH,    // 图片宽度
 *       IMG_HEIGHT,   // 图片高度
 *       IMG_FORMAT,   // 图片格式枚举值
 *       0x7F800000,   // 前导帧结尾标志
 *       0x7F800000    // 前导帧结尾标志
 *   };
 */
class JustFloatEngine : DataEngine {

    /** 内部字节缓冲区 */
    private val buffer = ByteArrayOutputStream()

    /** 最大缓冲区大小 */
    private val maxBufferSize = 10 * 1024 * 1024 // 10MB

    /** 将字符串编码为连续十六进制（供显示函数 hex→UTF-8 解码显示） */
    private fun String.encodeHex(): String =
        this.toByteArray(Charsets.UTF_8).joinToString("") { "%02X".format(it) }

    /**
     * 在图片接收模式下，消耗缓冲区中可能残留的图片数据
     * （前一次 scanForFrames 中前导帧帧尾后的剩余数据）
     */
    private fun consumeBufferAsImageData() {
        val buf = buffer.toByteArray()
        if (buf.isNotEmpty()) {
            val remaining = imageSize - imageBytesReceived
            val toConsume = minOf(buf.size, remaining)
            if (toConsume > 0) {
                imageDataBuffer.write(buf, 0, toConsume)
                imageBytesReceived += toConsume
            }
            if (toConsume >= buf.size) {
                buffer.reset()
            } else {
                buffer.reset()
                buffer.write(buf, toConsume, buf.size - toConsume)
            }
        }
    }

    /** 帧尾：{0x00, 0x00, 0x80, 0x7f} */
    private val frameTail = byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x7f)

    /** 是否过滤图像数据包 */
    private var imagePacketFiltered = false

    /** 图片数据接收状态 */
    private var imagePending = false
    private var imageId: Int = 0
    private var imageSize: Int = 0
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0
    private var imageFormat: Int = 0
    /** 已接收的图片数据字节数 */
    private var imageBytesReceived: Int = 0
    /** 图片原始数据累积缓冲区 */
    private val imageDataBuffer = ByteArrayOutputStream()
    /** 用于累积图片前导帧的 int 缓冲区 */
    private val pendingIntBuffer = mutableListOf<Int>()

    override fun feed(connectionId: String, data: ByteArray): List<DataEngineOutput> {
        if (data.isEmpty()) return emptyList()

        val results = mutableListOf<DataEngineOutput>()

        // 如果正在接收图片数据
        if (imagePending) {
            // 图像过滤开启时丢弃图片数据并重置
            if (imagePacketFiltered) {
                imagePending = false
                imageBytesReceived = 0
                imageDataBuffer.reset()
                buffer.write(data)
                results.addAll(scanForFrames(connectionId))
                return results
            }

            // ★ 先消耗缓冲区中可能残留的图片数据（前一次 feed 中帧尾后的剩余数据）
            consumeBufferAsImageData()

            results.addAll(handleImageData(connectionId, data))
            if (results.isNotEmpty()) return results
        }

        // 将新数据写入缓冲区
        buffer.write(data)

        // 检查缓冲区大小
        if (buffer.size() > maxBufferSize) {
            AppLogger.w("JustFloatEngine", "缓冲区超出上限（${buffer.size()}），执行重置")
            reset()
            return listOf(
                DataEngineOutput(text = "[缓冲区溢出]".encodeHex(), escapeControlChars = true)
            )
        }

        // 在缓冲区中搜索帧尾
        results.addAll(scanForFrames(connectionId))

        return results
    }

    /**
     * 处理图片数据接收
     * @return 非空列表表示图片接收完成或出错
     */
    private fun handleImageData(connectionId: String, data: ByteArray): List<DataEngineOutput> {
        val results = mutableListOf<DataEngineOutput>()
        val remaining = imageSize - imageBytesReceived
        val toConsume = minOf(data.size, remaining)

        imageBytesReceived += toConsume
        imageDataBuffer.write(data, 0, toConsume)

        if (imageBytesReceived >= imageSize) {
            // 图片数据接收完毕
            results.addAll(buildImagePacketOutput(connectionId))
            imagePending = false

            // 处理多余的数据（图片之后可能紧跟新的一帧）
            val extra = data.copyOfRange(toConsume, data.size)
            if (extra.isNotEmpty()) {
                buffer.write(extra)
                results.addAll(scanForFrames(connectionId))
            }
        }
        // 图片还在接收中，无需额外操作
        return results
    }

    /**
     * 在缓冲区中扫描帧尾，提取完整的帧
     */
    private fun scanForFrames(connectionId: String): List<DataEngineOutput> {
        val results = mutableListOf<DataEngineOutput>()
        val buf = buffer.toByteArray()
        if (buf.size < 4) return results // 至少需要 4 字节才能匹配帧尾

        var searchStart = 0

        while (true) {
            // 从 searchStart 位置开始搜索帧尾
            val tailPos = indexOfFrameTail(buf, searchStart)
            if (tailPos < 0) break // 没有找到完整的帧

            // 帧尾之前的数据是帧内容
            val frameData = buf.copyOfRange(0, tailPos)

            if (frameData.isNotEmpty()) {
                // 检查是否为图片前导帧
                val isImagePreamble = checkImagePreamble(frameData)
                if (isImagePreamble != null) {
                    // 图像过滤开启：跳过图片处理，将帧内容视为普通浮点数据
                    if (imagePacketFiltered) {
                        val floatOutput = parseFloatFrame(frameData, connectionId)
                        results.add(floatOutput)
                        val remaining = buf.copyOfRange(tailPos + 4, buf.size)
                        buffer.reset()
                        if (remaining.isNotEmpty()) buffer.write(remaining)
                        if (buffer.size() >= 4) results.addAll(scanForFrames(connectionId))
                        return results
                    }

                    // 是图片前导帧
                    imageId = isImagePreamble[0]
                    imageSize = isImagePreamble[1]
                    imageWidth = isImagePreamble[2]
                    imageHeight = isImagePreamble[3]
                    imageFormat = isImagePreamble[4]

                    AppLogger.i("JustFloatEngine",
                        "检测到图片前导帧: ID=$imageId, SIZE=$imageSize, WIDTH=$imageWidth, HEIGHT=$imageHeight, FORMAT=$imageFormat")

                    imagePending = true
                    imageBytesReceived = 0

                    results.add(
                        DataEngineOutput(
                            text = "[图片前导帧] ID=$imageId, SIZE=${imageSize}B, ${imageWidth}x$imageHeight, FORMAT=${getImageFormatName(imageFormat)}".encodeHex(),
                            type = OutputType.IMAGE_PACKET
                        )
                    )

                    // 帧尾之后的剩余数据
                    val remaining = buf.copyOfRange(tailPos + 4, buf.size)
                    buffer.reset()
                    if (remaining.isNotEmpty()) {
                        buffer.write(remaining)
                    }

                    // 如果图片大小为 0，立即完成
                    if (imageSize == 0) {
                        results.addAll(buildImagePacketOutput(connectionId))
                        imagePending = false
                        // 继续扫描剩余数据
                        searchStart = 0
                        continue
                    }

                    return results // 等待图片数据到来
                } else {
                    // 普通浮点数据帧
                    val floatOutput = parseFloatFrame(frameData, connectionId)
                    results.add(floatOutput)
                }
            }

            // 移除已处理的帧（帧内容 + 帧尾）
            val remaining = buf.copyOfRange(tailPos + 4, buf.size)
            buffer.reset()
            if (remaining.isNotEmpty()) {
                buffer.write(remaining)
            }

            // 继续扫描剩余数据（通过递归处理 buffer 中可能存在的后续帧）
            searchStart = 0
            // 注意：buffer 内容已更新（缓冲区已截断），需要递归扫描
            if (buffer.size() >= 4) {
                results.addAll(scanForFrames(connectionId))
            }
            break
        }

        return results
    }

    /**
     * 在字节数组中搜索帧尾 {0x00, 0x00, 0x80, 0x7f}
     * @return 匹配到的帧尾起始索引，未找到返回 -1
     */
    private fun indexOfFrameTail(data: ByteArray, startFrom: Int): Int {
        if (data.size - startFrom < 4) return -1

        for (i in startFrom..data.size - 4) {
            if (data[i].toInt() and 0xFF == 0x00 &&
                data[i + 1].toInt() and 0xFF == 0x00 &&
                data[i + 2].toInt() and 0xFF == 0x80 &&
                data[i + 3].toInt() and 0xFF == 0x7f) {
                return i
            }
        }
        return -1
    }

    /**
     * 检查帧数据是否为图片前导帧
     * 图片前导帧的特征：最后 8 字节是两个连续的 0x7F800000（int 值）
     * 即前导帧结构为 7 个 int，末尾两个是 0x7F800000
     *
     * @return 如果匹配，返回解析出的 5 个参数 [ID, SIZE, WIDTH, HEIGHT, FORMAT]；否则返回 null
     */
    private fun checkImagePreamble(frameData: ByteArray): IntArray? {
        // 前导帧应该是 7 个 int = 28 字节
        if (frameData.size < 28) return null

        // 检查尾部是否为两个 0x7F800000
        val bb = ByteBuffer.wrap(frameData, frameData.size - 8, 8).order(ByteOrder.LITTLE_ENDIAN)
        val secondLast = bb.getInt(0)
        val last = bb.getInt(4)

        if (secondLast == 0x7F800000 && last == 0x7F800000) {
            // 提取 5 个参数（前 5 个 int，共 20 字节）
            val paramBB = ByteBuffer.wrap(frameData, 0, 20).order(ByteOrder.LITTLE_ENDIAN)
            return intArrayOf(
                paramBB.getInt(0),   // IMG_ID
                paramBB.getInt(4),   // IMG_SIZE
                paramBB.getInt(8),   // IMG_WIDTH
                paramBB.getInt(12),  // IMG_HEIGHT
                paramBB.getInt(16)   // IMG_FORMAT
            )
        }

        return null
    }

    /**
     * 解析浮点数据帧
     */
    private fun parseFloatFrame(frameData: ByteArray, connectionId: String): DataEngineOutput {
        if (frameData.size < 4) {
            return DataEngineOutput(
                text = "[数据帧过短 ${frameData.size}B]".encodeHex(),
                escapeControlChars = true
            )
        }

        // 数据必须为 4 的倍数（float 数组）
        val floatCount = frameData.size / 4
        val bb = ByteBuffer.wrap(frameData).order(ByteOrder.LITTLE_ENDIAN)

        val values = StringBuilder()
        for (i in 0 until floatCount) {
            val floatVal = bb.getFloat(i * 4)
            if (i > 0) values.append(", ")
            values.append(String.format("%.6f", floatVal))
        }

        return DataEngineOutput(
            text = "${floatCount}通道: $values".encodeHex()
        )
    }

    /**
     * 构建图片数据包完成后的输出摘要
     */
    private fun buildImagePacketOutput(connectionId: String): List<DataEngineOutput> {
        val results = mutableListOf<DataEngineOutput>()
        val formatName = getImageFormatName(imageFormat)
        // 第 1 条：前导帧信息（去掉 0x7F800000 帧尾标记）
        results.add(DataEngineOutput(
            text = "ID=$imageId, SIZE=${imageSize}B, ${imageWidth}x$imageHeight, $formatName".encodeHex(),
            type = OutputType.IMAGE_PACKET
        ))
        // 第 2 条：图像原始 hex 数据（仅前 32 字节，避免巨量字符串卡死显示区）
        val raw = imageDataBuffer.toByteArray()
        imageDataBuffer.reset()
        val truncated = if (raw.size <= 32) {
            raw.joinToString("") { "%02X".format(it) }
        } else {
            raw.take(32).joinToString("") { "%02X".format(it) } + "(...${raw.size}B total)"
        }
        results.add(DataEngineOutput(
            text = truncated,
            type = OutputType.IMAGE_PACKET
        ))
        return results
    }

    /**
     * 获取图片格式名称
     */
    private fun getImageFormatName(format: Int): String = when (format) {
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

    override fun reset() {
        buffer.reset()
        imageDataBuffer.reset()
        imagePending = false
        imageId = 0
        imageSize = 0
        imageWidth = 0
        imageHeight = 0
        imageFormat = 0
        imageBytesReceived = 0
        pendingIntBuffer.clear()
        AppLogger.i("JustFloatEngine", "引擎状态已重置")
    }
    override fun setImagePacketFiltered(filtered: Boolean) {
        imagePacketFiltered = filtered
        if (filtered && imagePending) {
            imagePending = false
            imageBytesReceived = 0
            imageDataBuffer.reset()
        }
    }

    /** 当前是否正在接收图片数据 */
    fun isImagePending(): Boolean = imagePending

    /** 获取图片接收进度（已接收/总大小） */
    fun getImageProgress(): Pair<Int, Int> = Pair(imageBytesReceived, imageSize)

    override fun getEngineName(): String = "JustFloat"
}
