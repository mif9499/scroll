package scroll.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import scroll.rememberCupertinoFlingBehavior
import scroll.rememberCupertinoOverscrollEffect
import kotlin.random.Random

private const val ItemCount = 30

private val CardHeight = 96.dp
private val CardCorner = 24.dp

private const val CardSaturation = 0.8f
private const val CardLightness = 0.68f
private const val HueStepPerItem = 15f

private fun cardColor(index: Int, hueOffset: Float): Color =
    Color.hsl(
        hue = (hueOffset + index * HueStepPerItem) % 360f,
        saturation = CardSaturation,
        lightness = CardLightness,
    )

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Main()
        }
    }
}

@Composable
fun Main() {
    val listState = rememberLazyListState()
    val hueOffset = remember { Random.nextFloat() * 360f }
    val bars = WindowInsets.systemBars.asPaddingValues()
    val contentPadding = PaddingValues(
        start = 20.dp,
        end = 20.dp,
        top = bars.calculateTopPadding(),
        bottom = bars.calculateBottomPadding(),
    )
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF2F2F7)),
        state = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        flingBehavior = rememberCupertinoFlingBehavior(),
        overscrollEffect = rememberCupertinoOverscrollEffect(),
    ) {
        items(count = ItemCount, key = { it }) { index ->
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CardHeight)
                    .clip(RoundedRectangle(CardCorner))
                    .background(cardColor(index, hueOffset)),
            )
        }
    }
}
