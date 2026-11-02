package com.guolongli.dinner.data.remote

import com.guolongli.dinner.core.BaiduApi
import com.guolongli.dinner.core.error.DinnerException
import com.guolongli.dinner.data.remote.dto.BaiduErrorBody
import com.guolongli.dinner.core.log.Logger
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * 错误码映射。所有网盘错误的归因都收口在这里，业务层只消费 [DinnerException]。
 *
 * 成功判定：errno == 0；非 0 一律视为失败。
 * 鉴权类错误必须触发刷新或重新授权，不得用失效 token 重试。
 */
object BaiduErrorMapper {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 百度在 errno 之外还可能给出 OAuth 风格的 error 字段，这里一并归因。 */
    private val authErrorCodes = setOf(
        BaiduApi.ERRNO_TOKEN_INVALID,   // -6 access_token 无效或过期，网盘接口最常见
        110,                            // access token invalid
        111,                            // access token expired
        BaiduApi.ERRNO_NAS_TOKEN_INVALID, // 20017 nas 接口报「token 无效」（真实接口实测）
        BaiduApi.ERRNO_USER_NOT_EXIST,  // 31045 用户不存在，等价于登录态失效
    )
    private val authErrorNames = setOf(
        "invalid_token",
        "invalid_grant",
        "invalid_client",
        "unauthorized_client",
        "access_denied",
    )

    /**
     * 有明确含义、值得单独给用户一句话的错误码。
     *
     * 尤其 31061「文件已存在」：早先它被错放进鉴权集合里，结果是覆盖式上传失败时
     * 应用让用户去「重新授权」，把「同一个文件重传」误导成「授权坏了」。
     */
    private val friendlyMessages = mapOf(
        BaiduApi.ERRNO_FILE_EXIST to "云端已存在同名文件",
        BaiduApi.ERRNO_FILE_NOT_EXIST to "云端文件或目录不存在",
        31064 to "云盘未授权该应用的写入权限",
        31066 to "云端文件或目录不存在",
        31363 to "分片校验未通过，请重试",
        31364 to "分片数量超过上限",
        -7 to "文件或目录名非法，或无权访问该路径",
        -10 to "网盘容量不足",
    )

    fun mapErrno(errno: Int, errmsg: String): DinnerException = when {
        errno == BaiduApi.ERRNO_OK -> DinnerException.Local("errno 为 0 不应走错误映射")
        errno == BaiduApi.ERRNO_FREQUENCY_LIMIT -> DinnerException.FrequencyLimited()
        errno in authErrorCodes -> DinnerException.AuthRequired("errno=$errno")
        else -> DinnerException.Remote(
            errno = errno,
            errmsg = friendlyMessages[errno] ?: errmsg.ifBlank { "未知错误" },
        )
    }

    /** 解析非 2xx 响应体；解析不出来就退化为通用网络错误。 */
    fun mapHttpError(code: Int, body: String?): DinnerException {
        val parsed = body?.let { runCatching { json.decodeFromString<BaiduErrorBody>(it) }.getOrNull() }
        if (parsed != null) {
            val oauthError = parsed.error
            if (oauthError != null && parsed.errno == null) {
                if (oauthError in authErrorNames) {
                    return DinnerException.AuthRequired(oauthError)
                }
                return DinnerException.Remote(-1, parsed.errorDescription ?: oauthError)
            }
            parsed.errno?.let { errno ->
                return mapErrno(errno, parsed.errmsg.orEmpty())
            }
        }
        return when (code) {
            401, 403 -> DinnerException.AuthRequired("HTTP $code")
            429 -> DinnerException.FrequencyLimited()
            else -> DinnerException.Remote(-1, "HTTP $code")
        }
    }

    fun mapThrowable(throwable: Throwable): DinnerException = when (throwable) {
        is DinnerException -> throwable
        is IOException -> DinnerException.NetworkUnavailable(throwable)
        else -> {
            Logger.w("未归因异常：${throwable::class.java.simpleName}: ${throwable.message}")
            DinnerException.Local(throwable.message ?: "未知错误", throwable)
        }
    }

    /**
     * 校验业务响应。errno 非 0 时抛出对应异常，避免调用方遗漏检查。
     */
    fun requireSuccess(errno: Int, errmsg: String) {
        if (errno != BaiduApi.ERRNO_OK) throw mapErrno(errno, errmsg)
    }
}

/**
 * 把 subfile 上传接口的特殊响应折算成 token。
 * 分片上传成功时百度返回 errno 与 md5 两个字段，二者都要检查。
 */
fun requirePartSuccess(errno: Int, errmsg: String) {
    if (errno != BaiduApi.ERRNO_OK) {
        throw BaiduErrorMapper.mapErrno(errno, errmsg)
    }
}
