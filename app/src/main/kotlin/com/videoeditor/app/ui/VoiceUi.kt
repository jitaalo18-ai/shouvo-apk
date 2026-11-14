package com.videoeditor.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videoeditor.core.AudioFx
import com.videoeditor.core.VoiceEdits
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The Voice enhance panel (like Edits): Auto | Volume | Enhance | Fade tabs over the bottom of
 * the editor, the preview stays visible above. Works on the selected clip; closes when the
 * selection goes away.
 */
@Composable
fun VoicePanel(ctl: EditorController, modifier: Modifier = Modifier) {
    val clip = ctl.voiceClip
    LaunchedEffect(clip == null) { if (clip == null) ctl.closeVoice() }
    if (clip == null) return
    BackHandler { ctl.closeVoice() }
    val playing by ctl.player.isPlaying.collectAsState()
    val fx = clip.audio
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(Panel)
            // taps on the panel never reach the timeline under it
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .navigationBarsPadding()
            .padding(bottom = 8.dp)
            .testTag("voicePanel"),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { ctl.player.toggle() }, modifier = Modifier.testTag("voicePlay")) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause" else "Play")
            }
            Text("Voice enhance", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = { ctl.closeVoice() }, modifier = Modifier.testTag("voiceDone")) {
                Icon(Icons.Filled.Check, contentDescription = "Done")
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (tab in EditorController.VoiceTab.entries) {
                val on = ctl.voiceTab == tab
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { ctl.voiceTab = tab }
                        .padding(vertical = 8.dp).testTag("voiceTab_${tab.name}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(tab.label, fontSize = 14.sp, color = if (on) Color.White else Color.Gray, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.width(28.dp).height(2.dp).background(if (on) Color.White else Color.Transparent))
                }
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(min = 170.dp).padding(horizontal = 16.dp, vertical = 10.dp)) {
            when (ctl.voiceTab) {
                EditorController.VoiceTab.AUTO -> AutoTab(ctl)
                EditorController.VoiceTab.VOLUME -> {
                    LevelSlider(
                        label = "Volume", tag = "voiceVolume", value = fx.volume, max = AudioFx.MAX_VOLUME,
                        text = "${(fx.volume * 100).roundToInt()}%",
                        onChange = { ctl.setVoiceVolume(snap(it, 0.01f)) }, onDone = { ctl.voiceDone() },
                    )
                    Hint("100% is the original sound. Louder than 200% is not possible; loud parts are kept from crackling.")
                }
                EditorController.VoiceTab.ENHANCE -> {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { ctl.toggleVoiceApplyAll() }.testTag("voiceApplyAll"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = ctl.voiceApplyAll, onClick = { ctl.toggleVoiceApplyAll() },
                            colors = RadioButtonDefaults.colors(selectedColor = Color.White),
                        )
                        Text("Apply enhancement to all clips", fontSize = 14.sp)
                    }
                    LevelSlider(
                        label = "Reduce noise", tag = "voiceDenoise", value = fx.denoise, max = 1f,
                        text = if (fx.denoise > 0f && ctl.isProcessing(clip)) "Processing…" else "${(fx.denoise * 100).roundToInt()}%",
                        onChange = { ctl.setVoiceDenoise(snap(it, 0.01f)) }, onDone = { ctl.voiceDone() },
                    )
                    LevelSlider(
                        label = "Enhance voice", tag = "voiceEnhance", value = fx.enhance, max = 1f,
                        text = "${(fx.enhance * 100).roundToInt()}%",
                        onChange = { ctl.setVoiceEnhance(snap(it, 0.01f)) }, onDone = { ctl.voiceDone() },
                    )
                }
                EditorController.VoiceTab.FADE -> {
                    val max = VoiceEdits.maxFadeMs(clip).toFloat().coerceAtLeast(1f)
                    LevelSlider(
                        label = "Fade in", tag = "voiceFadeIn", value = fx.fadeInMs.toFloat(), max = max,
                        text = seconds(fx.fadeInMs),
                        onChange = { ctl.setVoiceFadeIn((it / 100f).roundToLong() * 100) }, onDone = { ctl.voiceDone() },
                    )
                    LevelSlider(
                        label = "Fade out", tag = "voiceFadeOut", value = fx.fadeOutMs.toFloat(), max = max,
                        text = seconds(fx.fadeOutMs),
                        onChange = { ctl.setVoiceFadeOut((it / 100f).roundToLong() * 100) }, onDone = { ctl.voiceDone() },
                    )
                }
            }
        }
        if (!ctl.processingDialogShown) ProcessingLine(ctl)
    }
}

