package scroll

import androidx.compose.animation.core.generateDecayAnimationSpec
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

@Composable
fun rememberCupertinoFlingBehavior(
    maxDeceleration: Float = 2.15f,
    m: Float = 9f,
    absVelocityThreshold: Float = 1f,
): FlingBehavior {
    return remember { CustomDecayFlingBehavior(maxDeceleration, m, absVelocityThreshold) }
}

class CustomDecayFlingBehavior(
    maxDeceleration: Float = 2.15f,
    m: Float = 9f,
    private val absVelocityThreshold: Float = 1f,
) : FlingBehavior {
    private val decaySpec =
        BiExponentialDecay(maxDeceleration, m = m, absVelocityThreshold = absVelocityThreshold)
            .generateDecayAnimationSpec<Float>()

    /** 值域是"松手之后走过的距离", 每帧的差交给 scrollBy。 */
    private val animator = UnscaledAnimatable()

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (abs(initialVelocity) <= absVelocityThreshold) return initialVelocity

        var applied = 0f
        var velocityLeft = initialVelocity
        return try {
            animator.updateValue(0f)
            animator.animateDecay(decaySpec, initialVelocity = initialVelocity) {
                // 显式 this.: 这一帧的值与速度来自 FrameScope, 别被外层同名局部变量遮住
                val delta = this.value - applied
                applied = this.value
                velocityLeft = this.velocity
                // 列表没吃下这一帧: 剩下的速度交回给越界效果
                if (abs(delta - scrollBy(delta)) != 0f) cancelAnimation()
            }
            velocityLeft
        } catch (_: CancellationException) {
            animator.velocity
        }
    }
}
