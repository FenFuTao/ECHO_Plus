package com.example.echo

import android.os.Handler
import android.os.Looper
import com.example.echo.util.AppLogger
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.util.concurrent.Executors

/**
 * TCP 客户端管理器
 * 负责连接/断开 TCP 服务端，发送握手数据。
 */
class TcpClientManager {

    fun interface OnConnectionStateListener {
        fun onStateChanged(connected: Boolean, message: String)
    }

    fun interface OnDataReceivedListener {
        fun onDataReceived(data: String)
    }

    @Volatile
    private var isConnected = false
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var remoteHost: String = ""
    private var remotePort: Int = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clientExecutor = Executors.newSingleThreadExecutor()
    private var stateListener: OnConnectionStateListener? = null
    private var dataListener: OnDataReceivedListener? = null

    fun setOnConnectionStateListener(listener: OnConnectionStateListener) {
        this.stateListener = listener
    }

    fun setOnDataReceivedListener(listener: OnDataReceivedListener) {
        this.dataListener = listener
    }

    fun isConnected(): Boolean = isConnected

    /**
     * 连接到 TCP 服务端
     * @param host 服务器 IP
     * @param port 端口
     * @param handshake 握手数据，连接成功后发送
     */
    fun connect(host: String, port: Int, handshake: String = "plot0"): Boolean {
        if (isConnected) {
            AppLogger.w("TcpClientManager", "客户端已连接")
            return false
        }
        remoteHost = host
        remotePort = port
        clientExecutor.submit {
            doConnect(host, port, handshake)
        }
        return true
    }

    private fun doConnect(host: String, port: Int, handshake: String) {
        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(host, port), 5000) // 5秒超时
            socket = sock
            outputStream = sock.getOutputStream()
            isConnected = true

            AppLogger.i("TcpClientManager", "TCP客户端已连接到 $host:$port")

            // 发送握手数据
            val handshakeBytes = handshake.toByteArray(Charset.forName("UTF-8"))
            try {
                outputStream?.write(handshakeBytes)
                outputStream?.flush()
                AppLogger.i("TcpClientManager", "握手数据已发送: $handshake")
            } catch (e: IOException) {
                AppLogger.e("TcpClientManager", "发送握手数据失败: ${e.message}")
            }

            mainHandler.post {
                stateListener?.onStateChanged(true, "已连接到 $host:$port")
            }

            // 监听远程断开
            listenForRemoteDisconnect(sock)
        } catch (e: Exception) {
            AppLogger.e("TcpClientManager", "连接失败: ${e.message}")
            isConnected = false
            mainHandler.post {
                stateListener?.onStateChanged(false, "连接失败: ${e.message}")
            }
        }
    }

    /**
     * 监听远程主机的数据与断开连接
     */
    private fun listenForRemoteDisconnect(sock: Socket) {
        try {
            val input = sock.getInputStream()
            val buf = ByteArray(1024)
            val sb = StringBuilder()
            while (isConnected && sock.isConnected && !sock.isClosed) {
                val len = input.read(buf)
                if (len < 0) {
                    // 远程关闭了连接
                    if (isConnected) {
                        isConnected = false
                        AppLogger.i("TcpClientManager", "远程主机 $remoteHost:$remotePort 断开连接")
                        mainHandler.post {
                            stateListener?.onStateChanged(false, "远程主机断开连接")
                        }
                    }
                    break
                }
                // UTF-8 解码收到的数据
                val chunk = String(buf, 0, len, Charsets.UTF_8)
                sb.append(chunk)
                // 检查是否有完整的行（含换行符）
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
            // Socket 在断开时可能抛出异常，忽略
        }
    }

    /**
     * 用户主动断开连接
     */
    fun disconnect() {
        if (!isConnected) return
        isConnected = false
        try {
            outputStream?.close()
            socket?.close()
        } catch (e: IOException) {
            AppLogger.e("TcpClientManager", "断开连接异常: ${e.message}")
        }
        outputStream = null
        socket = null
        AppLogger.i("TcpClientManager", "TCP客户端已断开")
        mainHandler.post {
            stateListener?.onStateChanged(false, "用户断开连接")
        }
    }

    fun send(data: ByteArray) {
        clientExecutor.submit {
            try {
                outputStream?.write(data)
                outputStream?.flush()
            } catch (e: IOException) {
                AppLogger.e("TcpClientManager", "发送数据失败: ${e.message}")
            }
        }
    }
}
