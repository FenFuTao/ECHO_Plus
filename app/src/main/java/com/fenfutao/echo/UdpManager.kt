package com.fenfutao.echo

import android.os.Handler
import android.os.Looper
import com.fenfutao.echo.util.AppLogger
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * UDP 管理器
 * 负责连接/断开 UDP 端点，发送和接收数据。
 *
 * UDP 是无连接协议，这里的"连接"指配置好远程地址并启动接收线程。
 */
class UdpManager {

    fun interface OnConnectionStateListener {
        fun onStateChanged(connected: Boolean, message: String)
    }

    fun interface OnDataReceivedListener {
        fun onDataReceived(data: String)
    }

    @Volatile
    private var isConnected = false
    @Volatile
    private var isConnecting = false
    @Volatile
    private var socket: DatagramSocket? = null
    private var remoteHost: String = ""
    private var remotePort: Int = 0
    private var localPort: Int = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var stateListener: OnConnectionStateListener? = null
    private var dataListener: OnDataReceivedListener? = null

    fun isConnecting(): Boolean = isConnecting

    fun setOnConnectionStateListener(listener: OnConnectionStateListener) {
        this.stateListener = listener
    }

    fun setOnDataReceivedListener(listener: OnDataReceivedListener) {
        this.dataListener = listener
    }

    fun isConnected(): Boolean = isConnected

    /**
     * 打开 UDP 端口并启动接收线程
     * @param remoteHost 远程 IP
     * @param remotePort 远程端口
     * @param localPort 本地监听端口
     */
    fun connect(remoteHost: String, remotePort: Int, localPort: Int): Boolean {
        if (isConnected) {
            AppLogger.w("UdpManager", "UDP 已连接")
            return false
        }
        if (isConnecting) {
            AppLogger.w("UdpManager", "UDP 正在连接中")
            return false
        }
        isConnecting = true
        this.remoteHost = remoteHost
        this.remotePort = remotePort
        this.localPort = localPort
        Thread {
            doConnect(remoteHost, remotePort, localPort)
        }.start()
        return true
    }

    @Synchronized
    private fun clearSocketIfMatch(sock: DatagramSocket?) {
        if (sock != null && socket === sock) {
            socket = null
        }
    }

    private fun doConnect(remoteHost: String, remotePort: Int, localPort: Int) {
        val sock = DatagramSocket(localPort)
        try {
            socket = sock
            sock.soTimeout = 3000 // 3秒超时，用于定期检查连接状态

            // 连接成功后检查是否已被取消
            if (!isConnecting) {
                try { sock.close() } catch (_: Exception) {}
                clearSocketIfMatch(sock)
                return
            }

            isConnected = true
            isConnecting = false

            AppLogger.i("UdpManager", "UDP 已启动，本地端口: $localPort，远程: $remoteHost:$remotePort")

            mainHandler.post {
                stateListener?.onStateChanged(true, "UDP 已启动 ($remoteHost:$remotePort)")
            }

            // 监听远程数据
            listenForData(sock)
        } catch (e: Exception) {
            try { sock.close() } catch (_: Exception) {}
            val socketReplaced: Boolean
            synchronized(this) {
                socketReplaced = (socket !== sock)
                if (!socketReplaced) {
                    socket = null
                }
            }
            isConnecting = false
            isConnected = false
            AppLogger.e("UdpManager", "UDP 启动失败: ${e.message}")
            if (!socketReplaced) {
                mainHandler.post {
                    stateListener?.onStateChanged(false, "UDP 启动失败: ${e.message}")
                }
            }
        }
    }

    /**
     * 监听 UDP 数据包
     */
    private fun listenForData(sock: DatagramSocket) {
        try {
            val buf = ByteArray(2048)
            val sb = StringBuilder()
            while (isConnected && !sock.isClosed) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    sock.receive(packet)
                    if (!isConnected) break

                    val len = packet.length
                    val chunk = String(buf, 0, len, Charsets.UTF_8)
                    sb.append(chunk)
                    // 提取所有完整行，剩余部分留在缓冲区
                    TcpClientManager.extractAndEmitLines(sb) { line ->
                        mainHandler.post {
                            dataListener?.onDataReceived(line)
                        }
                    }
                } catch (te: java.net.SocketTimeoutException) {
                    // 超时正常，继续循环检查连接状态
                    continue
                }
            }
        } catch (_: IOException) {
            // Socket 关闭时忽略
        }
    }

    /**
     * 断开 UDP 连接
     */
    fun disconnect() {
        val wasConnected = isConnected
        val wasConnecting = isConnecting
        isConnected = false
        isConnecting = false
        try {
            socket?.close()
        } catch (e: IOException) {
            AppLogger.e("UdpManager", "断开连接异常: ${e.message}")
        }
        socket = null
        if (wasConnected && !wasConnecting) {
            AppLogger.i("UdpManager", "UDP 已断开")
            mainHandler.post {
                stateListener?.onStateChanged(false, "用户断开连接")
            }
        } else if (wasConnecting) {
            AppLogger.i("UdpManager", "UDP 连接已取消")
        }
    }

    fun send(data: ByteArray) {
        Thread {
            try {
                val addr = InetAddress.getByName(remoteHost)
                val packet = DatagramPacket(data, data.size, addr, remotePort)
                socket?.send(packet)
            } catch (e: IOException) {
                AppLogger.e("UdpManager", "发送数据失败: ${e.message}")
            }
        }.start()
    }
}
