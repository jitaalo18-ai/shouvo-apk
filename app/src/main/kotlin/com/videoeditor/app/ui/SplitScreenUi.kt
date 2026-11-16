package com.videoeditor.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videoeditor.core.SplitScreen
import kotlin.math.abs

/**
 * Split screen (like CapCut on PC): a panel over the bottom of the editor with the layouts for
 * the pictures at the playhead. A tap arranges them at once - the preview above shows it - and
 * another layout can be tried right away; "Reset" gives the whole pictures back.
 */
@Composable
fun SplitScreenSheet(ctl: EditorController) {
    val clips = ctl.splitClips
    LaunchedEffect(clips.size < 2) { if (clips.size < 2) ctl.closeSplitScreen() }
    if (clips.size < 2) return
    androidx.activity.compose.BackHandler { ctl.closeSplitScreen() }
    val aspect = ctl.canvasAspect.coerceIn(0.3f, 3.4f)
    val layouts = SplitScreen.layouts(clips.size)
    // which layout the pictures are in now (if any)
    val current = layouts.firstOrNull { layout ->
        clips.zip(layout.cells).all { (c, cell) ->
            val box = SplitScreen.shownBox(c, ctl.canvasAspect)
            abs(box.x - cell.x) < 0.004f && abs(box.y - cell.y) < 0.004f && abs(box.w - cell.w) < 0.004f && abs(box.h - cell.h) < 0.004f
        }
    }
    Box(Modifier.fillMaxWidth().testTag("splitScreenRoot"), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(Panel)
                // taps on the panel never reach the timeline under it
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                .navigationBarsPadding()
                .padding(bottom = 10.dp)
                .testTag("splitScreenSheet"),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Split screen", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("${clips.size} clips at the playhead - each gets its own part of the screen", fontSize = 11.sp, color = Color.Gray)
                }
                TextButton(onClick = { ctl.resetSplitScreen() }, modifier = Modifier.testTag("splitReset")) { Text("Reset") }
                IconButton(onClick = { ctl.closeSplitScreen() }, modifier = Modifier.testTag("splitDone")) {
                    Icon(Icons.Filled.Check, contentDescription = "Done")
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (layout in layouts) {
                    val on = layout == current
                    Column(
                        Modifier.clip(RoundedCornerShape(10.dp)).clickable { ctl.applySplitScreen(layout) }
                            .padding(6.dp).testTag("splitLayout_${layout.id}"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val h = 76.dp
                        val w = (h * aspect).coerceIn(44.dp, 150.dp)
                        Box(
                            Modifier.size(w, h).clip(RoundedCornerShape(6.dp)).background(Color(0xFF14161B))
                                .border(if (on) 2.dp else 1.dp, if (on) Accent else Color(0xFF4A4E58), RoundedCornerShape(6.dp)),
                        ) {
                            Canvas(Modifier.size(w, h)) {
                                val gap = 2.dp.toPx()
                                for ((i, cell) in layout.cells.withIndex()) {
                                    drawRoundRect(
                                        color = PART_COLORS[i % PART_COLORS.size],
                                        topLeft = Offset(cell.x * size.width + gap, cell.y * size.height + gap),
                                        size = Size(cell.w * size.width - 2 * gap, cell.h * size.height - 2 * gap),
                                        cornerRadius = CornerRadius(3.dp.toPx()),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(layout.name, fontSize = 11.sp, color = if (on) Accent else Color.LightGray)
                    }
                }
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

private val PART_COLORS = listOf(Color(0xFF5B8DEF), Color(0xFFE2A93B), Color(0xFF4DB6AC), Color(0xFFE57373), Color(0xFF9575CD), Color(0xFF81C784))
