package com.guolongli.dinner.live

import com.guolongli.dinner.data.auth.DeviceCodeClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 设备码授权（真跑百度 openapi）。
 *
 * 之所以用设备码而不是授权码：它不需要回调地址、也不用把 32 位的 code 拷来拷去，
 * 终端里打出一串 8 位短码就能完成，最适合「本机测试」这种没有浏览器的场景。
 *
 * 跑法：
 * ```
 * DINNER_DEVICE=1 ./gradlew :app:testDebugUnitTest --tests "*DeviceCodeAuthTest*"
 * ```
 * 测试会打印确认网址和短码，在任意设备的浏览器里输入并同意，测试自动继续。
 */
class DeviceCodeAuthTest {

    /** 只申请、不等待：验证 DeviceCodeClient 打真接口能拿到短码（不需要人在旁边） */
    @Test
    fun `申请设备码能拿到短码与确认地址`() = runBlocking {
        assumeTrue("缺少 AppKey / SecretKey", LiveEnv.hasKeys())

        val code = DeviceCodeClient().request(LiveEnv.appKey)
        println("[auth] user_code=${code.userCode} url=${code.verificationUrl}")
        assertTrue("短码不该是空的", code.userCode.isNotBlank())
        assertTrue("确认地址应该指向百度", code.verificationUrl.contains("baidu.com"))
        assertTrue("有效期应该是个正数", code.expiresInSeconds > 0)
    }

    @Test
    fun `设备码授权并写入 build live-token json`() = runBlocking {
        assumeTrue("需要 DINNER_DEVICE=1 才跑", System.getenv("DINNER_DEVICE") == "1")
        assumeTrue("缺少 AppKey / SecretKey", LiveEnv.hasKeys())

        val client = DeviceCodeClient()
        val code = client.request(LiveEnv.appKey)

        // 同时写一份到文件：跑在后台时不用等 stdout 缓冲，直接读文件就能拿到短码
        File("build/device-code.txt").apply { parentFile?.mkdirs() }.writeText(
            """
            url=${code.verificationUrl}
            code=${code.userCode}
            expires_in=${code.expiresInSeconds}
            requested_at=${System.currentTimeMillis()}
            """.trimIndent(),
        )

        println("=".repeat(64))
        println("  在浏览器打开： ${code.verificationUrl}")
        println("  输入短码：     ${code.userCode}")
        println("  有效期：       ${code.expiresInSeconds} 秒（每 ${code.intervalSeconds} 秒轮询一次）")
        println("=".repeat(64))

        var lastLogged = -1L
        val info = client.awaitToken(
            appKey = LiveEnv.appKey,
            appSecret = LiveEnv.appSecret,
            deviceCode = code,
            timeoutMs = code.expiresInSeconds * 1000L,
        ) { elapsed ->
            val seconds = elapsed / 1000
            if (seconds != lastLogged && seconds % 10 == 0L) {
                lastLogged = seconds
                println("[auth] 等待确认… ${seconds}s")
            }
        }

        LiveEnv.saveToken(info)
        File("build/device-code.txt").writeText("authorized=true\n")
        println("[auth] 授权成功")
        println("[auth] access_token = ${info.accessToken.take(16)}…")
        println("[auth] scope        = ${info.scope.take(80)}…")
        println("[auth] 已写入 build/live-token.json")

        assertTrue("token 太长或太短都不正常", info.accessToken.length in 20..200)

        // 立刻用这个 token 打一次真实接口，确认授权真的可用
        val quota = LiveEnv.repository().quota()
        println("[auth] 网盘容量 total=${quota.first} used=${quota.second} free=${quota.third}")
    }
}
