package app.olo.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The family's no-pill rule: Material3 buttons default to a stadium shape and do
 * not read it from the theme, so every button goes through these wrappers with
 * shapes.small (10dp). A bare Button would regress to a pill.
 */

@Composable
fun OloButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = MaterialTheme.shapes.small) { Text(text) }
}

@Composable
fun OloOutlinedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = MaterialTheme.shapes.small) { Text(text) }
}

@Composable
fun OloTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = if (color == Color.Unspecified) ButtonDefaults.textButtonColors() else ButtonDefaults.textButtonColors(contentColor = color),
    ) { Text(text) }
}

/** A rounded-square colour tile, the family's way of marking a kind. */
@Composable
fun Tile(color: Color, size: Dp = 12.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(color, MaterialTheme.shapes.extraSmall))
}
