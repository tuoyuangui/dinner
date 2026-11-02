package com.guolongli.dinner

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * 应用入口。Hilt 依赖图在此组装。
 */
@HiltAndroidApp
class DinnerApplication : Application()