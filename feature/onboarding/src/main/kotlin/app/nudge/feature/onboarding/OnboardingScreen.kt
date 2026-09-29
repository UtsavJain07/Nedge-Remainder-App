package app.nudge.feature.onboarding

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlarmOn
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.nudge.core.designsystem.component.AnimatedCheckMark
import app.nudge.core.designsystem.component.PriorityFlag
import app.nudge.core.designsystem.component.priorityLabel
import app.nudge.core.designsystem.theme.EmphasizedType
import app.nudge.core.designsystem.theme.centeredMaxWidth
import app.nudge.core.designsystem.theme.LocalNudgeColors
import app.nudge.core.designsystem.theme.LocalReducedMotion
import app.nudge.core.designsystem.theme.Spacing
import app.nudge.core.designsystem.theme.decorative
import app.nudge.core.model.Priority
import app.nudge.core.ui.nav.LocalNavAnimatedVisibilityScope
import app.nudge.core.ui.snackbar.LocalSnackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data object OnboardingRoute

fun NavGraphBuilder.onboardingScreen(onDone: () -> Unit) {
    composable<OnboardingRoute> {
        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
            OnboardingScreen(onDone = onDone)
        }
    }
}

private const val PAGE_COUNT = 3

/** Three-page onboarding: welcome, how nudges work, permissions (FR-90, 03 §3.1, A20). */
@Composable
fun OnboardingScreen(onDone: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val pager = rememberPagerState { PAGE_COUNT }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == PAGE_COUNT - 1

    Scaffold(snackbarHost = { SnackbarHost(LocalSnackbar.current.hostState) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = Spacing.s)) {
                if (!last) {
                    TextButton(
                        onClick = { scope.launch { pager.animateScrollToPage(PAGE_COUNT - 1) } },
                        modifier = Modifier.align(Alignment.CenterEnd).testTag("onboarding_skip"),
                    ) { Text(stringResource(R.string.onboarding_skip)) }
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> HowItWorksPage()
                    else -> PermissionsPage()
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PageIndicator(current = pager.currentPage, count = PAGE_COUNT)
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        if (last) {
                            viewModel.finish(onDone)
                        } else {
                            scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("onboarding_primary"),
                ) {
                    AnimatedContent(last, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "cta") { isLast ->
                        Text(stringResource(if (isLast) R.string.onboarding_start else R.string.onboarding_next))
                    }
                }
            }
        }
    }
}

