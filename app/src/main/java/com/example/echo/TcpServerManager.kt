package com.example.echo

import android.os.Handler
import android.os.Looper
import com.example.echo.util.AppLogger
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * TCP 服务端管理器
 * 负责启动/停止 ServerSocket，接受客户端连接，跟踪连接数。
 */
class TcpServerManager {

    fun interface OnConnectionCountChangeListener {
        fun onCountChanged(count: Int)
    }

    fun interface OnServerStateChangeListener {
        fun onStateChanged(running: Boolean, message: String)
    }

    fun interface OnDataReceivedListener {
        fun onDataReceived(data: String)
    }

    private var serverSocket: ServerSocket? = null
    private val clientSockets = CopyOnWriteArrayList<Socket>()
    @Volatile
    private var isRunning = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serverExecutor = Executors.newCachedThreadPool()
    private var countListener: OnConnectionCountChangeListener? = null
    private var stateListener: OnServerStateChangeListener? = null
    private var dataListener: OnDataReceivedListener? = null

    fun setOnConnectionCountChangeListener(listener: OnConnectionCountChangeListener) {
        this.countListener = listener
    }

    fun setOnServerStateChangeListener(listener: OnServerStateChangeListener) {
        this.stateListener = listener
    }

    fun setOnDataReceivedListener(listener: OnDataReceivedListener) {
        this.dataListener = listener
    }

    fun isRunning(): Boolean = isRunning

    fun start(port: Int): Boolean {
        if (isRunning) {
            AppLogger.w("TcpServerManager", "服务器已在运行")
            return false
        }
        return try {
            serverSocket = ServerSocket(port)
            isRunning = true
            AppLogger.i("TcpServerManager", "TCP服务端已启动，监听端口: $port")
            mainHandler.post {
                stateListener?.onStateChanged(true, "TCP服务端已启动，端口: $port")
            }
            serverExecutor.submit { acceptClients() }
            true
        } catch (e: IOException) {
            AppLogger.e("TcpServerManager", "启动服务器失败: ${e.message}")
            isRunning = false
            mainHandler.post {
                stateListener?.onStateChanged(false, "启动失败: ${e.message}")
            }
            false
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        try {
            for (socket in clientSockets) {
                try {
                    socket.close()
                } catch (_: IOException) {}
            }
            clientSockets.clear()
            serverSocket?.close()
        } catch (e: IOException) {
            AppLogger.e("TcpServerManager", "关闭服务器异常: ${e.message}")
        }
        serverSocket = null
        updateConnectionCount()
        AppLogger.i("TcpServerManager", "TCP服务端已停止")
        mainHandler.post {
            stateListener?.onStateChanged(false, "用户断开连接")
        }
    }

    fun disconnectAllClients() {
        for (socket in clientSockets) {
            try {
                socket.close()
            } catch (_: IOException) {}
        }
        clientSockets.clear()
        updateConnectionCount()
        AppLogger.i("TcpServerManager", "所有客户端已断开")
    }

    private fun acceptClients() {
        while (isRunning) {
            try {
                val client = serverSocket?.accept() ?: break
                clientSockets.add(client)
                updateConnectionCount()
                AppLogger.i("TcpServerManager",
                    "新客户端已连接: ${client.inetAddress.hostAddress}, 当前连接数: ${clientSockets.size}")
                // 为每个客户端启动数据读取线程
                serverExecutor.submit { readClientData(client) }
            } catch (e: IOException) {
                if (isRunning) {
                    AppLogger.e("TcpServerManager", "接受客户端连接失败: ${e.message}")
                }
            }
        }
    }

    private fun readClientData(client: Socket) {
        try {
            val input = client.getInputStream()
            val buf = ByteArray(1024)
            val sb = StringBuilder()
            while (isRunning && client.isConnected && !client.isClosed) {
                val len = input.read(buf)
                if (len < 0) break
                val chunk = String(buf, 0, len, Charsets.UTF_8)
                sb.append(chunk)
                val str = sb.toString()
                val idx = str.indexOf('\n')
                if (idx >= 0) {
                    val complete = str.substring(0, idx)
                    sb.setLength(0)
                    sb.append(str.substring(idx + 1))
                    mainHandler.post {
                        dataListener?.onDataReceived(complete)
                    }
                }
            }
        } catch (_: IOException) {
            // 客户端断开时忽略
        }
    }

    private fun updateConnectionCount() {
        mainHandler.post {
            countListener?.onCountChanged(clientSockets.size)
        }
    }

    fun getConnectionCount(): Int = clientSockets.size

    fun broadcast(data: ByteArray) {
        serverExecutor.submit {
            for (socket in clientSockets) {
                try {
                    socket.getOutputStream().write(data)
                    socket.getOutputStream().flush()
                } catch (_: IOException) {}
            }
        }
    }
}
