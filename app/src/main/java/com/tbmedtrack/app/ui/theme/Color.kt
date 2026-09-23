package com.tbmedtrack.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── Primary accents (indigo / purple) ────────────────────────────────
val Indigo = Color(0xFF6366F1)          // primary accent
val IndigoDark = Color(0xFF818CF8)      // lighter for dark bg
val IndigoContainer = Color(0xFFE0E7FF)
val IndigoContainerDark = Color(0xFF312E81)
val AccentPurple = Color(0xFF8B5CF6)
val AccentBlue = Color(0xFF60A5FA)

// ── Status colors ────────────────────────────────────────────────────
// orange = due, green = taken, red = overdue only
val StatusTaken = Color(0xFF22C55E)            // green – taken
val StatusTakenContainer = Color(0xFF14321F)
val StatusUpcoming = Color(0xFFF59E0B)         // orange – due
val StatusUpcomingContainer = Color(0xFF3A2C10)
val StatusMissed = Color(0xFFEF4444)           // red – overdue
val StatusMissedContainer = Color(0xFF3A1616)
val StatusNeutral = Color(0xFF94A3B8)          // muted blue-gray

// Orange due accent (hero / badges)
val DueOrange = Color(0xFFF97316)
val DueOrangeSoft = Color(0xFFFB923C)

// ── Light surfaces (kept for light path) ─────────────────────────────
val LightBackground = Color(0xFFF6F7FB)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEEF1F6)
val LightOnSurface = Color(0xFF1E293B)
val LightOutline = Color(0xFFCBD5E1)

// ── Dark premium navy/midnight surfaces ──────────────────────────────
val DarkBackground = Color(0xFF0B1020)     // deep midnight navy
val DarkBackgroundTop = Color(0xFF121A32)  // gradient top
val DarkSurface = Color(0xFF161C33)        // dark glass card base
val DarkSurfaceElevated = Color(0xFF1E2643) // elevated card
val DarkSurfaceVariant = Color(0xFF232B48)
val DarkOnSurface = Color(0xFFEEF1FA)      // near-white / lavender text
val DarkOnSurfaceMuted = Color(0xFF94A3B8) // muted blue-gray secondary
val DarkOutline = Color(0xFF2C3556)        // subtle border
val DarkGlassBorder = Color(0x33FFFFFF)    // translucent white border
