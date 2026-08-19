package build.wallet.ui.app.loading

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment.Companion.Center
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import build.wallet.statemachine.core.SplashBodyModel

/**
 * FORK: the stock splash animates Block's Bitkey logo mark and word mark, with a
 * per-letter cascade.
 *
 * This is an unofficial third-party build. Presenting it under Block's branding
 * would misrepresent where it came from, so it shows a plain word mark instead.
 * The black background is kept so the transition into the app is unchanged.
 *
 * [model]'s animation timings are simply unused here rather than removed — the
 * model is shared with other callers.
 */
@Composable
fun SplashScreen(
  modifier: Modifier = Modifier,
  model: SplashBodyModel,
) {
  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black),
    contentAlignment = Center
  ) {
    BasicText(
      text = "Cosigner",
      style = TextStyle(
        color = Color.White,
        fontSize = 28.sp,
        letterSpacing = 2.sp
      )
    )
  }
}
