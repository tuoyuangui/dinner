package com.guolongli.dinner.core.error

/**
 * 统一异常类型。UI 只消费这个层级，不直接暴露底层异常文本。
 */
sealed class DinnerException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** 网络不可用，可稍后重试。 */
    class NetworkUnavailable(cause: Throwable? = null) :
        DinnerException("网络不可用，请检查连接后重试", cause)

    /** 鉴权失效（token 过期或被撤销）。必须触发刷新或重新授权，不得用失效 token 重试。 */
    class AuthRequired(reason: String) :
        DinnerException("授权已失效，请重新授权 ($reason)")

    /** 频控 20012。必须指数退避重试。 */
    class FrequencyLimited :
        DinnerException("请求过于频繁，稍后再试")

    /** 网盘返回的业务错误。 */
    class Remote(val errno: Int, val errmsg: String) :
        DinnerException("网盘返回错误 $errno: $errmsg")

    /** 本地错误（文件读写、解析等）。 */
    class Local(message: String, cause: Throwable? = null) : DinnerException(message, cause)

    /** 业务校验失败，用于表单提示。 */
    class Validation(message: String) : DinnerException(message)
}
