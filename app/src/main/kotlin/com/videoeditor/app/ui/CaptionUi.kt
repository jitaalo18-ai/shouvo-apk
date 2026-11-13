package com.videoeditor.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.videoeditor.app.captions.CaptionLanguage
import com.videoeditor.app.captions.CaptionRenderer
import com.videoeditor.app.captions.CaptionStyle
import com.videoeditor.core.Captions
import com.videoeditor.core.Compound
import com.videoeditor.core.MediaKind
import com.videoeditor.core.Track

private fun mb(bytes: Long) = "${(bytes + 500_000) / 1_000_000} MB"

/** Auto Captions: choose the language (and with it the model), download it once, create. */
@Composable
fun CaptionSheet(ctl: EditorController) {
    Dialog(onDismissRequest = { ctl.captionSheet = false }) {
        Surface(shape = RoundedCornerShape(16.dp), color = Panel, modifier = Modifier.testTag("captionSheet")) {
            Column(Modifier.padding(20.dp)) {
                Text("Auto captions", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Recognises the speech of your video and writes it as captions. Runs on your phone.",
                    fontSize = 12.sp, color = Color.Gray,
                )
                Spacer(Modifier.height(14.dp))
                Text("Language", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (lang in CaptionLanguage.entries) {
                        FilterChip(
                            selected = ctl.captionLanguage == lang,
                            onClick = { ctl.captionLanguage = lang },
                            label = { Text(lang.label) },
                            modifier = Modifier.testTag("lang_${lang.name}"),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                val model = ctl.captionLanguage.model
                val ready = ctl.modelReady(model)
                val download = ctl.modelDownload
                Surface(shape = RoundedCornerShape(10.dp), color = Bg) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(model.title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        when {
                            ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Downloaded · ${mb(model.approxBytes)}", fontSize = 12.sp, color = Color(0xFF7CD992), modifier = Modifier.weight(1f))
                                TextButton(onClick = { ctl.deleteModel(model) }) { Text("Delete") }
                            }
                            download != null -> {
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { (download.first.toFloat() / download.second.coerceAtLeast(1)).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${mb(download.first)} / ${mb(download.second)}", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { ctl.stopCaptionWork() }) { Text("Stop") }
                                }
                            }
                            else -> {
                                Text(
                                    "One-time download, ${mb(model.approxBytes)} (Wi-Fi recommended). After that it works offline.",
                                    fontSize = 12.sp, color = Color.Gray,
                                )
                                Spacer(Modifier.height(6.dp))
                                Button(onClick = { ctl.downloadModel(model) }, modifier = Modifier.testTag("modelDownload")) {
                                    Text("Download")
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Style", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                StyleChips(ctl.captionStyle) { ctl.captionStyle = it }
                if (ctl.hasAutoCaptions) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("The captions you have now will be replaced.", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.weight(1f))
                        TextButton(onClick = { ctl.removeAutoCaptions(); ctl.captionSheet = false }) { Text("Remove") }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { ctl.captionSheet = false }) { Text("Close") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { ctl.createCaptions() },
                        enabled = ready && download == null,
                        modifier = Modifier.testTag("captionsCreate"),
                    ) { Text("Create captions") }
                }
            }
        }
    }
}

@Composable
private fun StyleChips(current: String, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (style in CaptionStyle.entries) {
            FilterChip(
                selected = CaptionStyle.of(current) == style,
                onClick = { onPick(style.name) },
                label = { Text(style.label) },
                modifier = Modifier.testTag("style_${style.name}"),
            )
        }
    }
}

/** Edit the words of a caption / text. Empty text deletes it. */
@Composable
fun TextEditDialog(ctl: EditorController, id: String) {
    val clip = ctl.timeline.byId(id) ?: run {
        ctl.editingTextId = null
        return
    }
    var value by remember(id) { mutableStateOf(clip.text) }
    AlertDialog(
        onDismissRequest = { ctl.editingTextId = null },
        title = { Text(if (clip.name == Captions.AUTO_NAME) "Edit caption" else "Edit text") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth().testTag("textField"),
                minLines = 2,
            )
        },
        confirmButton = { TextButton(onClick = { ctl.setText(id, value) }, modifier = Modifier.testTag("textDone")) { Text("Done") } },
        dismissButton = { TextButton(onClick = { ctl.editingTextId = null }) { Text("Cancel") } },
    )
}

/** Look, size and height of the selected caption (all auto captions together) or text. */
@Composable
fun TextStyleSheet(ctl: EditorController) {
    val clip = ctl.timeline.selected?.takeIf { it.kind == MediaKind.TEXT } ?: run {
        ctl.textStyleSheet = false
        return
    }
    Dialog(onDismissRequest = { ctl.textStyleSheet = false; ctl.endGesture() }) {
        Surface(shape = RoundedCornerShape(16.dp), color = Panel, modifier = Modifier.testTag("textStyleSheet")) {
            Column(Modifier.padding(20.dp)) {
                Text(if (clip.name == Captions.AUTO_NAME) "Caption style (all captions)" else "Text style", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                StyleChips(clip.style) { ctl.restyleText(clip.id, style = it) }
                Spacer(Modifier.height(10.dp))
                Text("Size", fontSize = 13.sp)
                Slider(
                    value = clip.scale.coerceIn(0.025f, 0.14f),
                    onValueChange = { ctl.restyleText(clip.id, size = it, live = true) },
                    onValueChangeFinished = { ctl.endGesture() },
                    valueRange = 0.025f..0.14f,
                    modifier = Modifier.testTag("textSize"),
                )
                Text("Height on screen", fontSize = 13.sp)
                Slider(
                    value = clip.posY.coerceIn(0.08f, 0.92f),
                    onValueChange = { ctl.restyleText(clip.id, posY = it, live = true) },
                    onValueChangeFinished = { ctl.endGesture() },
                    valueRange = 0.08f..0.92f,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { ctl.textStyleSheet = false; ctl.endGesture() }, modifier = Modifier.testTag("textStyleDone")) { Text("Done") }
                }
            }
        }
    }
}

/**
 * Captions and texts in the preview, drawn with the same renderer as the export. Tap: select;
 * double tap: edit the words; hold: multi-select; drag (when selected): move - auto captions
 * move together.
 */
@Composable
fun TextLayer(ctl: EditorController, position: Long, canvasW: Dp, canvasH: Dp, canvasWPx: Float, canvasHPx: Float) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val total = ctl.timeline.durationMs
    for (clip in ctl.flat.track(Track.TEXT)) {
        if (clip.kind != MediaKind.TEXT || clip.text.isBlank()) continue
        if (position < clip.timelineStartMs || position >= clip.timelineEndMs || position >= total) continue
        key(clip.id) {
            val owner = Compound.ownerId(clip.id)
            val isPiece = owner != clip.id
            val bmp = remember(clip.text, clip.style, clip.scale, canvasWPx, canvasHPx) {
                CaptionRenderer.render(clip.text, clip.style, clip.scale, canvasWPx.toInt().coerceAtLeast(2), canvasHPx.toInt().coerceAtLeast(2))
            }
            val w = with(density) { bmp.width.toDp() }
            val h = with(density) { bmp.height.toDp() }
            val picked = ctl.multiPick?.contains(owner) == true
            val latest by rememberUpdatedState(clip)
            var m = Modifier
                .offset(x = canvasW * clip.posX - w / 2, y = canvasH * clip.posY - h / 2)
                .size(w, h)
                .testTag("caption_${clip.text.take(20)}")
                .pointerInput(owner) {
                    detectTapGestures(
                        onDoubleTap = { if (!isPiece) ctl.editingTextId = owner },
                        onLongPress = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            ctl.longPress(owner)
                        },
                    ) { ctl.select(owner) }
                }
            if (clip.selected && !isPiece) {
                m = m.pointerInput(owner, canvasWPx, canvasHPx) {
                    detectDragGestures(onDragEnd = { ctl.endGesture() }, onDragCancel = { ctl.endGesture() }) { change, drag ->
                        change.consume()
                        val c = ctl.timeline.byId(owner) ?: latest
                        ctl.moveText(owner, c.posX + drag.x / canvasWPx, c.posY + drag.y / canvasHPx)
                    }
                }
            }
            m = m.rotate(clip.rotation)
            if (clip.selected || picked) m = m.border(1.5.dp, Accent, RoundedCornerShape(4.dp))
            Image(bitmap = bmp.asImageBitmap(), contentDescription = clip.text, modifier = m)
        }
    }
}
