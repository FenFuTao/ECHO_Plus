package com.fenfutao.echo

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import com.fenfutao.echo.dataengine.ImageFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 2.0 硬件加速 Grayscale8 图像渲染器。
 *
 * 关键设计:
 * - GL_LUMINANCE: GPU 将单通道灰度映射为 RGB 等亮度
 * - RENDERMODE_WHEN_DIRTY: 仅在有新帧时渲染，避免空轮询
 * - @Volatile 无锁生产者-消费者同步
 * - 最新帧优先策略
 * - byte[] → ByteBuffer.wrap() 零拷贝上传
 *
 * 线程安全: setGrayPixels() 可从任意线程直接调用，
 * 内部通过 @Volatile + requestRender() 切换至 GL 线程。
 */
class GLImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private var renderer: ImageRenderer? = null
    private var glInitialized = false

    @Volatile
    private var pendingPixels: PixelUpload? = null

    var imageWidth: Int = 0; private set
    var imageHeight: Int = 0; private set

    /** 初始化 OpenGL 渲染器。必须在 GLSurfaceView 附加到窗口后调用。 */
    fun initGL() {
        if (glInitialized) return
        try {
            glInitialized = true
            renderer = ImageRenderer()
            setEGLContextClientVersion(2)
            setRenderer(renderer!!)
            renderMode = RENDERMODE_WHEN_DIRTY
        } catch (e: Exception) {
            Log.e("GLImageView", "initGL 异常", e)
            glInitialized = false
        }
    }

    /** 确保渲染器已初始化。由 setGrayPixels/setImageFrame 内部调用。 */
    private fun ensureGL() {
        if (!glInitialized) initGL()
    }

    /**
     * GPU 直通上传灰度图。
     *
     * @param data 原始灰度像素字节，data.size 必须等于 width × height
     * @param width 图像宽度（像素）
     * @param height 图像高度（像素）
     */
    fun setGrayPixels(data: ByteArray, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || data.size != width * height) {
            Log.w("GLImageView", "无效灰度帧: ${data.size}B, ${width}x${height}")
            return
        }
        try {
            ensureGL()
            imageWidth = width; imageHeight = height
            pendingPixels = PixelUpload(data, width, height, GLES20.GL_LUMINANCE)
            requestRender()
        } catch (e: Exception) {
            Log.e("GLImageView", "setGrayPixels 异常", e)
        }
    }

    /**
     * 从 ImageFrame 直接渲染（自动判断 Grayscale8 格式）。
     * 非 Grayscale8 格式的帧将被静默忽略。
     */
    fun setImageFrame(frame: ImageFrame) {
        if (!frame.isGrayscale8) {
            Log.w("GLImageView", "不支持的图像格式: ${frame.format}，仅支持 Grayscale8(24)")
            return
        }
        if (!frame.isValid) {
            Log.w("GLImageView", "无效图像帧: ${frame.dataSize}B, ${frame.width}x${frame.height}")
            return
        }
        try {
            setGrayPixels(frame.rawBytes, frame.width, frame.height)
        } catch (e: Exception) {
            Log.e("GLImageView", "setImageFrame 异常", e)
        }
    }

    /** 清除当前显示（设为全黑） */
    fun clear() {
        if (!glInitialized) return
        pendingPixels = null
        imageWidth = 0; imageHeight = 0
        requestRender()
    }

    // ----- 着色器 -----
    companion object {
        val VERTEX_SHADER = """
            attribute vec4 vPosition;
            attribute vec2 vTexCoord;
            varying vec2 texCoord;
            void main() {
                gl_Position = vPosition;
                texCoord = vTexCoord;
            }
        """.trimIndent()

        val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 texCoord;
            uniform sampler2D texture;
            void main() {
                float gray = texture2D(texture, texCoord).r;
                gl_FragColor = vec4(gray, gray, gray, 1.0);
            }
        """.trimIndent()
    }

    // ----- 内部渲染器 -----

    private data class PixelUpload(
        val data: ByteArray, val width: Int,
        val height: Int, val glFormat: Int
    )

    private inner class ImageRenderer : GLSurfaceView.Renderer {

        private var textureId = 0; private var program = 0

        private val vertexBuffer: FloatBuffer = ByteBuffer
            .allocateDirect(32).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(floatArrayOf(
                -1f, -1f,   1f, -1f,   -1f, 1f,   1f, 1f
            )).also { it.position(0) }

        private val texCoordBuffer: FloatBuffer = ByteBuffer
            .allocateDirect(32).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(floatArrayOf(
                0f, 1f,   1f, 1f,   0f, 0f,   1f, 0f
            )).also { it.position(0) }

        private fun setupTexParams() {
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        private fun createProgram(vs: String, fs: String): Int {
            val vsh = loadShader(GLES20.GL_VERTEX_SHADER, vs) ?: return 0
            val fsh = loadShader(GLES20.GL_FRAGMENT_SHADER, fs) ?: return 0
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vsh); GLES20.glAttachShader(prog, fsh)
            GLES20.glLinkProgram(prog)
            val st = IntArray(1); GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, st, 0)
            if (st[0] == 0) {
                Log.e("GLImageView", "着色器链接失败: ${GLES20.glGetProgramInfoLog(prog)}")
                GLES20.glDeleteProgram(prog); return 0
            }
            return prog
        }

        private fun loadShader(type: Int, src: String): Int? {
            val sh = GLES20.glCreateShader(type)
            GLES20.glShaderSource(sh, src); GLES20.glCompileShader(sh)
            val st = IntArray(1)
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, st, 0)
            if (st[0] == 0) {
                Log.e("GLImageView", "着色器编译失败: ${GLES20.glGetShaderInfoLog(sh)}")
                GLES20.glDeleteShader(sh); return null
            }
            return sh
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            if (program == 0) Log.e("GLImageView", "着色器编译或链接失败")
            val texIds = IntArray(1); GLES20.glGenTextures(1, texIds, 0)
            textureId = texIds[0]
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            GLES20.glViewport(0, 0, w, h)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // 消费最新帧（旧帧自动覆盖 = 最新帧优先）
            val up = pendingPixels
            if (up != null) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0,
                    up.glFormat, up.width, up.height, 0,
                    up.glFormat, GLES20.GL_UNSIGNED_BYTE,
                    ByteBuffer.wrap(up.data))
                setupTexParams()
                pendingPixels = null
            }
            if (textureId == 0 || program == 0) return

            GLES20.glUseProgram(program)
            val ph = GLES20.glGetAttribLocation(program, "vPosition")
            val th = GLES20.glGetAttribLocation(program, "vTexCoord")
            val ut = GLES20.glGetUniformLocation(program, "texture")

            GLES20.glEnableVertexAttribArray(ph)
            GLES20.glVertexAttribPointer(ph, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(th)
            GLES20.glVertexAttribPointer(th, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glUniform1i(ut, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(ph)
            GLES20.glDisableVertexAttribArray(th)
        }
    }
}
