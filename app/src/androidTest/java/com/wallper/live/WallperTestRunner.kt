package com.wallper.live

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Starts the app with [TestWallperApplication]: same app, fake catalog. */
class WallperTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, TestWallperApplication::class.java.name, context)
}

class TestWallperApplication : WallperApplication() {
    override fun createContainer(): AppContainer = AppContainer(this, catalogSource = TestCatalog(this))
}
