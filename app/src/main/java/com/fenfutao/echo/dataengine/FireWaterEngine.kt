package com.fenfutao.echo.dataengine

import com.fenfutao.echo.util.AppLogger
import java.io.ByteArrayOutputStream

/**
 * FireWater 协议引擎
 *
 * 协议特点：CSV 风格的字符串流，直观简洁，编程像 printf 一样简单。
 * 帧结束标志：换行符 \n（也支持 \r\n 或 \n\r）。
 *
 * 采样数据格式："<any>:ch0,ch1,ch2,...,chN\n"
 * 图片前导帧格式："image:IMG_ID,IMG_SIZE,IMG_WIDTH,IMG_HEIGHT,IMG_FORMAT\n"
 *
 * 注意：any 不可以为 "image"，该前缀用于解析图片数据。
 */
class FireWaterEngine : DataEngine {

    /** 内部字节缓冲区 */
    private val buffer = ByteArrayOutputStream()

    /** 最大缓冲区大小 */
    private val maxBufferSize = 1024 * 1024 // 1MB

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

    override fun feed(connectionId: String, data: ByteArray): List<DataEngineOutput> {
        if (data.isEmpty()) return emptyList()

        val results = mutableListOf<DataEngineOutput>()

        // 如果正在接收图片数据，直接追加到图片缓冲区
        if (imagePending) {
            // 图像过滤开启时丢弃图片数据并重置
            if (imagePacketFiltered) {
                imagePending = false
                imageBytesReceived = 0
                imageDataBuffer.reset()
                buffer.write(data)
                results.addAll(processBuffer(connectionId))
                return results
            }

            // ★ 先消耗缓冲区中可能残留的图片数据（前一次 feed 中前导帧后的剩余数据）
            consumeBufferAsImageData()

            val remaining = imageSize - imageBytesReceived
            val toConsume = minOf(data.size, remaining)

            imageBytesReceived += toConsume
            imageDataBuffer.write(data, 0, toConsume)

            // 如果图片数据接收完毕
            if (imageBytesReceived >= imageSize) {
                results.addAll(buildImagePacketOutput(connectionId))
                imagePending = false

                // 处理多余的数据（图片之后可能紧跟新数据）
                val extra = data.copyOfRange(toConsume, data.size)
                if (extra.isNotEmpty()) {
                    buffer.write(extra)
                    results.addAll(processBuffer(connectionId))
                }
            } else {
                // 图片数据接收中，还有剩余部分待接收
                if (toConsume < data.size) {
                    val extra = data.copyOfRange(toConsume, data.size)
                    buffer.write(extra)
                }
            }
            return results
        }

        // 非图片接收状态：将数据写入缓冲区
        buffer.write(data)

        // 检查缓冲区大小
        if (buffer.size() > maxBufferSize) {
            AppLogger.w("FireWaterEngine", "缓冲区超出上限（${buffer.size()}），执行重置")
            reset()
            return listOf(
                DataEngineOutput(text = "[缓冲区溢出]".encodeHex(), escapeControlChars = true)
            )
        }

        // 从缓冲区提取完整行并处理
        results.addAll(processBuffer(connectionId))

        return results
    }

    /**
     * 从缓冲区中提取完整的行进行处理
     * 无论结尾有几个 \r 和 \n，均视为一个换行符，避免出现空白行。
     */
    private fun processBuffer(connectionId: String): List<DataEngineOutput> {
        val results = mutableListOf<DataEngineOutput>()
        val buf = buffer.toByteArray()
        var lastCut = 0
        var i = 0

        while (i < buf.size) {
            val b = buf[i].toInt() and 0xFF

            // 检测行结束符：\r 或 \n
            if (b == 0x0A || b == 0x0D) {
                val lineEnd = i

                // ★ 跳过后续所有连续的 \r 和 \n，无论有多少个，均视为一个换行符
                while (i < buf.size) {
                    val c = buf[i].toInt() and 0xFF
                    if (c == 0x0A || c == 0x0D) i++ else break
                }

                // 提取这一行（不含换行符）
                val lineBytes = buf.copyOfRange(lastCut, lineEnd)
                if (lineBytes.isNotEmpty()) {
                    val line = String(lineBytes, Charsets.UTF_8)
                    val parsed = parseLine(connectionId, line, lineBytes)
                    results.addAll(parsed)
                }
                lastCut = i
            } else {
                i++
            }
        }

        // 保留未完成的行在缓冲区中
        if (lastCut > 0) {
            buffer.reset()
            if (lastCut < buf.size) {
                buffer.write(buf, lastCut, buf.size - lastCut)
            }
        }

        return results
    }

