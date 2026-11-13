package com.videoeditor.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.app.media.MediaInfo
import com.videoeditor.app.media.MediaProbe
import java.io.File

/** Test media lives in the test APK's assets and is copied into the app before use. */
object Fixtures {
    val target: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun file(name: String): File {
        val dir = File(target.filesDir, "fixtures").apply { mkdirs() }
        val out = File(dir, name)
        if (!out.exists() || out.length() == 0L) {
            InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        return out
    }

    fun uri(name: String): Uri = Uri.fromFile(file(name))

    fun info(name: String): MediaInfo = MediaProbe.probe(target, uri(name)) ?: error("cannot probe $name")
}

/** Screenshots of the real screen, collected by the CI script after the run. */
object Shots {
    fun take(name: String) {
        val bmp: Bitmap? = try {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        } catch (e: Exception) {
            null
        }
        if (bmp == null) {
            Log.w("NockNockTest", "screenshot $name not available")
            return
        }
        val dir = File(Fixtures.target.filesDir, "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** Timing notes printed to logcat and kept in a file for the report. */
object Report {
    fun note(line: String) {
        Log.i("NockNockTest", line)
        val f = File(Fixtures.target.filesDir, "shots").apply { mkdirs() }.let { File(it, "report.txt") }
        f.appendText(line + "\n")
    }
}

/** Files the CI script pushed to /data/local/tmp/nn (read through the shell, which may read them). */
object Pushed {
    fun copyTo(name: String, target: java.io.File): Boolean {
        if (target.isFile && target.length() > 0) return true
        target.parentFile?.mkdirs()
        val ui = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val tmp = java.io.File(target.parentFile, target.name + ".copy")
        ui.executeShellCommand("cat /data/local/tmp/nn/$name").use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                tmp.outputStream().use { input.copyTo(it, 1 shl 20) }
            }
        }
        if (tmp.length() == 0L) {
            tmp.delete()
            return false
        }
        return tmp.renameTo(target)
    }

    fun file(name: String): java.io.File? {
        val f = java.io.File(Fixtures.target.filesDir, "ci/$name")
        return if (copyTo(name, f)) f else null
    }
}
