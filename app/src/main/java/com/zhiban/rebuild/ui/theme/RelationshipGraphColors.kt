package com.zhiban.rebuild.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class RelationshipGraphColors(
    val focusNode: Color,
    val onFocusNode: Color,
    val contactNode: Color,
    val onContactNode: Color,
    val workNode: Color,
    val onWorkNode: Color,
    val sharedCompany: Color,
    val nodeRing: Color,
)

internal val LightRelationshipGraphColors = RelationshipGraphColors(
    focusNode = Color(0xFFC97855),
    onFocusNode = Color(0xFF27120A),
    contactNode = Color(0xFF3A9690),
    onContactNode = Color(0xFF071A18),
    workNode = Color(0xFFE2B83F),
    onWorkNode = Color(0xFF211A05),
    sharedCompany = Color(0xFF7256B8),
    nodeRing = Color(0xFFFFFFFF),
)

internal val DarkRelationshipGraphColors = RelationshipGraphColors(
    focusNode = Color(0xFFF09A78),
    onFocusNode = Color(0xFF2A0E04),
    contactNode = Color(0xFF64CFC7),
    onContactNode = Color(0xFF06201D),
    workNode = Color(0xFFFFD86A),
    onWorkNode = Color(0xFF251D04),
    sharedCompany = Color(0xFFC0AAFF),
    nodeRing = Color(0xFF121212),
)

val LocalRelationshipGraphColors = staticCompositionLocalOf { LightRelationshipGraphColors }
