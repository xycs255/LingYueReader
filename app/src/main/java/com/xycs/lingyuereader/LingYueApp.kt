package com.xycs.lingyuereader

import android.app.Application

/** 灵阅的 Application：一启动先装上闪退记录（[CrashLog]），以后出了意外好查。 */
class LingYueApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
