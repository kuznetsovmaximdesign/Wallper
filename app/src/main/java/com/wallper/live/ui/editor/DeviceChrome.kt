package com.wallper.live.ui.editor

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wallper.live.ui.theme.ClockStyle
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

enum class ChromeMode { Lock, Home, Clean }

/** Mock system UI over the preview so the wallpaper is judged in context. */
@Composable
fun DeviceChrome(mode: ChromeMode, modifier: Modifier = Modifier) {
    when (mode) {
        ChromeMode.Lock -> LockChrome(modifier)
        ChromeMode.Home -> HomeChrome(modifier)
        ChromeMode.Clean -> Unit
    }
}

private val textShadow = Shadow(Color(0x66000000), blurRadius = 24f)

@Composable
private fun LockChrome(modifier: Modifier) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay((60 - LocalDateTime.now().second) * 1000L)
            now = LocalDateTime.now()
        }
    }
    val locale = Locale.getDefault()
    val time = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm", locale)
    val date = DateTimeFormatter.ofPattern("EEEE, d MMMM", locale)

    Column(
        modifier = modifier.fillMaxSize().padding(top = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(10.dp))
        Text(
            text = now.format(date).replaceFirstChar { it.titlecase(locale) },
            style = MaterialTheme.typography.titleMedium.copy(shadow = textShadow),
            color = Color.White.copy(alpha = 0.92f),
        )
        Text(
            text = now.format(time),
            style = ClockStyle.copy(shadow = textShadow),
            color = Color.White.copy(alpha = 0.94f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HomeChrome(modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 64.dp)) {
        Column(
            modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 420.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            repeat(4) { IconRow() }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 420.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { i ->
                    Box(
                        Modifier.size(6.dp).background(Color.White.copy(alpha = if (i == 0) 0.9f else 0.35f), CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(30.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(30.dp))
                    .padding(14.dp),
            ) { IconRow() }
        }
    }
}

@Composable
private fun IconRow() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        repeat(4) {
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .size(58.dp)
                    .aspectRatio(1f)
                    .background(Color.White.copy(alpha = 0.20f), RoundedCornerShape(18.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(18.dp)),
            )
        }
    }
}
