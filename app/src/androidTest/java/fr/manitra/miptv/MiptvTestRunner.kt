package fr.manitra.miptv

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * Runs the instrumented tests in French, in a bare [Application]: every test builds its own repository, and without
 * [MiptvApplication] no guide work is scheduled and WorkManager never starts. Starting it enables its job service,
 * whose PACKAGE_CHANGED broadcast lands ten seconds later, mid-suite; the CI emulator went offline right after it.
 */
class MiptvTestRunner : AndroidJUnitRunner() {
    /** The tests check the French texts, the reference language, whatever the language of the emulator. */
    override fun onCreate(arguments: Bundle?) {
        I18n.language = Language.FRENCH
        super.onCreate(arguments)
    }

    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
}
