package com.oaurumo.voldia

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku

/**
 * 共享的 Shizuku 电源控制逻辑，供 MainActivity 与 FloatWindowService 共用。
 *
 * 关机/重启需要 shell（ADB）权限，通过 Shizuku 执行 `svc power shutdown` /
 * `svc power reboot`。Shizuku 13.x 将 `Shizuku.newProcess` 标记为 private，
 * 这里通过反射调用。
 */
object PowerController {

    private const val REQUEST_CODE = 1001

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingAction: (() -> Unit)? = null
    private var listenerRegistered = false

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE) {
                val action = pendingAction
                pendingAction = null
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    action?.invoke()
                }
            }
        }

    private fun ensureListener() {
        if (!listenerRegistered) {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            listenerRegistered = true
        }
    }

    /**
     * 检查 Shizuku 服务与授权：已授权则立即执行 [action]；
     * 未授权则发起授权请求，授权通过后自动执行 [action]。
     * @param onNotRunning Shizuku 服务未启动时回调（主线程）
     */
    fun ensureShizukuAndRun(
        context: Context,
        action: () -> Unit,
        onNotRunning: () -> Unit
    ) {
        val running = try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
        if (!running) {
            mainHandler.post(onNotRunning)
            return
        }
        ensureListener()
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingAction = action
            Shizuku.requestPermission(REQUEST_CODE)
        }
    }

    /**
     * 通过 Shizuku 在后台线程执行 shell 命令。
     * @param onError 失败时回调（主线程），参数为错误信息
     */
    fun execute(cmd: Array<String>, onError: (String) -> Unit) {
        Thread {
            try {
                val process = shizukuNewProcess(cmd)
                val code = process.waitFor()
                if (code != 0) {
                    val err = process.errorStream.bufferedReader().readText().trim()
                    mainHandler.post { onError(err.ifEmpty { "exit code $code" }) }
                }
            } catch (e: Throwable) {
                mainHandler.post { onError(e.message ?: e.javaClass.simpleName) }
            }
        }.start()
    }

    /** 反射调用 Shizuku.newProcess（13.x 中为 private）。 */
    private fun shizukuNewProcess(cmd: Array<String>): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, cmd, null, null) as Process
    }
}
