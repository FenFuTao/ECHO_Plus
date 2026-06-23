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

    // ── 图像前导帧锁定 ──
    /** 是否已完成图像前导帧锁定 */
    private var imagePreambleLocked: Boolean = false
    /** 锁定的前导帧参数（用于比较是否属于同一图像流） */
    private var lockedImageId: Int = 0
    private var lockedImageSize: Int = 0
    private var lockedImageWidth: Int = 0
    private var lockedImageHeight: Int = 0
    private var lockedImageFormat: Int = 0
    /** 静默丢弃模式：前导帧不匹配时，仍按图像格式消耗字节但不产生输出 */
    private var imageSkipSilently: Boolean = false

    override fun feed(connectionId: String, data: ByteArray): List<DataEngineOutput> {
        if (data.isEmpty()) return emptyList()

        val results = mutableListOf<DataEngineOutput>()

        // ★ 图像接收模式：完全接管字节流，不经过 processBuffer()
        if (imagePending) {
            handleImageStream(connectionId, data, results)
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

        // ★ 若 processBuffer 中检测到图片前导帧，缓冲区残留的图像原始字节由 handleImageStream 接管
        //    （图像前导帧与原始数据在同一 feed() 中到达的情况）
        if (imagePending && buffer.size() > 0) {
            handleImageStream(connectionId, ByteArray(0), results)
        }

        return results
    }

    /**
     * 图像数据流处理 — 完全接管字节计数，不调用 processBuffer()。
     *
     * 1. 消耗 buffer 中所有残留字节
     * 2. 从新 data 中取足所需字节（imageSize - imageBytesReceived）
     * 3. 超出图像大小的字节为溢出数据，仅在图像完整后交还 processBuffer 处理
     */
    private fun handleImageStream(connectionId: String, data: ByteArray, results: MutableList<DataEngineOutput>) {
        // 1. 消耗缓冲区中所有字节（全为图像数据）
        drainBufferForImage()

        // 2. 消耗新数据中所需的字节
        val needed = imageSize - imageBytesReceived
        if (needed > 0 && data.isNotEmpty()) {
            val fromData = minOf(data.size, needed)
            // ★ 静默丢弃模式或过滤模式：消耗字节但不累积
            if (!imagePacketFiltered && !imageSkipSilently) {
                imageDataBuffer.write(data, 0, fromData)
            }
            imageBytesReceived += fromData

            // 3. 数据中超出图像部分的溢出字节 → 仅在图像完整时才有效
            if (imageBytesReceived >= imageSize) {
                finishImageForFireWater(results)
                val overflow = data.copyOfRange(fromData, data.size)
                if (overflow.isNotEmpty()) {
                    buffer.write(overflow)
                    // ★ imagePending 已为 false，可安全调用 processBuffer
                    results.addAll(processBuffer(connectionId))
                }
            }
        } else if (imageBytesReceived >= imageSize) {
            // 仅来自 buffer 的数据已使图像完整
            finishImageForFireWater(results)
            // ★ 处理 drainBufferForImage 留在 buffer 中的溢出字节
            //   （当 buffer 中的字节数超过 imageSize 时，多余字节被 drainBufferForImage 写回 buffer）
            if (buffer.size() > 0) {
                results.addAll(processBuffer(connectionId))
            }
        }
        // 图像未完整：所有数据均已消耗为图像字节，等待下一次 feed
    }

    /** 消耗缓冲区中所有字节作为图像数据 */
    private fun drainBufferForImage() {
        val buf = buffer.toByteArray()
        if (buf.isEmpty()) return
        buffer.reset()

        val needed = imageSize - imageBytesReceived
        val fromBuf = minOf(buf.size, needed)
        if (fromBuf > 0) {
            // ★ 静默丢弃模式或过滤模式：消耗字节但不累积
            if (!imagePacketFiltered && !imageSkipSilently) {
                imageDataBuffer.write(buf, 0, fromBuf)
            }
            imageBytesReceived += fromBuf
        }
        // 缓冲区超出所需（理论上不应发生，安全处理）
        if (fromBuf < buf.size) {
            buffer.write(buf, fromBuf, buf.size - fromBuf)
        }
    }

    /** 完成图像接收，输出 IMAGE_PACKET，重置状态 */
    private fun finishImageForFireWater(results: MutableList<DataEngineOutput>) {
        // ★ 静默丢弃模式：不产生任何输出，仅重置状态
        if (!imagePacketFiltered && !imageSkipSilently) {
            results.addAll(buildImagePacketOutput(connectionId = ""))
        }
        imagePending = false
        imageSkipSilently = false
        imageBytesReceived = 0
        imageDataBuffer.reset()
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
                // ★ 必须在 break 前更新 lastCut，确保缓冲区仅保留图像原始字节
                lastCut = i
                if (imagePending) break
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

    // consumeBufferAsImageData 已弃用，由 drainBufferForImage 取代

    /**
     * 判断一行文本是否为有效的 FireWater 文本行。
     *
     * 排除连接中断/卡顿时产生的二进制垃圾数据：
     * 1. 包含 U+FFFD（UTF-8 替换字符）→ 无效 UTF-8 字节
     * 2. 非 ASCII 可打印字符占比过高 → 非文本
     */
    private fun isValidTextLine(line: String, lineBytes: ByteArray): Boolean {
        // 条件 1：包含替换字符 → 原始字节不是有效 UTF-8
        if (line.contains('\uFFFD')) return false

        // 条件 2：统计可打印 ASCII 字符比例
        //   可打印 ASCII: 0x20~0x7E，加上常用控制符 \t(0x09) \n(0x0A) \r(0x0D)
        val printableCount = lineBytes.count { b ->
            val u = b.toInt() and 0xFF
            (u >= 0x20 && u <= 0x7E) || u == 0x09 || u == 0x0A || u == 0x0D
        }
        // 可打印 ASCII 占比 < 60% → 判定为二进制数据
        if (lineBytes.isNotEmpty() && (printableCount.toFloat() / lineBytes.size) < 0.6f) return false

        return true
    }

    /**
     * 解析一行数据
     */
    private fun parseLine(connectionId: String, line: String, lineBytes: ByteArray): List<DataEngineOutput> {
        val results = mutableListOf<DataEngineOutput>()

        // 检测图片前导帧
        if (line.startsWith("image:")) {
            val parts = line.removePrefix("image:").split(",")
            if (parts.size >= 5) {
                try {
                    val newId = parts[0].trim().toInt()
                    val newSize = parts[1].trim().toInt()
                    val newWidth = parts[2].trim().toInt()
                    val newHeight = parts[3].trim().toInt()
                    val newFormat = parts[4].trim().toInt()

                    // ── 图像前导帧锁定逻辑 ──
                    if (!imagePreambleLocked) {
                        // 首次图像帧：锁定前导帧参数
                        imagePreambleLocked = true
                        lockedImageId = newId
                        lockedImageSize = newSize
                        lockedImageWidth = newWidth
                        lockedImageHeight = newHeight
                        lockedImageFormat = newFormat
                        imageSkipSilently = false
                    } else {
                        // 已锁定：检查新前导帧是否匹配
                        val matches = newId == lockedImageId &&
                            newSize == lockedImageSize &&
                            newWidth == lockedImageWidth &&
                            newHeight == lockedImageHeight &&
                            newFormat == lockedImageFormat
                        if (!matches) {
                            // 不匹配：进入静默丢弃模式，仍按图像格式消耗字节但不输出
                            imageSkipSilently = true
                            AppLogger.i("FireWaterEngine",
                                "图像前导帧不匹配（ID=$newId, 当前锁定ID=$lockedImageId），静默丢弃")
                        } else {
                            imageSkipSilently = false
                        }
                    }

                    imageId = newId
                    imageSize = newSize
                    imageWidth = newWidth
                    imageHeight = newHeight
                    imageFormat = newFormat

                    AppLogger.i("FireWaterEngine",
                        "检测到图片前导帧: ID=$imageId, SIZE=$imageSize, WIDTH=$imageWidth, HEIGHT=$imageHeight, FORMAT=$imageFormat${if (imageSkipSilently) " [静默丢弃]" else ""}")

                    // 标记开始接收图片数据
                    imagePending = true
                    imageBytesReceived = 0

                    // 图像过滤开启时不输出任何内容
                    if (!imagePacketFiltered) {
                        // 前导帧信息由 buildImagePacketOutput() 统一输出
                    }
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
            // ★ 过滤：跳过二进制垃圾数据
            if (!isValidTextLine(line, lineBytes)) return emptyList()

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
        // 第 2 条：图像原始 hex 数据（仅前 16 字节，标记为 isImageDataPayload 以避免进入显示区）
        val raw = imageDataBuffer.toByteArray()
        imageDataBuffer.reset()
        val truncated = if (raw.size <= 16) {
            raw.joinToString("") { "%02X".format(it) }
        } else {
            raw.take(16).joinToString("") { "%02X".format(it) } + "(...${raw.size}B total)"
        }
        results.add(DataEngineOutput(
            text = truncated,
            type = OutputType.IMAGE_PACKET,
            isImageDataPayload = true
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
        // ★ 重置图像前导帧锁定
        imagePreambleLocked = false
        lockedImageId = 0
        lockedImageSize = 0
        lockedImageWidth = 0
        lockedImageHeight = 0
        lockedImageFormat = 0
        imageSkipSilently = false
        AppLogger.i("FireWaterEngine", "引擎状态已重置")
    }
    override fun setImagePacketFiltered(filtered: Boolean) {
        imagePacketFiltered = filtered
        if (filtered && imagePending) {
            // 丢弃当前正在累积的图片数据
            imagePending = false
            imageSkipSilently = false
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
