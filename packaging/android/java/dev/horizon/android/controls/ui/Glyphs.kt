package dev.horizon.android.controls.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.horizon.android.R

enum class Glyph(val res: Int) {
    CLOSE(R.drawable.ic_ctrl_close),
    PAUSE(R.drawable.ic_ctrl_pause),
    EDIT(R.drawable.ic_ctrl_edit),
    LAYOUTS(R.drawable.ic_ctrl_layouts),
    SLIDERS(R.drawable.ic_ctrl_sliders),
    CHEVRON(R.drawable.ic_ctrl_chevron),
    BACK(R.drawable.ic_ctrl_back),
    CHECK(R.drawable.ic_ctrl_check),
    GEAR(R.drawable.ic_ctrl_gear),
    MENU(R.drawable.ic_ctrl_menu),
}

@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, modifier: Modifier = Modifier.size(20.dp)) {
    Image(
        painter = painterResource(glyph.res),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(tint),
    )
}
