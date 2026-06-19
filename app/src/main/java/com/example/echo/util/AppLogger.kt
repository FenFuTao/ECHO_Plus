package com.example.echo.util

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 全局日志记录器
 * 日志文件保存在 external files dir /logs/ 下
 */
object AppLogger {

    private const val TAG = "ECHO_PLUS"
    private const val LOG_DIR = "logs"
    private const val MAX_LOG_SIZE = 2 * 1024 * 1024L // 2MB 轮转
    private const val MAX_LOG_FILES = 3

    private var logDir: File? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileDateFormatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    /** 初始化日志目录 */
    fun init(context: Context) {
        logDir = File(context.getExternalFilesDir(null), LOG_DIR)
        logDir?.let { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
            // 写入启动标记
            i("AppLogger", "=== 日志系统初始化 ===")
            i("AppLogger", "日志目录: ${dir.absolutePath}")
            i("AppLogger", "SDK: ${android.os.Build.VERSION.SDK_INT}, 设备: ${android.os.Build.MODEL}")
        }
    }

    /** 获取日志目录路径 */
    fun getLogDirPath(): String? = logDir?.absolutePath

    /** 获取最新的日志文件 */
    fun getLatestLogFile(): File? {
        return logDir?.listFiles()?.filter { it.name.endsWith(".log") }?.maxByOrNull { it.lastModified() }
    }

    fun d(tag: String, msg: String) = log('D', tag, msg)
    fun i(tag: String, msg: String) = log('I', tag, msg)
    fun w(tag: String, msg: String) = log('W', tag, msg)
    fun e(tag: String, msg: String) = log('E', tag, msg)
    fun e(tag: String, msg: String, tr: Throwable) = log('E', tag, "$msg\n${Log.getStackTraceString(tr)}")

    private fun log(level: Char, tag: String, msg: String) {
        // 同时输出到 logcat
        when (level) {
            'D' -> Log.d(tag, msg)
            'I' -> Log.i(tag, msg)
            'W' -> Log.w(tag, msg)
            'E' -> Log.e(tag, msg)
        }

        // 异步写入文件
        executor.execute {
            writeToFile(level, tag, msg)
        }
    }

    private fun writeToFile(level: Char, tag: String, msg: String) {
        val dir = logDir ?: return
        val timestamp = dateFormatter.format(Date())
        val logLine = "$timestamp [$level] $tag: $msg\n"

        try {
            val logFile = getOrCreateLogFile(dir)
            if (logFile.length() > MAX_LOG_SIZE) {
                rotateLogs(dir)
            }
            val writer = FileWriter(logFile, true)
            writer.write(logLine)
            writer.close()
        } catch (e: IOException) {
            Log.e(TAG, "写入日志文件失败: ${e.message}")
        }
    }

    private fun getOrCreateLogFile(dir: File): File {
        val current = File(dir, "current.log")
        if (!current.exists()) {
            current.createNewFile()
        }
        return current
    }

    private fun rotateLogs(dir: File) {
        try {
            val current = File(dir, "current.log")
            // 删除最旧的轮转文件
            for (i in MAX_LOG_FILES downTo 2) {
                val oldFile = File(dir, "session_${i}.log")
                val newFile = File(dir, "session_${i - 1}.log")
                if (oldFile.exists()) oldFile.delete()
                if (newFile.exists()) newFile.renameTo(oldFile)
            }
            // 重命名当前文件
            val rotated = File(dir, "session_1.log")
            current.renameTo(rotated)
            current.createNewFile()
        } catch (e: Exception) {
            Log.e(TAG, "日志轮转失败: ${e.message}")
        }
    }
}
