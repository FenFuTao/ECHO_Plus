package com.example.echo

import android.app.Application
import com.example.echo.util.AppLogger
import com.example.echo.util.ConfigManager
import com.example.echo.util.ConnectionLogger

class SerialConsoleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        ConfigManager.init(this)
        ConnectionLogger.init(this)
        AppLogger.i("AppInit", "应用程序启动 - ECHO+ Serial Console")

        // 设置全局崩溃处理器
        setupCrashHandler()
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 记录崩溃日志
            ConnectionLogger.log("系统", "应用崩溃: ${throwable.message ?: "未知错误"}")
            // 延迟一小段时间等待日志写入完成
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {}
            // 交给默认处理器继续处理
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
