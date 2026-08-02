package com.photon.remote.ui.adddevice

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photon.remote.PhotonApplication
import com.photon.remote.ui.adddevice.steps.BrandStep
import com.photon.remote.ui.adddevice.steps.ModelStep
import com.photon.remote.ui.adddevice.steps.StbAreaStep
import com.photon.remote.ui.adddevice.steps.TypeStep
import com.photon.remote.viewmodel.AddDeviceViewModel

/**
 * 添加设备向导（计划 §5.4 / Todo 27-28），HorizontalPager 分步。
 *
 * 步骤（机顶盒多一步地区选择）：
 *   0 设备类型 → 1 品牌 → 2 [地区 或 型号] → 3 [型号 或 测试] → 4 测试
 * 顶部：关闭按钮 + 步骤标题 + 步骤圆点；底部：上一步/下一步。
 * STB 品牌无地区数据时自动跳过地区页。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceScreen(
    onFinished: () -> Unit,
) {
    // ViewModel：依赖从手动 DI 容器（PhotonApplication.container）获取
    val app = LocalContext.current.applicationContext as PhotonApplication
    val viewModel: AddDeviceViewModel = viewModel {
        AddDeviceViewModel(
            repository = app.container.repository,
            indexLoader = app.container.indexLoader,
            irdbParser = app.container.irdbParser,
            codeResolver = app.container.codeResolver,
            dispatcher = app.container.irDispatcher,
            transmitter = app.container.transmitterManager,
            locationResolver = app.container.locationResolver,
        )
    }
    val currentPage by viewModel.currentPage.collectAsState()
    val selectedType by viewModel.selectedType.collectAsState()
    val brandHasAreas by viewModel.brandHasAreas.collectAsState()
    val savedId by viewModel.savedDeviceId.collectAsState()

    val pagerState = rememberPagerState(pageCount = { viewModel.pageCount })

    // 分页器 → VM 同步（用户滑动回写）
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { viewModel.setPage(it) }
    }
    // VM → 分页器同步（上一步/下一步/选择跳转）
    LaunchedEffect(currentPage) {
        if (pagerState.currentPage != currentPage) pagerState.animateScrollToPage(currentPage)
    }
    // STB 品牌无地区数据 → 自动跳过地区页（第 2 页）
    LaunchedEffect(currentPage, brandHasAreas) {
        if (currentPage == 2 && viewModel.isStbAreaStep && !brandHasAreas && viewModel.selectedBrand.value != null) {
            viewModel.nextPage()
        }
    }
    // 保存成功 → 退出向导
    LaunchedEffect(savedId) {
        if (savedId != null) onFinished()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stepTitle(viewModel, currentPage), style = MaterialTheme.typography.titleMedium)
                        // 步骤圆点指示器
                        Row {
                            repeat(viewModel.pageCount) { i ->
                                StepDot(active = i == currentPage, done = i < currentPage)
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onFinished) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(16.dp)) {
                    OutlinedButton(
                        onClick = viewModel::previousPage,
                        enabled = currentPage > 0,
                    ) { Text("上一步") }
                    Spacer(Modifier.weight(1f))
                    if (currentPage < viewModel.pageCount - 1) {
                        Button(
                            onClick = viewModel::nextPage,
                            enabled = viewModel.isNextEnabled(currentPage),
                        ) { Text("下一步") }
                    }
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = false,   // 向导模式：翻页一律经底部按钮/选择逻辑
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { page ->
            when (page) {
                0 -> TypeStep(
                    selected = selectedType,
                    onSelect = viewModel::selectType,
                )
                1 -> BrandStep(
                    brands = viewModel.brands.collectAsState().value,
                    selectedName = viewModel.selectedBrand.collectAsState().value?.name,
                    onSelect = viewModel::selectBrand,
                )
                2 -> if (viewModel.isStbAreaStep) {
                    StbAreaStep(viewModel)
                } else {
                    ModelStep(
                        models = viewModel.models.collectAsState().value,
                        selectedRef = viewModel.selectedCode.collectAsState().value?.codeRef,
                        onSelect = viewModel::selectCode,
                    )
                }
                3 -> if (viewModel.isStbAreaStep) {
                    ModelStep(
                        models = viewModel.models.collectAsState().value,
                        selectedRef = viewModel.selectedCode.collectAsState().value?.codeRef,
                        onSelect = viewModel::selectCode,
                    )
                } else {
                    TestRemoteStep(viewModel)
                }
                else -> TestRemoteStep(viewModel)
            }
        }
    }
}

/** 步骤标题 */
@Composable
private fun stepTitle(vm: AddDeviceViewModel, page: Int): String = when {
    page == 0 -> "选择设备类型"
    page == 1 -> "选择品牌"
    page == 2 -> if (vm.isStbAreaStep) "选择地区" else "选择型号"
    page == 3 -> if (vm.isStbAreaStep) "选择型号" else "测试遥控器"
    else -> "测试遥控器"
}

/** 步骤圆点（active=当前，done=已完成） */
@Composable
private fun StepDot(active: Boolean, done: Boolean) {
    Spacer(Modifier.width(6.dp))
    Surface(
        shape = CircleShape,
        color = when {
            active -> MaterialTheme.colorScheme.primary
            done -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.padding(top = 4.dp).size(8.dp),
    ) {}
}
