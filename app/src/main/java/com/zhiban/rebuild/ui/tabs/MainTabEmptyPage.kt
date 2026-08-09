package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zhiban.rebuild.navigation.MainTabContract
import com.zhiban.rebuild.ui.components.ZhiBanEmptyState
import com.zhiban.rebuild.ui.components.ZhiBanPage
import com.zhiban.rebuild.ui.components.ZhiBanPrimaryTabHeader
import com.zhiban.rebuild.ui.components.ZhiBanTabHorizontalPadding
import com.zhiban.rebuild.ui.components.ZhiBanTabTopPadding
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun MainTabEmptyPage(key: String, modifier: Modifier = Modifier) {
    val tab = MainTabContract.requireTab(key)
    ZhiBanPage(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = ZhiBanTabHorizontalPadding,
                    vertical = ZhiBanTabTopPadding,
                ),
            horizontalAlignment = Alignment.Start,
        ) {
            ZhiBanPrimaryTabHeader(
                title = tab.pageTitle,
                subtitle = emptyTabSubtitle(key),
            )
            Spacer(Modifier.height(ZhiBanSpacing.Xxl))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                ZhiBanEmptyState(tab.emptyTitle, tab.emptyDescription)
            }
        }
    }
}

private fun emptyTabSubtitle(key: String): String = when (key) {
    "calendar" -> LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy年 M月"))
    "relation" -> "0 位联系人"
    "skill" -> "扩展知伴能做的事"
    "profile" -> "管理资料与设置"
    else -> ""
}
