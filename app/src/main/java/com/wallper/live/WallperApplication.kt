package com.wallper.live

import android.app.Application

open class WallperApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        container.network
    }

    /** UI tests override this to plug in a fake catalog. */
    protected open fun createContainer(): AppContainer = AppContainer(this)
}