    /** 将字符串编码为连续十六进制（供显示函数 hex→UTF-8 解码显示） */
    private fun String.encodeHex(): String =
        this.toByteArray(Charsets.UTF_8).joinToString("") { "%02X".format(it) }

    /**
     * 在图片接收模式下，消耗缓冲区中可能残留的图片数据
     * （前一次 feed 中前导帧换行符之后的数据）
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

    /**
     * 解析一行数据
     */
    private fun parseLine(connectionId: String, line: String, lineBytes: ByteArray): List<DataEngineOutput> {
        val results = mutableListOf<DataEngineOutput>()

        // 检测图片前导帧
        if (line.startsWith("image:")) {
            // 图像过滤开启：将 image: 行视为普通 CSV 数据
            if (imagePacketFiltered) {
                results.add(DataEngineOutput(text = line.encodeHex()))
                return results
            }

            val parts = line.removePrefix("image:").split(",")
            if (parts.size >= 5) {
                try {
                    imageId = parts[0].trim().toInt()
                    imageSize = parts[1].trim().toInt()
                    imageWidth = parts[2].trim().toInt()
                    imageHeight = parts[3].trim().toInt()
                    imageFormat = parts[4].trim().toInt()

                    AppLogger.i("FireWaterEngine",
                        "检测到图片前导帧: ID=$imageId, SIZE=$imageSize, WIDTH=$imageWidth, HEIGHT=$imageHeight, FORMAT=$imageFormat")

                    // 标记开始接收图片数据
                    imagePending = true
                    imageBytesReceived = 0

                    results.add(
                        DataEngineOutput(
                            text = "[图片前导帧] ID=$imageId, SIZE=${imageSize}B, ${imageWidth}x$imageHeight, FORMAT=$imageFormat".encodeHex(),
                            type = OutputType.IMAGE_PACKET
                        )
                    )
                } catch (e: NumberFormatException) {
                    results.add(
                        DataEngineOutput(text = "[图片解析失败]".encodeHex())
                    )
                }
            } else {
                results.add(
                    DataEngineOutput(text = "[图片格式错误]".encodeHex())
                )
            }
        } else {
            // 普通 CSV 数据行 — 仅传数据内容
            results.add(
                DataEngineOutput(text = line.encodeHex())
            )
        }

        return results
    }

    /**
     * 构建图片数据包完成后的输出摘要
     */
    private fun buildImagePacketOutput(connectionId: String): List<DataEngineOutput> {
        val results = mutableListOf<DataEngineOutput>()
        val formatName = getImageFormatName(imageFormat)
        // 第 1 条：前导帧信息（去掉帧尾标记，纯内容）
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
        AppLogger.i("FireWaterEngine", "引擎状态已重置")
    }
    override fun setImagePacketFiltered(filtered: Boolean) {
        imagePacketFiltered = filtered
        if (filtered && imagePending) {
            // 丢弃当前正在累积的图片数据
            imagePending = false
            imageBytesReceived = 0
            imageDataBuffer.reset()
        }
    }

    /** 当前是否正在接收图片数据 */
    fun isImagePending(): Boolean = imagePending

    /** 获取图片接收进度（已接收/总大小） */
    fun getImageProgress(): Pair<Int, Int> = Pair(imageBytesReceived, imageSize)

    override fun getEngineName(): String = "FireWater"
}
