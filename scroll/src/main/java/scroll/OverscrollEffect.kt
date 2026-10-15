package scroll

import androidx.compose.animation.core.spring
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/** 拉伸上限 = 视口尺寸 × 这个比例; 位移永远到不了它。 */
private const val DefaultStretchLimitRatio = 0.55f

/** 判"够不够带回列表"用的阻尼, 比列表惯性 (2.15) 大: 小速度一律交给弹簧。 */
private const val DefaultReturnMaxDeceleration = 4.4f

private const val ReturnDecayM = 9f

private const val ReturnDecayAbsVelocityThreshold = 1f

private const val OverscrollRestThreshold = 1f

@Composable
fun rememberCupertinoOverscrollEffect(
    dampingRatio: Float = 1.0f,
    stiffness: Float = 150f,
    stretchLimitRatio: Float = DefaultStretchLimitRatio,
    returnMaxDeceleration: Float = DefaultReturnMaxDeceleration,
): OverscrollEffect {
    return remember {
        BouncyOverscrollEffect(dampingRatio, stiffness, stretchLimitRatio, returnMaxDeceleration)
    }
}

private fun Float.sanitize(): Float = if (isNaN()) 0f else this
private fun Offset.sanitize(): Offset = Offset(x.sanitize(), y.sanitize())
private fun Velocity.sanitize(): Velocity = Velocity(x.sanitize(), y.sanitize())