@Composable
private fun AutoTab(ctl: EditorController) {
    val noisy = ctl.noisyClipCount
    Text("Finds sound problems in your clips and fixes them in one tap.", fontSize = 12.sp, color = Color.Gray)
    Spacer(Modifier.height(12.dp))
    if (noisy == null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("voiceAnalyzing")) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Checking the sound…", fontSize = 13.sp, color = Color.Gray)
        }
    } else {
        val label = when {
            noisy == 0 -> "No background noise found"
            ctl.noiseRemoved -> "Noise removed on $noisy ${if (noisy == 1) "clip" else "clips"}"
            else -> "Remove noise on $noisy ${if (noisy == 1) "clip" else "clips"}"
        }
        FilterChip(
            selected = ctl.noiseRemoved,
            enabled = noisy > 0,
            onClick = { ctl.toggleRemoveNoise() },
            label = { Text(label) },
            leadingIcon = { Icon(Icons.Filled.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp)) },
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.35f)),
            modifier = Modifier.testTag("voiceRemoveNoise"),
        )
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { ctl.autoAdjustAudio() }, modifier = Modifier.fillMaxWidth().testTag("voiceAutoAdjust")) {
        Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Auto adjust audio")
    }
    Hint("Auto adjust brings the voice of every clip to the same loudness.")
}

@Composable
private fun LevelSlider(label: String, tag: String, value: Float, max: Float, text: String, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(text, fontSize = 13.sp, color = Color.Gray, modifier = Modifier.testTag("${tag}Value"))
    }
    Slider(
        value = value.coerceIn(0f, max),
        onValueChange = onChange,
        onValueChangeFinished = onDone,
        valueRange = 0f..max,
        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0xFF44474F)),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
}

private fun snap(v: Float, step: Float) = (v / step).roundToInt() * step

private fun seconds(ms: Long) = if (ms == 0L) "Off" else String.format(Locale.US, "%.1fs", ms / 1000f)

/**
 * The one-time processing popup (like Edits): spinner, "Processing", it only happens once, keep
 * editing; Minimize (or tapping outside / Back) hides it, the work goes on.
 */
@Composable
fun ProcessingDialog(ctl: EditorController) {
    val progress = ctl.processingProgress ?: return
    Dialog(onDismissRequest = { ctl.minimizeProcessing() }) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF262628),
            modifier = Modifier.width(300.dp).testTag("voiceProcessing"),
        ) {
            Column(Modifier.padding(top = 28.dp, start = 24.dp, end = 24.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(44.dp), color = Color.White, strokeWidth = 3.5.dp, trackColor = Color(0xFF3A3A3D))
                Spacer(Modifier.height(18.dp))
                Text("Processing", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Spacer(Modifier.height(10.dp))
                Text(
                    "This only needs to happen once. You can keep editing while audio processes.",
                    fontSize = 15.sp, color = Color.White, textAlign = TextAlign.Center, lineHeight = 21.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text("${(progress * 100).roundToInt()}%", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.testTag("voiceProcessingPercent"))
                Spacer(Modifier.height(14.dp))
                TextButton(onClick = { ctl.minimizeProcessing() }, modifier = Modifier.fillMaxWidth().testTag("voiceMinimize")) {
                    Text("Minimize", fontSize = 16.sp, color = Color.White)
                }
            }
        }
    }
}

/**
 * Twinkling dots and little four-point stars over the video while its sound is being processed
 * (like Edits). Drawn over the preview only, never exported, never catches a touch.
 */
@Composable
fun VoiceSparkles(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "sparkles")
    val t by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "twinkle",
    )
    Box(modifier.testTag("voiceSparkles")) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(Color.Black.copy(alpha = 0.28f))
            val step = 22.dp.toPx()
            val cols = (size.width / step).toInt().coerceAtLeast(1)
            val rows = (size.height / step).toInt().coerceAtLeast(1)
            val padX = (size.width - (cols - 1) * step) / 2f
            val padY = (size.height - (rows - 1) * step) / 2f
            val dot = 1.6.dp.toPx()
            val star = 4.5.dp.toPx()
            for (r in 0 until rows) for (c in 0 until cols) {
                val h = (r * 73856093) xor (c * 19349663)
                val phase = ((h ushr 3) and 1023) / 1024f
                val wave = 0.5f + 0.5f * kotlin.math.sin(2f * Math.PI.toFloat() * (t + phase))
                val alpha = 0.18f + 0.72f * wave
                val center = Offset(padX + c * step, padY + r * step)
                if ((h ushr 11) % 3 == 0) {
                    // four-point star: two thin diamonds
                    val s = star * (0.7f + 0.3f * wave)
                    val k = s * 0.22f
                    val path = Path().apply {
                        moveTo(center.x, center.y - s)
                        lineTo(center.x + k, center.y - k)
                        lineTo(center.x + s, center.y)
                        lineTo(center.x + k, center.y + k)
                        lineTo(center.x, center.y + s)
                        lineTo(center.x - k, center.y + k)
                        lineTo(center.x - s, center.y)
                        lineTo(center.x - k, center.y - k)
                        close()
                    }
                    drawPath(path, Color.White.copy(alpha = alpha))
                } else {
                    drawCircle(Color.White.copy(alpha = alpha * 0.8f), dot * (0.8f + 0.5f * wave), center)
                }
            }
        }
        Text(
            "Enhancing audio",
            fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
        )
    }
}

/** A line in the panel while processing runs in the background (tap: show the popup again). */
@Composable
fun ProcessingLine(ctl: EditorController) {
    val progress = ctl.processingProgress ?: return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(8.dp))
            .clickable { ctl.showProcessing() }.background(Color(0xFF26282E)).padding(10.dp).testTag("voiceProcessingLine"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text("Processing audio… ${(progress * 100).roundToInt()}% · you can keep editing", fontSize = 12.sp, color = Color.LightGray)
    }
}
