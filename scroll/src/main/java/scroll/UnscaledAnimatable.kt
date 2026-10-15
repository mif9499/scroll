package scroll

import androidx.compose.animation.core.AnimationConstants
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VectorizedAnimationSpec
import androidx.compose.animation.core.VectorizedDecayAnimationSpec
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import android.util.Log
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job

private const val DebugAnimationFrames = false

/** 只认真实帧时间的 Float 动画器: 1x (不看系统动画速率), 时间原点=起步这一刻 (无首帧延迟)。 */
@Stable
class UnscaledAnimatable(initialValue: Float = 0f) {
    var value: Float by mutableFloatStateOf(initialValue)
        private set

    /** 第一帧到来前是起始速度, 不留上一段的陈旧值。 */
    var velocity: Float = 0f
        private set

    var isRunning: Boolean by mutableStateOf(false)
        private set

    var lastFrameTimeNanos: Long = AnimationConstants.UnspecifiedTime
        private set

    private val mutex = MutatorMutex()

    private val clock = FrameClock()

    private var runningJob: Job? = null

    /** 区分"我这一段动画"与顶掉我的那一段: 收尾时别抹掉新动画的状态。 */
    private var generation = 0

    /** 直接写值, 不碰正在跑的动画 (拖拽/越界记账这类权威写入)。 */
    fun updateValue(targetValue: Float) {
        value = targetValue
    }

    fun cancel() {
        val job = runningJob
        runningJob = null
        isRunning = false

        generation++
        job?.cancel()
    }

    suspend fun snapTo(targetValue: Float) {
        mutex.mutate(MutatePriority.Default) {
            value = targetValue
            velocity = 0f
        }
    }

    suspend fun animateTo(
        targetValue: Float,
        animationSpec: AnimationSpec<Float>,
        initialVelocity: Float = velocity,
        startTimeNanos: Long = System.nanoTime(),
        onFrame: FrameScope.() -> Unit = {},
    ) {
        animate(
            animation =
                TargetFloatAnimation(
                    spec = animationSpec.vectorize(Float.VectorConverter),
                    initialValue = value,
                    targetValue = targetValue,
                    initialVelocity = initialVelocity,
                ),
            startTimeNanos = startTimeNanos,
            onFrame = onFrame,
        )
    }

    suspend fun animateDecay(
        decaySpec: DecayAnimationSpec<Float>,
        initialVelocity: Float = velocity,
        startTimeNanos: Long = System.nanoTime(),
        onFrame: FrameScope.() -> Unit = {},
    ) {
        animate(
            animation =
                DecayFloatAnimation(
                    spec = decaySpec.vectorize(Float.VectorConverter),
                    initialValue = value,
                    initialVelocity = initialVelocity,
                ),
            startTimeNanos = startTimeNanos,
            onFrame = onFrame,
        )
    }

    private suspend fun animate(
        animation: FloatAnimation,
        startTimeNanos: Long,
        onFrame: FrameScope.() -> Unit,
    ) {
        mutex.mutate(MutatePriority.Default) {
            val myGeneration = ++generation
            val start =
                if (startTimeNanos == AnimationConstants.UnspecifiedTime) System.nanoTime()
                else startTimeNanos
            val scope = FrameScope(this@UnscaledAnimatable)
            runningJob = coroutineContext[Job]
            isRunning = true

            velocity = animation.initialVelocity

            clock.reset(start)
            try {
                while (scope.isRunning) {
                    withFrameNanos { frameTimeNanos ->
                        if (!clock.advance(frameTimeNanos)) return@withFrameNanos
                        val playTimeNanos = clock.playTimeNanos
                        value = animation.valueAt(playTimeNanos)
                        velocity = animation.velocityAt(playTimeNanos)
                        lastFrameTimeNanos = clock.lastFrameTimeNanos
                        scope.startTimeNanos = start
                        scope.playTimeNanos = playTimeNanos
                        scope.lastFrameTimeNanos = clock.lastFrameTimeNanos
                        if (playTimeNanos >= animation.durationNanos) scope.isRunning = false
                        if (DebugAnimationFrames) {
                            Log.d(
                                "UnscaledAnim",
                                "frame=$frameTimeNanos play=$playTimeNanos value=$value " +
                                    "vel=$velocity wall=${System.nanoTime()}",
                            )
                        }
                        scope.onFrame()
                    }
                }
            } finally {
                if (generation == myGeneration) {
                    isRunning = false
                    runningJob = null
                }
            }
        }
    }

    @Stable
    class FrameScope internal constructor(private val animatable: UnscaledAnimatable) {
        val value: Float
            get() = animatable.value

        val velocity: Float
            get() = animatable.velocity

        var lastFrameTimeNanos: Long = AnimationConstants.UnspecifiedTime
            internal set

        var startTimeNanos: Long = AnimationConstants.UnspecifiedTime
            internal set

        /** 动画真正走过的时间 (FrameClock 逐帧累加, 丢掉的异常间隔不算)。 */
        var playTimeNanos: Long = 0L
            internal set

        var isRunning: Boolean = true
            internal set

        fun cancelAnimation() {
            isRunning = false
        }
    }
}

private interface FloatAnimation {
    val durationNanos: Long

    val initialVelocity: Float

    fun valueAt(playTimeNanos: Long): Float

    fun velocityAt(playTimeNanos: Long): Float
}

private class TargetFloatAnimation(
    private val spec: VectorizedAnimationSpec<AnimationVector1D>,
    initialValue: Float,
    private val targetValue: Float,
    override val initialVelocity: Float,
) : FloatAnimation {
    private val initial = AnimationVector(initialValue)
    private val target = AnimationVector(targetValue)
    private val velocity = AnimationVector(initialVelocity)

    override val durationNanos: Long =
        if (spec.isInfinite) Long.MAX_VALUE
        else spec.getDurationNanos(initial, target, velocity)

    override fun valueAt(playTimeNanos: Long): Float =
        if (playTimeNanos >= durationNanos) targetValue
        else spec.getValueFromNanos(playTimeNanos, initial, target, velocity).value

    override fun velocityAt(playTimeNanos: Long): Float =
        if (playTimeNanos >= durationNanos) spec.getEndVelocity(initial, target, velocity).value
        else spec.getVelocityFromNanos(playTimeNanos, initial, target, velocity).value
}

private class DecayFloatAnimation(
    private val spec: VectorizedDecayAnimationSpec<AnimationVector1D>,
    initialValue: Float,
    override val initialVelocity: Float,
) : FloatAnimation {
    private val initial = AnimationVector(initialValue)
    private val velocity = AnimationVector(initialVelocity)

    override val durationNanos: Long = spec.getDurationNanos(initial, velocity)

    private val targetValue: Float = spec.getTargetValue(initial, velocity).value

    private val endVelocity: Float =
        spec.getVelocityFromNanos(durationNanos, initial, velocity)
            .value
            .coerceIn(-spec.absVelocityThreshold, spec.absVelocityThreshold)

    override fun valueAt(playTimeNanos: Long): Float =
        if (playTimeNanos >= durationNanos) targetValue
        else spec.getValueFromNanos(playTimeNanos, initial, velocity).value

    override fun velocityAt(playTimeNanos: Long): Float =
        if (playTimeNanos >= durationNanos) endVelocity
        else spec.getVelocityFromNanos(playTimeNanos, initial, velocity).value
}
