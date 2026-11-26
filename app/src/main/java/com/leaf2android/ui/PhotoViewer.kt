package com.leaf2android.ui

import com.leaf2android.R
import com.leaf2android.localization.*

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leaf2android.data.*
import com.leaf2android.protocol.ImagePoint
import com.leaf2android.protocol.ImageZoom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

private data class PreviewAnalysis(val rgb: List<IntArray>, val high: Bitmap, val low: Bitmap)
private data class LoadedPreview(val bitmap: Bitmap, val analysis: PreviewAnalysis)

private fun loadPreview(path: String): LoadedPreview {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth.toLong() * bounds.outHeight / (sample.toLong() * sample) > 9_000_000) sample *= 2
    val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
        ?: throw java.io.IOException(tr(R.string.the_cached_preview_cannot_be_decoded_read_it_ag_a1858))
    val width = min(512, bitmap.width)
    val height = max(1, bitmap.height * width / bitmap.width)
    val sampled = Bitmap.createScaledBitmap(bitmap, width, height, false)
    val pixels = IntArray(width * height)
    sampled.getPixels(pixels, 0, width, 0, 0, width, height)
    val rgb = List(3) { IntArray(256) }
    val high = IntArray(pixels.size)
    val low = IntArray(pixels.size)
    pixels.forEachIndexed { i, pixel ->
        val r = (pixel ushr 16) and 255; val g = (pixel ushr 8) and 255; val b = pixel and 255
        rgb[0][r]++; rgb[1][g]++; rgb[2][b]++
        if (maxOf(r, g, b) >= 250) high[i] = 0xB8FF563D.toInt()
        if (maxOf(r, g, b) <= 5) low[i] = 0xB844AAFF.toInt()
    }
    val highBitmap = Bitmap.createBitmap(high, width, height, Bitmap.Config.ARGB_8888)
    val lowBitmap = Bitmap.createBitmap(low, width, height, Bitmap.Config.ARGB_8888)
    if (sampled !== bitmap) sampled.recycle()
    return LoadedPreview(bitmap, PreviewAnalysis(rgb, highBitmap, lowBitmap))
}

@Composable
fun PhotoViewer(photo: LeafPhoto, repo: LeafRepository, connected: Boolean, modifier: Modifier = Modifier) {
    var loaded by remember(photo.path, photo.sha256) { mutableStateOf<LoadedPreview?>(null) }
    var loadError by remember(photo.path, photo.sha256) { mutableStateOf<String?>(null) }
    var viewport by remember(photo.path) { mutableStateOf(repo.viewport(photo.path)) }
    val options by repo.settings.collectAsStateWithLifecycle()
    val histogram = options.showHistogram
    val highlight = options.showHighlights
    val shadow = options.showShadows
    var info by rememberSaveable { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf(false) }
    var imageTools by rememberSaveable(photo.path, photo.connectionGeneration) { mutableStateOf(false) }
    var photoActions by rememberSaveable(photo.path, photo.connectionGeneration) { mutableStateOf(false) }
    var grayPicking by rememberSaveable(photo.path, photo.connectionGeneration) { mutableStateOf(false) }
    var grayFractionX by rememberSaveable(photo.path, photo.sha256, photo.connectionGeneration) { mutableStateOf<Double?>(null) }
    var grayFractionY by rememberSaveable(photo.path, photo.sha256, photo.connectionGeneration) { mutableStateOf<Double?>(null) }
    var grayPointX by rememberSaveable(photo.path, photo.sha256, photo.connectionGeneration) { mutableStateOf<Int?>(null) }
    var grayPointY by rememberSaveable(photo.path, photo.sha256, photo.connectionGeneration) { mutableStateOf<Int?>(null) }
    var grayOutsideImage by rememberSaveable(photo.path, photo.connectionGeneration) { mutableStateOf(false) }
    var backZoomMode by rememberSaveable(photo.path, photo.connectionGeneration) { mutableStateOf(false) }
    var backZoomBitmap by remember(photo.path, photo.connectionGeneration) { mutableStateOf<Bitmap?>(null) }
    val session by repo.state.collectAsStateWithLifecycle()
    val currentBackPhoto = connected && isCurrentBackPhoto(photo, session)
    val backEnabled = currentBackPhoto && !session.controlsBusy && !session.labMode
    val grayMapping = remember(photo.metadata, photo.sha256, photo.metadataPreviewSha256, session.camera, photo.width, photo.height) { photoGrayMapping(photo, session.camera) }
    val grayFraction = if (grayFractionX != null && grayFractionY != null) ImageFraction(grayFractionX!!, grayFractionY!!) else null
    val grayPoint = if (grayPointX != null && grayPointY != null) ImagePoint(grayPointX!!, grayPointY!!) else null
    val activeBackPreview = session.backPreview?.takeIf {
        it.photo.path == photo.path && it.photo.deviceAddress == session.deviceAddress &&
            it.photo.connectionGeneration == session.connectionGeneration && it.zoom == ImageZoom.X1
    }
    LaunchedEffect(activeBackPreview?.photo?.sha256, backZoomMode) {
        if (!backZoomMode) {
            backZoomBitmap = null
        } else {
            val file = activeBackPreview?.photo?.cacheFile
            backZoomBitmap = if (file != null) {
                withContext(Dispatchers.Default) { BitmapFactory.decodeFile(file) }
            } else {
                null
            }
        }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        if (uri != null && photo.cacheFile != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out -> File(photo.cacheFile).inputStream().use { it.copyTo(out) } }
                        ?: throw java.io.IOException(tr(R.string.could_not_open_the_save_location_6afef))
                }
            } catch (e: Exception) { repo.showError(tr(R.string.could_not_save_preview_1_s_3e10a , e.message)) }
        }
    }
    fun move(next: Viewport) { viewport = next; repo.setViewport(photo.path, next) }
    LaunchedEffect(photo.path, photo.cacheFile, photo.sha256) {
        loaded = null
        loadError = null
        photo.cacheFile?.let { path ->
            try { loaded = withContext(Dispatchers.Default) { loadPreview(path) } }
            catch (e: Exception) { loadError = e.message ?: tr(R.string.could_not_load_preview_270c5) }
        }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(photo.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(photo.source.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (connected) IconButton(onClick = repo::refreshSelected) { Icon(Icons.Default.Refresh, tr(R.string.read_preview_again_e63d3)) }
            IconButton(onClick = { repo.star(photo.path) }) { Icon(if (photo.starred) Icons.Default.Star else Icons.Default.StarBorder, tr(R.string.local_flag_fdc55)) }
            IconButton(onClick = { info = true }) { Icon(Icons.Default.Info, tr(R.string.photo_information_8aa32)) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { imageTools = true }, enabled = backEnabled, modifier = Modifier.weight(1f).testTag("photo-open-image-tools")) {
                Icon(Icons.Default.Tune, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr(R.string.back_image_tools), maxLines = 2)
            }
            OutlinedButton(onClick = { photoActions = true }, modifier = Modifier.weight(1f).testTag("photo-open-actions")) {
                Icon(Icons.Default.MoreHoriz, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr(R.string.photo_actions), maxLines = 2)
            }
        }
        if (connected && !photo.isDemo && !currentBackPhoto) Text(tr(R.string.refresh_photo_before_back_write),
            Modifier.padding(horizontal = 20.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
