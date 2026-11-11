package com.unspektrawesome

import android.app.Application

internal object NativeHost {
    private lateinit var host: Application

    fun attach(application: Application) {
        check(!::host.isInitialized)
        host = application
    }

    @JvmStatic fun application(): Application = host
}
