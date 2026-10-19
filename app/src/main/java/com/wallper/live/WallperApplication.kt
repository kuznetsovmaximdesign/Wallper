package com.wallper.live

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

open class WallperApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        container.network
        // Background work that decodes video pauses while the app is not on screen.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.motionThumbs.setForeground(true)
            override fun onStop(owner: LifecycleOwner) = container.motionThumbs.setForeground(false)
        })
    }

    /** UI tests override this to plug in a fake catalog. */
    protected open fun createContainer(): AppContainer = AppContainer(this)
}
