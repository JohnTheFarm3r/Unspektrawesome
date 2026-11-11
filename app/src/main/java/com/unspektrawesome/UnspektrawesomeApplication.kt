package com.unspektrawesome

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.unspektrawesome.diagnostics.InternalLogRecorder
import com.unspektrawesome.vulkan.ShaderWarmup

class UnspektrawesomeApplication : Application() {
    val shaderWarmup by lazy { ShaderWarmup(this) }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        NativeHost.attach(this)
    }

    override fun onCreate() {
        super.onCreate()
        InternalLogRecorder.start(this)
        System.loadLibrary("unspektrawesome_vulkan")
        shaderWarmup.start()
        com.unspektrawesome.language.AppLanguage.initialize(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private fun log(activity: Activity, state: String) =
                InternalLogRecorder.event("Lifecycle", "${activity.javaClass.simpleName} $state")
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = log(activity, "created restored=${savedInstanceState != null}")
            override fun onActivityStarted(activity: Activity) = log(activity, "started")
            override fun onActivityResumed(activity: Activity) = log(activity, "resumed")
            override fun onActivityPaused(activity: Activity) = log(activity, "paused")
            override fun onActivityStopped(activity: Activity) = log(activity, "stopped")
            override fun onActivityDestroyed(activity: Activity) = log(activity, "destroyed")
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    override fun onTrimMemory(level: Int) {
        InternalLogRecorder.warning("Memory", "trim level=$level")
        super.onTrimMemory(level)
    }
}
