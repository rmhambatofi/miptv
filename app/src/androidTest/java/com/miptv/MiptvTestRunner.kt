package com.miptv

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * Runs the instrumented tests in a bare [Application]: every test builds its own repository, and without
 * [MiptvApplication] no guide work is scheduled and WorkManager never starts. Starting it enables its job service,
 * whose PACKAGE_CHANGED broadcast lands ten seconds later, mid-suite; the CI emulator went offline right after it.
 */
class MiptvTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
}
