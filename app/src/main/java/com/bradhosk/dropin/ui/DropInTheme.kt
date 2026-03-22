package com.bradhosk.dropin.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bradhosk.dropin.R

// ── Palette ──────────────────────────────────────────────────
object DropInColors {
    val background   = Color(0xFF0E0E10)
    val surface      = Color(0xFF1A1A1E)
    val surfaceAlt   = Color(0xFF222226)
    val border       = Color(0xFF2C2C30)
    val textPrimary  = Color(0xFFEAEAEC)
    val textSecondary = Color(0xFF7A7A82)
    val accent       = Color(0xFF00E5A0)
    val accentDim    = Color(0xFF003D2B)
    val danger       = Color(0xFFFF4D5A)
    val dangerDim    = Color(0xFF3A1520)
    val controlBg    = Color(0xCC1A1A1E)   // 80 % opaque surface
    val controlOff   = Color(0xCC3A1520)   // 80 % opaque danger-dim
}

// ── Fonts ────────────────────────────────────────────────────
private val fontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val outfitFont = GoogleFont("Outfit")
private val dmSansFont = GoogleFont("DM Sans")

private val OutfitFamily = FontFamily(
    Font(googleFont = outfitFont, fontProvider = fontProvider, weight = FontWeight.Light),
    Font(googleFont = outfitFont, fontProvider = fontProvider, weight = FontWeight.Normal),
    Font(googleFont = outfitFont, fontProvider = fontProvider, weight = FontWeight.Medium),
    Font(googleFont = outfitFont, fontProvider = fontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = outfitFont, fontProvider = fontProvider, weight = FontWeight.Bold),
)

private val DmSansFamily = FontFamily(
    Font(googleFont = dmSansFont, fontProvider = fontProvider, weight = FontWeight.Normal),
    Font(googleFont = dmSansFont, fontProvider = fontProvider, weight = FontWeight.Medium),
    Font(googleFont = dmSansFont, fontProvider = fontProvider, weight = FontWeight.SemiBold),
)

// ── Typography ───────────────────────────────────────────────
private val DropInTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        letterSpacing = (-0.5).sp,
        color = DropInColors.textPrimary,
    ),
    headlineMedium = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        letterSpacing = (-0.3).sp,
        color = DropInColors.textPrimary,
    ),
    titleMedium = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 1.2.sp,
        color = DropInColors.textSecondary,
    ),
    bodyLarge = TextStyle(
        fontFamily = DmSansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        color = DropInColors.textPrimary,
    ),
    bodyMedium = TextStyle(
        fontFamily = DmSansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        color = DropInColors.textSecondary,
    ),
    bodySmall = TextStyle(
        fontFamily = DmSansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        color = DropInColors.textSecondary,
    ),
    labelLarge = TextStyle(
        fontFamily = DmSansFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        color = DropInColors.accent,
    ),
    labelMedium = TextStyle(
        fontFamily = DmSansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.8.sp,
        color = DropInColors.textSecondary,
    ),
)

// ── Color Scheme ─────────────────────────────────────────────
private val DropInColorScheme = darkColorScheme(
    primary = DropInColors.accent,
    onPrimary = Color(0xFF00311F),
    secondary = DropInColors.textSecondary,
    background = DropInColors.background,
    onBackground = DropInColors.textPrimary,
    surface = DropInColors.surface,
    onSurface = DropInColors.textPrimary,
    surfaceVariant = DropInColors.surfaceAlt,
    outline = DropInColors.border,
    error = DropInColors.danger,
)

// ── Shapes ───────────────────────────────────────────────────
private val DropInShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
)

// ── Theme Composable ─────────────────────────────────────────
@Composable
fun DropInTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DropInColorScheme,
        typography = DropInTypography,
        shapes = DropInShapes,
        content = content,
    )
}
