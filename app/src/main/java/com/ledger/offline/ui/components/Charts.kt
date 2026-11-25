package com.ledger.offline.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledger.offline.util.Money
import java.util.Locale
import kotlin.math.ceil

/**
 * 自绘图表组件（只用 `androidx.compose.foundation.Canvas`，不引入任何图表库）。
 *
 * - [TrendChart]：柱状图 / 折线图，两种颜色分别表示收入与支出；
 * - [CategoryPieChart]：分类结构饼图 + 图例（分类名 / 金额 / 百分比）。
 *
 * 说明：颜色全部取自 Material3 主题或固定色板；文字用 `TextMeasurer` 直接画在画布上，
 * 避免为坐标轴文字再堆一层布局。
 */

/** 趋势图类型：柱状图 / 折线图。 */
enum class ChartType(val label: String) {
    BAR("柱状图"),
    LINE("折线图")
}

/**
 * 趋势图的一个时间桶（一天 / 一个月 / 一年）。
 *
 * @param incomeCents 该桶内的收入合计（分）
 * @param expenseCents 该桶内的支出合计（分）
 */
data class ChartBar(
    val incomeCents: Long = 0L,
    val expenseCents: Long = 0L
)

/**
 * 饼图的一块数据。
 *
 * @param label 分类名（来自数据库，属于用户数据，不做翻译）
 * @param icon 分类图标（emoji）
 */
data class PieEntry(
    val label: String,
    val valueCents: Long,
    val icon: String = ""
)

/** 分类饼图的固定色板；深浅色主题下都保持可分辨。 */
private val PieColors = listOf(
    Color(0xFF2E7D6F),
    Color(0xFFB4560A),
    Color(0xFF3E6DA8),
    Color(0xFF8A5BA6),
    Color(0xFFB0475F),
    Color(0xFF6E8B3D),
    Color(0xFF9C7A2E)
)

/**
 * 收支趋势图：柱状图或折线图。
 *
 * @param bars 按时间顺序排列的桶，由 `AnalysisViewModel` 聚合
 * @param labels 与 [bars] 等长的 X 轴标签（调用方用 `tr(...)` 构造）
 * @param type 柱状图 / 折线图
 */
@Composable
fun TrendChart(
    bars: List<ChartBar>,
    labels: List<String>,
    type: ChartType,
    modifier: Modifier = Modifier
) {
    val incomeColor = MaterialTheme.colorScheme.primary
    val expenseColor = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = TextStyle(color = axisColor, fontSize = 9.sp)
    val measurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(176.dp)
    ) {
        if (bars.isEmpty()) return@Canvas
        val bottom = size.height - 18.dp.toPx()
        val top = 10.dp.toPx()
        val left = 2.dp.toPx()
        val right = size.width - 2.dp.toPx()
        if (right <= left || bottom <= top) return@Canvas
        val plotWidth = right - left
        val plotHeight = bottom - top
        val peak = bars.maxOfOrNull { bar -> maxOf(bar.incomeCents, bar.expenseCents) } ?: 0L
        val safePeak = if (peak <= 0L) 1f else peak.toFloat()

        // 25% / 50% / 75% 三条辅助网格线 + 基线
        for (fraction in listOf(0.25f, 0.5f, 0.75f)) {
            val y = bottom - plotHeight * fraction
            drawLine(
                color = gridColor,
                start = Offset(left, y),
                end = Offset(right, y),
                strokeWidth = 1.dp.toPx()
            )
        }
        drawLine(
            color = axisColor.copy(alpha = 0.5f),
            start = Offset(left, bottom),
            end = Offset(right, bottom),
            strokeWidth = 1.dp.toPx()
        )

        when (type) {
            ChartType.BAR -> drawBarSeries(
                bars = bars,
                left = left,
                plotWidth = plotWidth,
                bottom = bottom,
                plotHeight = plotHeight,
                peak = safePeak,
                incomeColor = incomeColor,
                expenseColor = expenseColor
            )

            ChartType.LINE -> drawLineSeries(
                bars = bars,
                left = left,
                plotWidth = plotWidth,
                bottom = bottom,
                plotHeight = plotHeight,
                peak = safePeak,
                incomeColor = incomeColor,
                expenseColor = expenseColor
            )
        }

        // 峰值刻度放左上角，省掉一整条 Y 轴
        if (peak > 0L) {
            val peakLayout = measurer.measure(
                text = AnnotatedString(Money.plain(peak)),
                style = labelStyle,
                maxLines = 1
            )
            drawText(peakLayout, color = axisColor, topLeft = Offset(left + 2.dp.toPx(), top - 4.dp.toPx()))
        }

        drawAxisLabels(
            labels = labels,
            measurer = measurer,
            style = labelStyle,
            color = axisColor,
            left = left,
            plotWidth = plotWidth,
            bottom = bottom,
            count = bars.size
        )
    }
}

