package com.guolongli.dinner.domain.model

/**
 * 记录附带媒体的类型。
 *
 * 一条记录最多带一段媒体：照片或视频。持久化时存 name()，
 * 从网盘快照恢复时按字符串解析，未知取值一律回退为照片，保证旧数据可读。
 */
enum class MediaType {
    IMAGE,
    VIDEO,
    ;

    companion object {
        val DEFAULT: MediaType = IMAGE

        fun fromStorage(raw: String?): MediaType =
            entries.firstOrNull { it.name == raw } ?: DEFAULT

        /** 依据 MIME 判断媒体类型；视频以外一律按照片处理。 */
        fun fromMime(mimeType: String?): MediaType =
            if (mimeType?.startsWith("video/") == true) VIDEO else IMAGE
    }
}
