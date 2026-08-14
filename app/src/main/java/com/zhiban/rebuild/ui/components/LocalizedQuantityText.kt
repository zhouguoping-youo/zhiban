package com.zhiban.rebuild.ui.components

import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource

@Composable
fun localizedQuantity(@PluralsRes resourceId: Int, count: Int): String = pluralStringResource(resourceId, count, count)
