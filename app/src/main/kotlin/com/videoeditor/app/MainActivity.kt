package com.videoeditor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.videoeditor.app.ui.AppTheme
import com.videoeditor.app.ui.EditorScreen
import com.videoeditor.app.ui.ProjectsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as App
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        // -1 = Projects screen, otherwise the id of the project being edited
                        var openProject by rememberSaveable { mutableStateOf(-1L) }
                        if (openProject < 0) {
                            ProjectsScreen(app = app, onOpen = { openProject = it })
                        } else {
                            BackHandler { openProject = -1L }
                            key(openProject) {
                                EditorScreen(app = app, projectId = openProject, onBack = { openProject = -1L })
                            }
                        }
                    }
                }
            }
        }
    }
}
