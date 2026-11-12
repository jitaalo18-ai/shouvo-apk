package com.videoeditor.app

import android.app.Application
import com.videoeditor.app.data.AppDatabase
import com.videoeditor.app.data.LibraryRepository
import com.videoeditor.app.data.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    /** Outlives screens so saves always finish. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var projects: ProjectRepository
        private set
    lateinit var library: LibraryRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.create(this)
        projects = ProjectRepository(db)
        library = LibraryRepository(this, db)
        appScope.launch { library.cleanUpRemoved() }
    }
}