private class BouncyOverscrollEffect(
    val dampingRatio: Float,
    val stiffness: Float,
    val stretchLimitRatio: Float,
    returnMaxDeceleration: Float,
) : OverscrollEffect {
    private val animatorX = UnscaledAnimatable()
    private val animatorY = UnscaledAnimatable()

    private val overscrollOffset: Offset
        get() = Offset(animatorX.value, animatorY.value)

    var containerSize = IntSize.Zero

    override val isInProgress: Boolean
        get() = animatorX.value != 0f || animatorY.value != 0f

    override val node: DelegatableNode = BouncyOverscrollNode()

    private val returnDecay = BiExponentialDecay(
        maxDeceleration = returnMaxDeceleration,
        m = ReturnDecayM,
        absVelocityThreshold = ReturnDecayAbsVelocityThreshold,
    )

    private fun limitX(): Float =
        if (containerSize.width > 0) containerSize.width * stretchLimitRatio else 0f

    private fun limitY(): Float =
        if (containerSize.height > 0) containerSize.height * stretchLimitRatio else 0f

    private fun stretchSlope(offset: Float, limit: Float): Float =
        if (limit <= 0f) 1f else (1f - abs(offset) / limit).coerceIn(0f, 1f)

    /** 往外时按跟手比例折算成"看到的"速度; 归位方向本来就是 1:1。 */
    private fun visibleVelocity(velocity: Float, offset: Float, limit: Float): Float =
        if (velocity == 0f || offset == 0f || (velocity > 0f) != (offset > 0f)) velocity
        else velocity * stretchSlope(offset, limit)

    private fun springVelocity(velocity: Velocity): Offset =
        Offset(
            visibleVelocity(velocity.x, animatorX.value, limitX()),
            visibleVelocity(velocity.y, animatorY.value, limitY()),
        )

    /** 精确步进: d' = ±L·(1-(1-|d|/L)·e^{-|Δ|/L}), 任意 Δ 都落在曲线上。 */
    private fun stretched(from: Float, delta: Float, limit: Float): Float {
        if (limit <= 0f) return from + delta
        val sign = if (delta < 0f) -1f else 1f
        val remaining = (1f - abs(from) / limit).coerceIn(0f, 1f) * exp(-abs(delta) / limit)
        return sign * limit * (1f - remaining)
    }

    private fun absorb(current: Float, delta: Float, limit: Float): Float {
        if (delta == 0f) return current
        val grows = current == 0f || (delta > 0f) == (current > 0f)
        return if (grows) stretched(current, delta, limit) else current + delta
    }

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset
    ): Offset {
        val safeDelta = delta.sanitize()
        val isDrag = source == NestedScrollSource.UserInput

        if (isDrag) {
            animatorX.cancel()
            animatorY.cancel()
        }

        // 先把已有位移按 1:1 收回去 (拉回来跟手), 剩下的才轮到列表
        val (consumedX, leftX) = adjustOverscroll(safeDelta.x, animatorX.value)
        val (consumedY, leftY) = adjustOverscroll(safeDelta.y, animatorY.value)

        val consumedByOverscroll = Offset(consumedX, consumedY)
        animatorX.updateValue(animatorX.value + consumedX)
        animatorY.updateValue(animatorY.value + consumedY)

        val leftForScroll = Offset(leftX, leftY)
        val consumedByScroll = performScroll(leftForScroll).sanitize()

        val leftForStretch = leftForScroll - consumedByScroll

        if (isDrag) {
            animatorX.updateValue(absorb(animatorX.value, leftForStretch.x, limitX()))
            animatorY.updateValue(absorb(animatorY.value, leftForStretch.y, limitY()))
            return consumedByOverscroll + consumedByScroll + leftForStretch
        }

        return consumedByOverscroll + consumedByScroll
    }

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity
    ) {
        val safeVelocity = velocity.sanitize()

        val isFlingingOutward = (animatorX.value > 0f && safeVelocity.x > 0f) ||
                (animatorX.value < 0f && safeVelocity.x < 0f) ||
                (animatorY.value > 0f && safeVelocity.y > 0f) ||
                (animatorY.value < 0f && safeVelocity.y < 0f)

        // 越界方向继续甩: 列表惯性照跑, 位移交给弹簧继续往外
        if (isFlingingOutward) {
            coroutineScope {
                launch {
                    performFling(safeVelocity)
                }

                springToZero(springVelocity(safeVelocity))
            }
            return
        }

        if (overscrollOffset == Offset.Zero) {
            val consumed = performFling(safeVelocity).sanitize()
            val leftover = (safeVelocity - consumed).sanitize()
            // 撞墙剩下的速度交给弹簧起弹
            if (leftover != Velocity.Zero) {
                springToZero(springVelocity(leftover))
            }
            return
        }

        if (canCarryBack(safeVelocity)) {
            val consumed = performFling(safeVelocity).sanitize()
            val leftover = (safeVelocity - consumed).sanitize()
            if (leftover != Velocity.Zero || overscrollOffset != Offset.Zero) {
                springToZero(springVelocity(leftover))
            }
        } else {
            springToZero(springVelocity(safeVelocity))
        }
    }

    /** 行程与位移反号、且够长, 才算能把位移按 1:1 带回 0。 */
    private fun canCarryBack(velocity: Velocity): Boolean {
        fun axis(offset: Float, v: Float): Boolean {
            if (offset == 0f || v == 0f) return false
            val travel = returnDecay.getTargetValue(0f, v)
            return (travel > 0f) != (offset > 0f) && abs(travel) >= abs(offset)
        }
        return axis(animatorX.value, velocity.x) || axis(animatorY.value, velocity.y)
    }

    private suspend fun springToZero(initialVelocity: Offset) {
        val spec = spring(
            dampingRatio = dampingRatio,
            stiffness = stiffness,
            visibilityThreshold = OverscrollRestThreshold,
        )
        val vx = initialVelocity.x.sanitize()
        val vy = initialVelocity.y.sanitize()
        coroutineScope {
            launch {
                if (animatorX.value != 0f || vx != 0f) animatorX.animateTo(0f, spec, vx)
            }
            launch {
                if (animatorY.value != 0f || vy != 0f) animatorY.animateTo(0f, spec, vy)
            }
        }
    }

    private fun adjustOverscroll(delta: Float, currentRaw: Float): Pair<Float, Float> {
        if (currentRaw == 0f) return 0f to delta
        val isPullingBack = currentRaw.sign != delta.sign
        return if (isPullingBack) {
            if (abs(delta) >= abs(currentRaw)) -currentRaw to (delta + currentRaw) else delta to 0f
        } else {
            0f to delta
        }
    }

    private inner class BouncyOverscrollNode : Modifier.Node(), LayoutAwareModifierNode, LayoutModifierNode {
        override fun onRemeasured(size: IntSize) { containerSize = size }

        override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
            val placeable = measurable.measure(constraints)
            return layout(placeable.width, placeable.height) {
                val offset = overscrollOffset
                placeable.placeRelative(offset.x.toInt(), offset.y.toInt())
            }
        }
    }
}
