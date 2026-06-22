package com.fenfutao.echo.util

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 协议连接日志记录器
 * 记录各协议连接/断开的日志，格式：
 * [yyyy/mm/dd hh:mm:ss.xx] -数据接口- -消息-
 * 文件保存在 context.getExternalFilesDir(null)/connections.log
 */
object ConnectionLogger {

    private const val FILE_NAME = "connections.log"
    private var logFile: File? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormatter = SimpleDateFormat("yyyy/MM/dd HH:mm:ss.SS", Locale.getDefault())

    fun init(context: Context) {
        logFile = File(context.getExternalFilesDir(null), FILE_NAME)
        AppLogger.i("ConnectionLogger", "连接日志文件: ${logFile?.absolutePath}")
    }

    /**
     * 记录连接日志
     * @param protocol 数据接口名称，如 "串口", "UDP", "TCP客户端", "TCP服务端", "Demo"
     * @param message  连接/断开信息，如 "已连接", "已断开", "连接失败: 端口被占用"
     */
    fun log(protocol: String, message: String) {
        executor.execute {
            writeToFile(protocol, message)
        }
    }

    private fun writeToFile(protocol: String, message: String) {
        val file = logFile ?: return
        val timestamp = dateFormatter.format(Date())
        val logLine = "[$timestamp] -$protocol- -$message-\n"

        try {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
            val writer = FileWriter(file, true)
            writer.write(logLine)
            writer.close()
        } catch (e: IOException) {
            AppLogger.e("ConnectionLogger", "写入连接日志失败: ${e.message}")
        }
    }
}
