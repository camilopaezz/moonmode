package dev.camilo.st2mode.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private val SegmentInnerCorner = CornerSize(4.dp)

/** Outer corners take the theme's large shape; inner corners stay tight so the rows read as one group. */
@Composable
internal fun segmentShape(index: Int, count: Int): Shape {
    val outer = MaterialTheme.shapes.large
    val first = index == 0
    val last = index == count - 1
    return RoundedCornerShape(
        topStart = if (first) outer.topStart else SegmentInnerCorner,
        topEnd = if (first) outer.topEnd else SegmentInnerCorner,
        bottomEnd = if (last) outer.bottomEnd else SegmentInnerCorner,
        bottomStart = if (last) outer.bottomStart else SegmentInnerCorner,
    )
}

/** Section label above a group: inset from the group edge, 24 dp above and 8 dp below. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = SegmentGroupGap, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun SegmentedGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SegmentGap), content = content)
}

/**
 * One row of a segmented group. [interaction] carries the row's single accessible control
 * (`clickable`, `selectable`, or `toggleable`) so the whole segment is the target.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SegmentedRow(
    index: Int,
    count: Int,
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supporting: String? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    interaction: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    // Animate the selection, not the colors, so a theme change recolors rows in step with the page.
    val selection by animateFloatAsState(
        if (selected) 1f else 0f, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "segmentSelection",
    )
    val container = lerp(colors.surfaceContainer, colors.secondaryContainer, selection)
    val content = lerp(colors.onSurface, colors.onSecondaryContainer, selection)
    val secondary = lerp(colors.onSurfaceVariant, colors.onSecondaryContainer, selection)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = segmentShape(index, count),
        color = container,
        contentColor = content,
    ) {
        Row(
            Modifier
                .then(interaction)
                .heightIn(min = if (supporting != null) 72.dp else 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .alpha(if (enabled) 1f else DisabledContentAlpha),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (icon != null) {
                Icon(
                    icon, contentDescription = null, modifier = Modifier.size(24.dp),
                    tint = secondary,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (supporting != null) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondary,
                    )
                }
            }
            trailing?.invoke()
        }
    }
}
