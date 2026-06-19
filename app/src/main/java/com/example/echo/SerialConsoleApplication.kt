package com.example.echo

import android.app.Application
import com.example.echo.util.AppLogger
import com.example.echo.util.ConfigManager

class SerialConsoleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        ConfigManager.init(this)
        AppLogger.i("AppInit", "应用程序启动 - ECHO+ Serial Console")
    }
}
