package app.nudge.core.ui.text

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/** Text produced by ViewModels without a Context (05 §12). */
sealed interface UiText {
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    data class Plural(@param:PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    data class Raw(val value: String) : UiText

    @Composable
    fun resolve(): String = when (this) {
        is Res -> stringResource(id, *args.toTypedArray())
        is Plural -> pluralStringResource(id, count, *args.toTypedArray())
        is Raw -> value
    }

    fun resolve(context: android.content.Context): String = when (this) {
        is Res -> context.getString(id, *args.toTypedArray())
        is Plural -> context.resources.getQuantityString(id, count, *args.toTypedArray())
        is Raw -> value
    }
}