/** 柱状图：每个时间桶内左收入、右支出并排两根柱子。 */
private fun DrawScope.drawBarSeries(
    bars: List<ChartBar>,
    left: Float,
    plotWidth: Float,
    bottom: Float,
    plotHeight: Float,
    peak: Float,
    incomeColor: Color,
    expenseColor: Color
) {
    val slot = plotWidth / bars.size
    val barWidth = (slot * 0.28f).coerceIn(1.5f, 14.dp.toPx())
    val gap = (barWidth * 0.25f).coerceIn(0.5f, 2.dp.toPx())
    bars.forEachIndexed { index, bar ->
        val centerX = left + slot * (index + 0.5f)
        val incomeHeight = plotHeight * (bar.incomeCents.toFloat() / peak).coerceIn(0f, 1f)
        val expenseHeight = plotHeight * (bar.expenseCents.toFloat() / peak).coerceIn(0f, 1f)
        if (incomeHeight > 0f) {
            drawRect(
                color = incomeColor,
                topLeft = Offset(centerX - barWidth - gap / 2f, bottom - incomeHeight),
                size = Size(barWidth, incomeHeight)
            )
        }
        if (expenseHeight > 0f) {
            drawRect(
                color = expenseColor,
                topLeft = Offset(centerX + gap / 2f, bottom - expenseHeight),
                size = Size(barWidth, expenseHeight)
            )
        }
    }
}

/** 折线图：收入 / 支出各一条折线，桶数较少时补上数据点。 */
private fun DrawScope.drawLineSeries(
    bars: List<ChartBar>,
    left: Float,
    plotWidth: Float,
    bottom: Float,
    plotHeight: Float,
    peak: Float,
    incomeColor: Color,
    expenseColor: Color
) {
    val slot = plotWidth / bars.size
    val incomePath = Path()
    val expensePath = Path()
    val showPoints = bars.size <= 12
    bars.forEachIndexed { index, bar ->
        val x = left + slot * (index + 0.5f)
        val incomeY = bottom - plotHeight * (bar.incomeCents.toFloat() / peak).coerceIn(0f, 1f)
        val expenseY = bottom - plotHeight * (bar.expenseCents.toFloat() / peak).coerceIn(0f, 1f)
        if (index == 0) {
            incomePath.moveTo(x, incomeY)
            expensePath.moveTo(x, expenseY)
        } else {
            incomePath.lineTo(x, incomeY)
            expensePath.lineTo(x, expenseY)
        }
        if (showPoints) {
            drawCircle(color = incomeColor, radius = 2.5.dp.toPx(), center = Offset(x, incomeY))
            drawCircle(color = expenseColor, radius = 2.5.dp.toPx(), center = Offset(x, expenseY))
        }
    }
    val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawPath(incomePath, color = incomeColor, style = stroke)
    drawPath(expensePath, color = expenseColor, style = stroke)
}

