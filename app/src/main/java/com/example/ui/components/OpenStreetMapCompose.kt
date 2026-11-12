package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.LocationData
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.MidnightBg
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceVariantDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

private val httpClient = OkHttpClient.Builder().build()
private const val TILE_SIZE = 256

@Composable
fun OpenStreetMapCompose(
    location: LocationData?,
    modifier: Modifier = Modifier,
    deviceName: String = "Monitored Phone"
) {
    val targetLat = location?.latitude ?: 37.7749 // Default SF fallback if no GPS fix yet
    val targetLon = location?.longitude ?: -122.4194

    var zoom by remember { mutableIntStateOf(15) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val tileCache = remember { mutableStateMapOf<String, Bitmap>() }
    val scope = rememberCoroutineScope()

    // Function to recenter on target device
    val recenter = {
        offsetX = 0f
        offsetY = 0f
    }

    LaunchedEffect(targetLat, targetLon) {
        recenter()
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MidnightBg)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        offsetX += pan.x
                        offsetY += pan.y
                        if (gestureZoom > 1.2f && zoom < 18) {
                            zoom += 1
                        } else if (gestureZoom < 0.8f && zoom > 3) {
                            zoom -= 1
                        }
                    }
                }
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            val n = 2.0.pow(zoom.toDouble())
            val radLat = Math.toRadians(targetLat)
            val centerTileX = ((targetLon + 180.0) / 360.0 * n)
            val centerTileY = ((1.0 - asinh(tan(radLat)) / PI) / 2.0 * n)

            val screenCenterX = canvasWidth / 2f + offsetX
            val screenCenterY = canvasHeight / 2f + offsetY

            val startTileX = floor(centerTileX - (canvasWidth / (2f * TILE_SIZE)) - 1).toInt()
            val endTileX = floor(centerTileX + (canvasWidth / (2f * TILE_SIZE)) + 1).toInt()
            val startTileY = floor(centerTileY - (canvasHeight / (2f * TILE_SIZE)) - 1).toInt()
            val endTileY = floor(centerTileY + (canvasHeight / (2f * TILE_SIZE)) + 1).toInt()

            val maxTile = (n - 1).toInt()

            for (tx in startTileX..endTileX) {
                for (ty in startTileY..endTileY) {
                    if (tx in 0..maxTile && ty in 0..maxTile) {
                        val tileKey = "$zoom/$tx/$ty"
                        val posX = screenCenterX + ((tx - centerTileX) * TILE_SIZE).toFloat()
                        val posY = screenCenterY + ((ty - centerTileY) * TILE_SIZE).toFloat()

                        val bitmap = tileCache[tileKey]
                        if (bitmap != null && !bitmap.isRecycled) {
                            drawImage(
                                image = bitmap.asImageBitmap(),
                                dstOffset = IntOffset(posX.toInt(), posY.toInt()),
                                dstSize = IntSize(TILE_SIZE, TILE_SIZE)
                            )
                        } else {
                            // Dark grid tile placeholder
                            drawRect(
                                color = SurfaceDark,
                                topLeft = Offset(posX, posY),
                                size = androidx.compose.ui.geometry.Size(TILE_SIZE.toFloat(), TILE_SIZE.toFloat())
                            )
                            drawRect(
                                color = Color(0xFF1E293B),
                                topLeft = Offset(posX, posY),
                                size = androidx.compose.ui.geometry.Size(TILE_SIZE.toFloat(), TILE_SIZE.toFloat()),
                                style = Stroke(width = 1f)
                            )
                            // Fetch tile in background
                            scope.launch(Dispatchers.IO) {
                                fetchTile(zoom, tx, ty)?.let { loaded ->
                                    withContext(Dispatchers.Main) {
                                        tileCache[tileKey] = loaded
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Draw Accuracy Radius
            val accuracyRadius = ((location?.accuracy ?: 15f) * 2.5f).coerceIn(24f, 120f)
            drawCircle(
                color = CyberEmerald.copy(alpha = 0.2f),
                radius = accuracyRadius,
                center = Offset(screenCenterX, screenCenterY)
            )
            drawCircle(
                color = CyberEmerald.copy(alpha = 0.5f),
                radius = accuracyRadius,
                center = Offset(screenCenterX, screenCenterY),
                style = Stroke(width = 2f)
            )

            // Draw Target Pin Marker
            drawCircle(
                color = Color.White,
                radius = 12f,
                center = Offset(screenCenterX, screenCenterY)
            )
            drawCircle(
                color = CyberEmerald,
                radius = 9f,
                center = Offset(screenCenterX, screenCenterY)
            )
        }

        // Location info chip (Top overlay)
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            shape = RoundedCornerShape(12.dp),
            color = SurfaceVariantDark.copy(alpha = 0.92f),
            shadowElevation = 4.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(CyberEmerald, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = deviceName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Text(
                    text = String.format("%.5f, %.5f", targetLat, targetLon),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Accuracy: ±${location?.accuracy?.toInt() ?: 10}m",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberEmerald
                )
            }
        }

        // Map control buttons (Bottom right overlay)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FloatingActionButton(
                onClick = recenter,
                modifier = Modifier.size(42.dp),
                containerColor = SurfaceDark,
                contentColor = CyberEmerald,
                elevation = FloatingActionButtonDefaults.elevation(2.dp)
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Recenter", modifier = Modifier.size(20.dp))
            }

            FloatingActionButton(
                onClick = { if (zoom < 18) zoom += 1 },
                modifier = Modifier.size(42.dp),
                containerColor = SurfaceDark,
                contentColor = Color.White,
                elevation = FloatingActionButtonDefaults.elevation(2.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Zoom In", modifier = Modifier.size(20.dp))
            }

            FloatingActionButton(
                onClick = { if (zoom > 3) zoom -= 1 },
                modifier = Modifier.size(42.dp),
                containerColor = SurfaceDark,
                contentColor = Color.White,
                elevation = FloatingActionButtonDefaults.elevation(2.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Zoom Out", modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun fetchTile(z: Int, x: Int, y: Int): Bitmap? {
    return try {
        val url = "https://tile.openstreetmap.org/$z/$x/$y.png"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "HomeSafe-AndroidApp/1.0 (contact: android@homesafe.local)")
            .build()

        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            val bytes = response.body?.bytes()
            if (bytes != null) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else null
        } else null
    } catch (e: Exception) {
        null
    }
}
