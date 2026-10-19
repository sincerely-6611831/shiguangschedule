package com.xingheyuzhuan.shiguangschedule.widget.keepalive

import androidx.annotation.Keep
import android.os.Build
import com.xingheyuzhuan.shiguangschedule.BuildConfig
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Shizuku UserService：以 shell/root 身份执行固定范围的后台策略操作。 */
@Keep
class BackgroundPolicyService : IBackgroundPolicyService.Stub() {
    override fun destroy() = exitProcess(0)

    override fun readPolicy(userId: Int): String {
        require(userId >= 0)
        val whitelist = command("cmd", "deviceidle", "whitelist").lineSequence().any {
            it.split(',').getOrNull(1) == BuildConfig.APPLICATION_ID
        }
        return JSONObject().apply {
            put("whitelisted", whitelist)
            OPERATIONS.forEach { op ->
                val output = command("cmd", "appops", "get", "--user", userId.toString(), BuildConfig.APPLICATION_ID, op)
                val mode = Regex("^\\s*$op: (allow|ignore|deny|default|foreground)", RegexOption.MULTILINE)
                    .find(output)?.groupValues?.get(1)
                    ?: if (output.contains("No operations")) "default"
                    else error("无法读取 $op: $output")
                put(op, mode)
            }
        }.toString()
    }

    override fun applyPolicy(userId: Int, policy: String) {
        require(userId >= 0)
        val desired = JSONObject(policy)
        // 在执行任何命令前验证全部参数；包名始终固定为本应用。
        val whitelisted = desired.getBoolean("whitelisted")
        OPERATIONS.forEach { require(desired.getString(it) in MODES) }
        val current = JSONObject(readPolicy(userId))
        if (current.getBoolean("whitelisted") != whitelisted) {
            command("cmd", "deviceidle", "whitelist", (if (whitelisted) "+" else "-") + BuildConfig.APPLICATION_ID)
        }
        OPERATIONS.forEach { op ->
            command("cmd", "appops", "set", "--user", userId.toString(), BuildConfig.APPLICATION_ID, op, desired.getString(op))
        }
        val actual = JSONObject(readPolicy(userId))
        check(actual.getBoolean("whitelisted") == whitelisted &&
            OPERATIONS.all { actual.getString(it) == desired.getString(it) }) {
            "系统未完整应用后台策略，请检查系统限制后重试"
        }
    }

    private fun command(vararg args: String): String {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        try {
            check(process.waitFor(10, TimeUnit.SECONDS)) { "系统命令执行超时: ${args.take(3)}" }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            check(process.exitValue() == 0 && !output.startsWith("Error") && !output.contains("Exception")) {
                output.take(1000).ifEmpty { "系统拒绝执行后台策略" }
            }
            return output
        } finally {
            process.destroy()
        }
    }

    companion object {
        private val OPERATIONS = if (Build.VERSION.SDK_INT >= 28)
            listOf("RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND") else listOf("RUN_IN_BACKGROUND")
        private val MODES = setOf("allow", "ignore", "deny", "default", "foreground")
    }
}