/** X 轴标签：桶多时按步长抽稀（最多约 6 个），放不下就跳过。 */
private fun DrawScope.drawAxisLabels(
    labels: List<String>,
    measurer: TextMeasurer,
    style: TextStyle,
    color: Color,
    left: Float,
    plotWidth: Float,
    bottom: Float,
    count: Int
) {
    if (count <= 0) return
    val slot = plotWidth / count
    // 近 7 天只显示 1–2 位“日”，全部 7 个标签都画；其它长周期按 6 个抽稀，避免拥挤。
    val labelStep = if (count <= 7) 1 else ceil(count / 6f).toInt().coerceAtLeast(1)
    for (index in (0 until count) step labelStep) {
        val text = labels.getOrNull(index) ?: continue
        val layout = measurer.measure(
            text = AnnotatedString(text),
            style = style,
            maxLines = 1
        )
        if (layout.size.width.toFloat() > slot * labelStep * 1.3f) continue
        val centerX = left + slot * (index + 0.5f)
        drawText(
            textLayoutResult = layout,
            color = color,
            topLeft = Offset(centerX - layout.size.width / 2f, bottom + 3.dp.toPx())
        )
    }
}

/**
 * 分类结构饼图：环形饼图 + 图例（分类名 / 金额 / 百分比）。
 *
 * @param entries 分类合计，内部会按金额降序截断，其余合并成 [otherLabel]
 * @param otherLabel “其他”这一块的名称，由调用方用 `tr(...)` 传入
 * @param maxSlices 最多展示多少块（含“其他”），避免饼图碎片化
 */
@Composable
fun CategoryPieChart(
    entries: List<PieEntry>,
    otherLabel: String,
    modifier: Modifier = Modifier,
    maxSlices: Int = 6
) {
    val slices = remember(entries, otherLabel, maxSlices) {
        buildPieSlices(entries, otherLabel, maxSlices)
    }
    if (slices.isEmpty()) return
    val total = slices.sumOf { slice -> slice.valueCents }.coerceAtLeast(1L)
    val holeColor = MaterialTheme.colorScheme.surface

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Canvas(modifier = Modifier.size(150.dp)) {
            var startAngle = -90f
            slices.forEachIndexed { index, slice ->
                val sweep = 360f * slice.valueCents.toFloat() / total.toFloat()
                drawArc(
                    color = PieColors[index % PieColors.size],
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = true
                )
                startAngle += sweep
            }
            // 中心镂空成环形，颜色与卡片背景一致
            drawCircle(color = holeColor, radius = size.minDimension * 0.3f)
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            slices.forEachIndexed { index, slice ->
                PieLegendRow(
                    color = PieColors[index % PieColors.size],
                    slice = slice,
                    percent = slice.valueCents.toFloat() * 100f / total.toFloat()
                )
            }
        }
    }
}

/** 饼图图例的一行：色块 + 分类名 + 金额 + 百分比。 */
@Composable
private fun PieLegendRow(color: Color, slice: PieEntry, percent: Float) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (slice.icon.isBlank()) slice.label else "${slice.icon} ${slice.label}",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = Money.format(slice.valueCents),
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatPercent(percent),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 按金额降序排序，超出 [maxSlices] 的尾部合并为“其他”。 */
private fun buildPieSlices(entries: List<PieEntry>, otherLabel: String, maxSlices: Int): List<PieEntry> {
    val sorted = entries
        .filter { entry -> entry.valueCents > 0L }
        .sortedByDescending { entry -> entry.valueCents }
    if (sorted.isEmpty()) return emptyList()
    val limit = maxSlices.coerceAtLeast(2)
    if (sorted.size <= limit) return sorted
    val head = sorted.take(limit - 1)
    val restCents = sorted.drop(limit - 1).sumOf { entry -> entry.valueCents }
    if (restCents <= 0L) return head
    return head + PieEntry(label = otherLabel, valueCents = restCents, icon = "")
}

/** 百分比文本，例如 “42.5%”。 */
private fun formatPercent(percent: Float): String =
    String.format(Locale.getDefault(), "%.1f%%", percent)