/** Expressive page indicator: dots that stretch into a pill for the current page (04). */
@Composable
private fun PageIndicator(current: Int, count: Int) {
    val reduced = LocalReducedMotion.current
    val description = stringResource(R.string.onboarding_page_of, current + 1, count)
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        repeat(count) { i ->
            val selected = i == current
            val width by animateDpAsState(
                if (selected) 24.dp else 8.dp,
                decorative(reduced, spring(dampingRatio = 0.6f, stiffness = 500f)),
                label = "dot",
            )
            Box(
                Modifier
                    .size(width = width, height = 8.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

@Composable
private fun PageLayout(title: String, body: String?, illustration: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .centeredMaxWidth(560.dp)
            .padding(horizontal = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        illustration()
        Spacer(Modifier.height(Spacing.xl))
        Text(title, style = EmphasizedType.headlineMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(Spacing.m))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/** Page 1 (A20): three task cards drop in with SpringBouncy, 120 ms apart, then a check draws in. */
@Composable
private fun WelcomePage() {
    PageLayout(stringResource(R.string.onboarding_welcome_title), stringResource(R.string.onboarding_welcome_body)) {
        val reduced = LocalReducedMotion.current
        val drops = remember { List(3) { Animatable(if (reduced) 1f else 0f) } }
        LaunchedEffect(Unit) {
            drops.forEachIndexed { i, a ->
                launch {
                    delay(i * 120L)
                    a.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 500f))
                }
            }
        }
        val nudge = LocalNudgeColors.current
        val accents = listOf(nudge.low.color, nudge.high.color, nudge.urgent.color)
        val card = MaterialTheme.colorScheme.surfaceContainerHigh
        val line = MaterialTheme.colorScheme.outlineVariant
        val drop = with(LocalDensity.current) { 120.dp.toPx() }
        Box(Modifier.size(width = 240.dp, height = 200.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                // Back to front: the front card sits lowest.
                drops.forEachIndexed { i, a ->
                    val t = a.value
                    val top = size.height * (0.08f + 0.26f * i) - (1f - t) * drop
                    drawTaskCard(Offset(size.width * 0.06f, top), Size(size.width * 0.88f, size.height * 0.36f), card, accents[i], line, alpha = t.coerceIn(0f, 1f))
                }
            }
            AnimatedCheckMark(
                color = nudge.low.color,
                delayMillis = if (reduced) 0 else 3 * 120 + 250,
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = (-4).dp, y = 4.dp).size(48.dp),
            )
        }
    }
}

private fun DrawScope.drawTaskCard(topLeft: Offset, size: Size, container: Color, accent: Color, line: Color, alpha: Float) {
    val radius = CornerRadius(size.height * 0.28f)
    drawRoundRect(container, topLeft, size, radius, alpha = alpha)
    val r = size.height * 0.18f
    val c = Offset(topLeft.x + size.height * 0.45f, topLeft.y + size.height / 2)
    drawCircle(accent, radius = r, center = c, style = Stroke(size.height * 0.06f), alpha = alpha)
    val x = c.x + r * 2f
    val stroke = size.height * 0.1f
    drawLine(line, Offset(x, c.y - size.height * 0.1f), Offset(topLeft.x + size.width * 0.8f, c.y - size.height * 0.1f), stroke, alpha = alpha, cap = StrokeCap.Round)
    drawLine(line, Offset(x, c.y + size.height * 0.14f), Offset(topLeft.x + size.width * 0.55f, c.y + size.height * 0.14f), stroke, alpha = alpha, cap = StrokeCap.Round)
}

/** Page 2: priority → cadence rows animate in one after another. */
@Composable
private fun HowItWorksPage() {
    val rows = listOf(
        Priority.URGENT to R.string.onboarding_cadence_urgent,
        Priority.HIGH to R.string.onboarding_cadence_high,
        Priority.MEDIUM to R.string.onboarding_cadence_medium,
        Priority.LOW to R.string.onboarding_cadence_low,
    )
    PageLayout(stringResource(R.string.onboarding_how_title), stringResource(R.string.onboarding_how_caption)) {
        val reduced = LocalReducedMotion.current
        val shift = with(LocalDensity.current) { 48.dp.toPx() }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()) {
            rows.forEachIndexed { i, (priority, cadence) ->
                val p = remember { Animatable(if (reduced) 1f else 0f) }
                LaunchedEffect(Unit) {
                    delay(150L + i * 120L)
                    p.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 500f))
                }
                Surface(
                    color = LocalNudgeColors.current.forPriority(priority).container,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = p.value.coerceIn(0f, 1f)
                            translationX = (1f - p.value) * shift
                        },
                ) {
                    Row(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                        PriorityFlag(priority, size = 20.dp)
                        Spacer(Modifier.width(Spacing.m))
                        Text(
                            stringResource(R.string.onboarding_cadence_row, priorityLabel(priority), stringResource(cadence)),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/** Page 3: notification + exact-alarm permission cards; each turns into a green ✓ when granted. */
@Composable
private fun PermissionsPage() {
    val context = LocalContext.current
    var notificationsOn by remember { mutableStateOf(notificationsEnabled(context)) }
    var exactOn by remember { mutableStateOf(exactAlarmsAllowed(context)) }
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        notificationsOn = notificationsEnabled(context)
        exactOn = exactAlarmsAllowed(context)
        onPauseOrDispose { }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        askedNotifications = true
        notificationsOn = notificationsEnabled(context)
    }

    PageLayout(stringResource(R.string.onboarding_permissions_title), body = null) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m), modifier = Modifier.fillMaxWidth()) {
            PermissionCard(
                icon = { Icon(Icons.Rounded.Notifications, contentDescription = null) },
                title = stringResource(R.string.onboarding_notifications_title),
                body = stringResource(R.string.onboarding_notifications_body),
                granted = notificationsOn,
                onAllow = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedNotifications) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        // Permanently denied (or pre-33 with notifications switched off): system settings.
                        context.startActivity(appNotificationSettings(context))
                    }
                },
                testTag = "onboarding_allow_notifications",
            )
            PermissionCard(
                icon = { Icon(Icons.Rounded.AlarmOn, contentDescription = null) },
                title = stringResource(R.string.onboarding_exact_title),
                body = stringResource(R.string.onboarding_exact_body),
                granted = exactOn,
                onAllow = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                testTag = "onboarding_allow_exact",
            )
        }
    }
}

@Composable
private fun PermissionCard(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    granted: Boolean,
    onAllow: () -> Unit,
    testTag: String,
) {
    val success = LocalNudgeColors.current.low
    Surface(
        color = if (granted) success.container else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(Spacing.s))
            AnimatedContent(granted, transitionSpec = { scaleIn() + fadeIn() togetherWith fadeOut() }, label = "granted") { isGranted ->
                if (isGranted) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = stringResource(R.string.onboarding_granted),
                        tint = success.color,
                        modifier = Modifier.size(48.dp).padding(Spacing.s),
                    )
                } else {
                    FilledTonalButton(onClick = onAllow, modifier = Modifier.heightIn(min = 48.dp).testTag(testTag)) {
                        Text(stringResource(R.string.onboarding_allow))
                    }
                }
            }
        }
    }
}

private fun notificationsEnabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun exactAlarmsAllowed(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() != false

private fun appNotificationSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
