package com.fenfutao.echo

import android.os.Handler
import android.os.Looper
import com.fenfutao.echo.util.AppLogger
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

    fun interface OnConnectionListChangeListener {
        fun onListChanged(addresses: List<String>)
    }

    fun interface OnServerStateChangeListener {
        fun onStateChanged(running: Boolean, message: String)
    }

    fun interface OnDataReceivedListener {
        fun onDataReceived(data: String)
    }

    fun interface OnRawDataReceivedListener {
        fun onRawDataReceived(data: ByteArray, clientAddress: String)
    }

    @Volatile
    private var serverSocket: ServerSocket? = null
    private val clientSockets = CopyOnWriteArrayList<Socket>()
    @Volatile
    private var isRunning = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serverExecutor = Executors.newCachedThreadPool()
    private var countListener: OnConnectionCountChangeListener? = null
    private var listListener: OnConnectionListChangeListener? = null
    private var stateListener: OnServerStateChangeListener? = null
    private var dataListener: OnDataReceivedListener? = null
    private var rawDataListener: OnRawDataReceivedListener? = null

    /** 服务端握手数据，新客户端连接后若收到匹配此字符串的数据则过滤掉 */
    @Volatile
    var handshake: String = ""

    fun setOnConnectionCountChangeListener(listener: OnConnectionCountChangeListener) {
        this.countListener = listener
    }

    fun setOnConnectionListChangeListener(listener: OnConnectionListChangeListener) {
        this.listListener = listener
    }

    fun setOnServerStateChangeListener(listener: OnServerStateChangeListener) {
        this.stateListener = listener
    }

    fun setOnDataReceivedListener(listener: OnDataReceivedListener) {
        this.dataListener = listener
    }

    fun setOnRawDataReceivedListener(listener: OnRawDataReceivedListener) {
        this.rawDataListener = listener
    }

    /** 获取当前所有已连接客户端的 IP 地址列表（去重，按连接顺序） */
    fun getClientAddresses(): List<String> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (socket in clientSockets) {
            val addr = try {
                socket.inetAddress?.hostAddress ?: "未知"
            } catch (_: Exception) { null } ?: continue
            if (addr !in seen) {
                seen.add(addr)
                result.add(addr)
            }
        }
        return result
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
        updateConnectionList()
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
        updateConnectionList()
        AppLogger.i("TcpServerManager", "所有客户端已断开")
    }

    private fun acceptClients() {
        while (isRunning) {
            try {
                val client = serverSocket?.accept() ?: break
                val newIp = try { client.inetAddress?.hostAddress } catch (_: Exception) { null }
                // ★ 同 IP 重连：替换旧 Socket（断开又重连时去重）
                if (newIp != null) {
                    val oldSocket = clientSockets.find { s ->
                        try { s.inetAddress?.hostAddress == newIp } catch (_: Exception) { false }
                    }
                    if (oldSocket != null) {
                        clientSockets.remove(oldSocket)
                        try { oldSocket.close() } catch (_: Exception) {}
                    }
                }
                clientSockets.add(client)
                updateConnectionCount()
                updateConnectionList()
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
            var handshakeSkipped = false
            val clientAddress = try {
                "${client.inetAddress?.hostAddress ?: "未知"}:${client.port}"
            } catch (_: Exception) { "未知" }
            while (isRunning && client.isConnected && !client.isClosed) {
                val len = input.read(buf)
                if (len < 0) break
                // ★ 原始字节回调（供协议引擎使用），在 IO 线程直接回调避免主线程拥塞
                val rawCopy = buf.copyOf(len)
                rawDataListener?.onRawDataReceived(rawCopy, clientAddress)
                val chunk = String(buf, 0, len, Charsets.UTF_8)

                // 如果累积缓冲恰好等于握手数据（无 \n），清空缓冲继续
                if (!handshakeSkipped && handshake.isNotEmpty() && sb.toString() == handshake) {
                    sb.setLength(0)
                }

                sb.append(chunk)
                // 提取所有完整行，逐行处理（含握手过滤）
                TcpClientManager.extractAndEmitLines(sb) { line ->
                    val shouldSkip = if (!handshakeSkipped && handshake.isNotEmpty()) {
                        if (line == handshake) {
                            handshakeSkipped = true
                            true
                        } else if (line.startsWith(handshake)) {
                            handshakeSkipped = true
                            val remainder = line.substring(handshake.length)
                            if (remainder.isNotEmpty()) {
                                mainHandler.post { dataListener?.onDataReceived(remainder) }
                            }
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                    if (!shouldSkip) {
                        handshakeSkipped = true
                        mainHandler.post { dataListener?.onDataReceived(line) }
                    }
                }
            }
        } catch (_: IOException) {
            // 客户端断开时忽略
        } finally {
            // 客户端断开后从列表中移除
            clientSockets.remove(client)
            try { client.close() } catch (_: Exception) {}
            mainHandler.post {
                updateConnectionCount()
                updateConnectionList()
            }
        }
    }

    private fun updateConnectionList() {
        mainHandler.post {
            listListener?.onListChanged(getClientAddresses())
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
                    val os = socket.getOutputStream()
                    os.write(data)
                    os.flush()
                } catch (_: IOException) {}
            }
        }
    }
}
