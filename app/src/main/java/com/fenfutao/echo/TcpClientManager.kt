package com.fenfutao.echo

import android.os.Handler
import android.os.Looper
import com.fenfutao.echo.util.AppLogger
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
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
    @Volatile
    private var isConnecting = false
    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var outputStream: OutputStream? = null
    private var remoteHost: String = ""
    private var remotePort: Int = 0
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
     * 连接到 TCP 服务端
     * @param host 服务器 IP
     * @param port 端口
     * @param handshake 握手数据，连接成功后发送（为空则不发送）
     */
    fun connect(host: String, port: Int, handshake: String = "", timeoutMs: Int = 10000): Boolean {
        if (isConnected) {
            AppLogger.w("TcpClientManager", "客户端已连接")
            return false
        }
        if (isConnecting) {
            AppLogger.w("TcpClientManager", "客户端正在连接中")
            return false
        }
        isConnecting = true
        remoteHost = host
        remotePort = port
        val effectiveTimeout = timeoutMs.coerceIn(1000, 60000)
        Thread {
            doConnect(host, port, handshake, effectiveTimeout)
        }.start()
        return true
    }

    @Synchronized
    private fun clearSocketIfMatch(sock: Socket?) {
        if (sock != null && socket === sock) {
            socket = null
        }
    }

    private fun doConnect(host: String, port: Int, handshake: String, timeoutMs: Int = 10000) {
        val sock = Socket()
        try {
            // 提前存入共享字段，以便 disconnect() 能关闭它来中断阻塞
            socket = sock
            sock.connect(InetSocketAddress(host, port), timeoutMs)

            // 连接成功后检查是否已被取消
            if (!isConnecting) {
                try { sock.close() } catch (_: Exception) {}
                clearSocketIfMatch(sock)
                return
            }

            outputStream = sock.getOutputStream()
            isConnected = true
            isConnecting = false

            AppLogger.i("TcpClientManager", "TCP客户端已连接到 $host:$port")

            // 若握手数据不为空则发送
            if (handshake.isNotEmpty()) {
                val handshakeBytes = handshake.toByteArray(Charset.forName("UTF-8"))
                try {
                    outputStream?.write(handshakeBytes)
                    outputStream?.flush()
                    AppLogger.i("TcpClientManager", "握手数据已发送: $handshake")
                } catch (e: IOException) {
                    AppLogger.e("TcpClientManager", "发送握手数据失败: ${e.message}")
                }
            }

            mainHandler.post {
                stateListener?.onStateChanged(true, "已连接到 $host:$port")
            }

            // 监听远程断开
            listenForRemoteDisconnect(sock)
        } catch (e: Exception) {
            // 关闭本地 socket
            try { sock.close() } catch (_: Exception) {}
            // 只有当前 socket 未被替换（新连接）才触发回调，防止旧线程污染新连接状态
            val socketReplaced: Boolean
            synchronized(this) {
                socketReplaced = (socket !== sock)
                if (!socketReplaced) {
                    socket = null
                    outputStream = null
                }
            }
            isConnecting = false
            isConnected = false
            AppLogger.e("TcpClientManager", "连接失败: ${e.message}")
            if (!socketReplaced) {
                mainHandler.post {
                    stateListener?.onStateChanged(false, "连接失败: ${e.message}")
                }
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
                // 提取所有完整行，剩余部分留在缓冲区
                extractAndEmitLines(sb) { line ->
                    mainHandler.post {
                        dataListener?.onDataReceived(line)
                    }
                }
            }
        } catch (_: IOException) {
            // Socket 在断开时可能抛出异常，忽略
        }
    }

    /**
     * 用户主动断开连接（也可用于取消正在进行的连接尝试）
     *
     * 取消连接（wasConnecting=true）时不发回调：handleConnectToggle 已同步更新 UI，
     * doConnect 的 catch 会处理或不处理（若 socket 已被新连接替换）。
     * 断开已建立连接（wasConnected=true）时发回调通知 UI。
     */
    fun disconnect() {
        val wasConnected = isConnected
        val wasConnecting = isConnecting
        isConnected = false
        isConnecting = false
        try {
            outputStream?.close()
            socket?.close()
        } catch (e: IOException) {
            AppLogger.e("TcpClientManager", "断开连接异常: ${e.message}")
        }
        outputStream = null
        socket = null
        if (wasConnected && !wasConnecting) {
            AppLogger.i("TcpClientManager", "TCP客户端已断开")
            mainHandler.post {
                stateListener?.onStateChanged(false, "用户断开连接")
            }
        } else if (wasConnecting) {
            AppLogger.i("TcpClientManager", "TCP客户端连接已取消")
        }
    }

    fun send(data: ByteArray) {
        Thread {
            try {
                // @Volatile 保证 outputStream 的写入对当前线程立即可见
                outputStream?.write(data)
                outputStream?.flush()
            } catch (e: IOException) {
                AppLogger.e("TcpClientManager", "发送数据失败: ${e.message}")
            }
        }.start()
    }

    companion object {
        /**
         * 从 StringBuilder 中提取所有完整的行，将剩余不完整数据留在缓冲区。
         * \n、\r\n、\n\r 均视为行终止符（\r\n 和 \n\r 算一个换行符）。
         */
        fun extractAndEmitLines(sb: StringBuilder, onLine: (String) -> Unit) {
            val str = sb.toString()
            if (str.isEmpty()) return

            var lastCut = 0
            var i = 0
            while (i < str.length) {
                val c = str[i]
                if (c == '\n' || c == '\r') {
                    val isDouble = (c == '\r' && i + 1 < str.length && str[i + 1] == '\n') ||
                                   (c == '\n' && i + 1 < str.length && str[i + 1] == '\r')
                    val lineEnd = i
                    i = if (isDouble) i + 2 else i + 1
                    onLine(str.substring(lastCut, lineEnd))
                    lastCut = i
                } else {
                    i++
                }
            }
            if (lastCut > 0) {
                sb.setLength(0)
                sb.append(str.substring(lastCut))
            }
        }
    }
}
