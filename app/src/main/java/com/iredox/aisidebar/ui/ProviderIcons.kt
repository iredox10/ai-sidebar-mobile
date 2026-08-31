package com.iredox.aisidebar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ProviderBadge(provider: String, modifier: Modifier = Modifier) {
    val p = provider.lowercase()
    val (bg, fg, label) = when (p) {
        "openai" -> Triple(Color(0xFFFFFFFF), Color(0xFF000000), "O")
        "anthropic" -> Triple(Color(0xFFFFFFFF), Color(0xFF000000), "A")
        "google" -> Triple(Color(0xFFFFFFFF), Color(0xFF000000), "G")
        "deepseek" -> Triple(Color(0xFFFFFFFF), Color(0xFF4D6BFE), "D")
        "openrouter" -> Triple(Color(0xFFFFFFFF), Color(0xFF6463E2), "OR")
        "custom" -> Triple(Color(0xFFFFFFFF), Color(0xFF14B8A6), "C")
        else -> Triple(Color(0xFFFFFFFF), Color(0xFF000000), p.take(2).uppercase())
    }
    val isSerif = p == "anthropic"
    Box(
        modifier = modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = fg,
            fontSize = if (label.length == 1) 10.sp else 8.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = if (isSerif) FontFamily.Serif else FontFamily.SansSerif,
            maxLines = 1
        )
    }
}

@Composable
fun ProviderIconWithLabel(provider: String, label: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        ProviderBadge(provider)
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
