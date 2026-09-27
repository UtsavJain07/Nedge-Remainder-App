# 04 — Design System & Motion

The look is **Material 3 Expressive**: bold rounded shapes, springy motion, generous spacing, and color that comes from each list. Reference feel: Google Tasks' clarity + Apple Reminders' colored list cards + Things 3's satisfying completion.

All tokens live in `:core:designsystem`. Screens **never** hardcode colors, dp values or durations. They use `NudgeTheme.*` / `MaterialTheme.*` tokens.

---

## 1. Theme setup

```kotlin
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NudgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,          // Settings.dynamicColor
    pureBlack: Boolean = false,             // Settings.pureBlack
    seedColor: Color? = null,               // list color → tints screen (List screen only)
    content: @Composable () -> Unit,
) {
    val scheme = when {
        seedColor != null -> rememberDynamicColorScheme(seedColor, darkTheme, style = PaletteStyle.TonalSpot) // materialkolor
        dynamicColor && Build.VERSION.SDK_INT >= 31 ->
            if (darkTheme) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        else -> if (darkTheme) NudgeDarkScheme else NudgeLightScheme
    }.let { if (darkTheme && pureBlack) it.copy(surface = Color.Black, background = Color.Black) else it }

    MaterialExpressiveTheme(
        colorScheme = scheme,
        typography = NudgeTypography,
        shapes = NudgeShapes,
        motionScheme = MotionScheme.expressive(),
    ) {
        CompositionLocalProvider(LocalNudgeColors provides nudgeExtendedColors(darkTheme)) { content() }
    }
}
```

- The **List screen** wraps its content in `NudgeTheme(seedColor = list.color)`, so the top bar, FAB, slider and checkbox fills all take the list's hue. Animate between seeds when the pager changes (use `animateColorAsState` on the key scheme roles: `primary`, `primaryContainer`, `onPrimaryContainer`, `surfaceContainer`; tween 300 ms).
- Library for seed schemes: `com.materialkolor:material-kolor` (latest stable). If it's unavailable, generate a scheme with `ColorScheme` manual tonal mapping (primary = seed, primaryContainer = seed blended 80% toward the surface).

## 2. Color

### 2.1 Base scheme (brand = Indigo "Nudge Blue")

Brand seed: `#4F5BD5`. Generate the full light/dark schemes with the Material Theme Builder from this seed (TonalSpot) and paste them as `NudgeLightScheme`/`NudgeDarkScheme`. Key values:

| Role | Light | Dark |
|------|-------|------|
| primary | `#4F5BD5` | `#BCC2FF` |
| onPrimary | `#FFFFFF` | `#1A2478` |
| primaryContainer | `#DFE0FF` | `#343FA6` |
| surface | `#FBF8FF` | `#131318` |
| surfaceContainer | `#EFEDF4` | `#1F1F25` |
| surfaceContainerHigh | `#E9E7EF` | `#2A292F` |
| onSurface | `#1B1B21` | `#E4E1E9` |
| onSurfaceVariant | `#46464F` | `#C7C5D0` |
| outlineVariant | `#C7C5D0` | `#46464F` |
| error | `#BA1A1A` | `#FFB4AB` |

### 2.2 Priority colors (extended scheme, `LocalNudgeColors`)

| Priority | Light | Dark | Container (light/dark) | Icon |
|----------|-------|------|------------------------|------|
| Urgent | `#E5383B` | `#FF8A80` | `#FFDAD6` / `#5C1414` | `Icons.Rounded.Flag` filled |
| High | `#F77F00` | `#FFB870` | `#FFE0C2` / `#5A3000` | Flag filled |
| Medium | `#2F80ED` | `#8AB8FF` | `#D6E6FF` / `#0F3566` | Flag filled |
| Low | `#2BA84A` | `#7DDB91` | `#D2F5D9` / `#0E4A1E` | Flag filled |
| None | `onSurfaceVariant` | `onSurfaceVariant` | — | no flag |

### 2.3 List color palette (12 presets, stored as ARGB `Int`)

| # | Name | Hex | # | Name | Hex |
|---|------|-----|---|------|-----|
| 1 | Indigo | `#5C6BC0` | 7 | Teal | `#26A69A` |
| 2 | Violet | `#8E6CEF` | 8 | Green | `#43A047` |
| 3 | Pink | `#EC407A` | 9 | Lime | `#9CCC65` |
| 4 | Red | `#EF5350` | 10 | Amber | `#FFB300` |
| 5 | Orange | `#FF7043` | 11 | Brown | `#8D6E63` |
| 6 | Cyan | `#29B6F6` | 12 | Slate | `#607D8B` |

The list card container = the list color at 16% alpha over `surfaceContainer` (light) or 24% (dark). Text on it = `onSurface`. The icon circle = the list color at 100% with white/black content, whichever has more contrast.

## 3. Typography

Font: **Google Sans Flex** if available through downloadable fonts; otherwise **Plus Jakarta Sans** (bundled, OFL) for display/headline/title and the system **Roboto Flex** for body. Scale:

| Style | Size / Line | Weight | Use |
|-------|-------------|--------|-----|
| displaySmall | 36/44 | 700 | Onboarding titles |
| headlineMedium | 28/36 | 700 | Home greeting |
| headlineSmall | 24/32 | 600 | Task Detail title, List top bar (expanded) |
| titleLarge | 22/28 | 600 | Top bars |
| titleMedium | 16/24 | 600 | List card name, section headers |
| bodyLarge | 16/24 | 400 | Task title |
| bodyMedium | 14/20 | 400 | Subtask title, notes |
| labelLarge | 14/20 | 600 | Buttons, chips |
| labelMedium | 12/16 | 600 | Progress %, counters |
| labelSmall | 11/16 | 500 | Meta line |

Use the emphasized variants (`MaterialTheme.typography.*Emphasized`, Expressive) for the Home greeting and the completion "All done!".

## 4. Shape

| Token | Value | Use |
|-------|-------|-----|
| extraSmall | 8 dp | Chips inner, progress bar ends |
| small | 12 dp | Task row container (when highlighted/dragged) |
| medium | 16 dp | Focus cards, menus |
| large | 24 dp | List cards |
| extraLarge | 32 dp | Bottom sheets (top corners), FAB (Expressive large FAB) |
| full | 50% | Checkbox, color swatches, avatar |

Expressive shape morph: the **checkbox** morphs from a circle (open) to a rounded "cookie/soft-burst" shape (completed) using `MaterialShapes` + `Morph` (`androidx.graphics:graphics-shapes`). If `MaterialShapes` isn't available, morph from a circle to a 30%-rounded square.

## 5. Spacing & layout

- Base grid 4 dp. Tokens: `xxs 2, xs 4, s 8, m 12, l 16, xl 24, xxl 32`.
- Screen horizontal padding: 16 dp. Card gap: 12 dp.
- Task row min height: 56 dp (top-level), 48 dp (subtask). Vertical padding 8 dp.
- Subtask indent: **40 dp**.
- Bottom sheet content padding: 24 dp horizontal.
- Edge-to-edge: `enableEdgeToEdge()`; apply `WindowInsets.safeDrawing` padding in `Scaffold`.

## 6. Motion

### 6.1 Motion tokens

Use `MaterialTheme.motionScheme` (Expressive) wherever possible:
- `motionScheme.defaultSpatialSpec()` for position/size changes (bouncy).
- `motionScheme.fastSpatialSpec()` for small elements (checkbox, chips).
- `motionScheme.defaultEffectsSpec()` for color and alpha (no overshoot).

Where a raw spec is needed:

| Token | Spec |
|-------|------|
| `SpringBouncy` | `spring(dampingRatio = 0.6f, stiffness = 500f)` |
| `SpringSnappy` | `spring(dampingRatio = 0.8f, stiffness = 800f)` |
| `SpringGentle` | `spring(dampingRatio = 1f, stiffness = 300f)` |
| `TweenFast` | `tween(150, easing = FastOutSlowInEasing)` |
| `TweenMedium` | `tween(300, easing = EmphasizedDecelerate)` (`CubicBezierEasing(0.05f,0.7f,0.1f,1f)`) |

### 6.2 Signature animations (must implement exactly)

| # | Animation | Spec |
|---|-----------|------|
| A1 | **Task complete** | 1) Checkbox: fill scales from 0→1 (`SpringBouncy`), the ring color transitions to the fill color, the check path draws with `PathMeasure` over 250 ms, and the shape morphs circle→soft-burst. 2) Particle burst: 8 dots in the priority color (or list color for None) fly outward 16–28 dp and fade (400 ms). 3) Title strikethrough: a line drawn left→right over 250 ms (`drawWithContent` + animated fraction), text alpha 1→0.5. 4) After a **600 ms** hold, the row is removed from the open section; `Modifier.animateItem()` collapses it (`fadeOutSpec = tween(200)`, `placementSpec = SpringGentle`) and it appears in Completed with a fade-in. Haptic `Confirm` at step 1. |
| A2 | **Un-complete** | Reverse: the check path retracts (150 ms), the fill scales down, the strike line retracts right→left, and the row animates back into place. |
| A3 | **Row insert** | `animateItem(fadeInSpec = tween(220), placementSpec = SpringBouncy)` plus an initial `graphicsLayer` scaleY 0.8→1 and translationY -12 dp→0 (`SpringBouncy`). |
| A4 | **Quick Add "fly"** | On Enter: a copy of the title text (captured in an overlay) scales 1→0.6, moves toward the top of the list, and fades out over 350 ms, while the new row inserts with A3. |
| A5 | **Drag lift** | On drag start: row elevation 0→8 dp, scale 1→1.03, container color → `surfaceContainerHighest`, corner 0→12 dp (all `SpringSnappy`). Other rows shift with `animateItem`. Drop: back to rest with `SpringBouncy`. |
| A6 | **Nest target** | While hovering the nest zone ≥ 250 ms: target row container → `primaryContainer` at 60%, scale 1.02, a 2 dp `primary` border, and a ghost placeholder (the dragged item's outline, 50% alpha, indented 40 dp) appears under the target's last child with `animateContentSize`. |
| A7 | **Nest reject** | Horizontal shake: translationX keyframes 0, -8, 8, -6, 6, -3, 0 over 360 ms + `Reject` haptic. |
| A8 | **List card → List screen** | `SharedTransitionLayout`: the card's container `sharedBounds(key="list-${id}")` becomes the top app bar area, the icon `sharedElement(key="list-icon-${id}")` flies to the top bar, and the name `sharedElement(key="list-name-${id}")` goes to the title. `boundsTransform = { _, _ -> spring(0.8f, 380f) }`. Screen content fades in (200 ms, 100 ms delay). |
| A9 | **FAB → Quick Add** | The FAB scales down to 0.9 on press (`SpringSnappy`). The sheet slides up with the default `ModalBottomSheet` motion, and the chips inside stagger in (each 30 ms later, alpha 0→1, translationY 8→0 dp). |
| A10 | **Progress** | Bar width `animateFloatAsState(SpringGentle)`. The ring sweep `animateFloatAsState(tween(600, EmphasizedDecelerate))`. The % text uses `AnimatedContent` with a vertical slide counter. |
| A11 | **Expand/collapse subtasks** | Chevron rotation 0→180° (`SpringSnappy`). Subtask rows enter/exit with `animateItem` + `expandVertically`/`shrinkVertically` (`SpringGentle`), staggered 20 ms per child. |
| A12 | **Completed section toggle** | The header chevron rotates. The items expand with `animateItem` (they're part of the same `LazyColumn`, keyed). |
| A13 | **Confetti** (list becomes all done) | Canvas overlay with 80 particles (rects and circles), colors from the list seed scheme, launched from the bottom center with random angle −60°..−120°, velocity 900–1600 dp/s, gravity 2200 dp/s², rotation, 1800 ms lifetime, fading in the last 400 ms. Driven by `withFrameNanos`. Plays once per transition to empty. |
| A14 | **Home greeting** | The header text uses `AnimatedContent` when the greeting changes. On first composition the greeting slides up 16 dp + fades (300 ms), then the Focus cards stagger in (40 ms each, `SpringBouncy`). |
| A15 | **List cards press** | Scale 0.96 on press (`SpringSnappy`) and back. The progress ring animates from 0 to its value on first appearance (A10). |
| A16 | **Pager color** | The top bar and FAB color animate between list seeds (300 ms tween). |
| A17 | **Snackbar** | Default M3 animation. |
| A18 | **Swipe background** | The icon scales 0.6→1.2 as the swipe progress crosses the 35% threshold (`SpringBouncy`, haptic tick at the threshold). |
| A19 | **Highlight pulse** (from search/notification) | The row background animates list color alpha 0→0.3→0 twice (2 × 600 ms). |
| A20 | **Onboarding illustrations** | Canvas cards drop in with `SpringBouncy`, staggered 120 ms. The check draws with `PathMeasure`. |

### 6.3 Screen transitions (Navigation)

- Default: `fadeIn(tween(200)) + slideInHorizontally { it / 10 }` / reverse on pop. Predictive back is supported (Navigation Compose handles it; sheets use `PredictiveBackHandler`).
- Home → List: shared element (A8).
- → Settings / Search: slide up 5% + fade.

### 6.4 Performance rules for animation

- Read animated values inside `graphicsLayer { }` / `drawBehind { }` lambdas (deferred reads) so they don't recompose.
- Give every `LazyColumn` item a stable `key` (task id) and a `contentType` (`"task"`, `"subtask"`, `"header"`).
- Confetti and particles draw on a single `Canvas`, not one composable per particle.
- Test with the Compose compiler reports and `Layout Inspector` recomposition counts. Budget: completing a task recomposes only that row and the section header.

### 6.5 Reduced motion

```kotlin
val reducedMotion = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
```
Provide it via `LocalReducedMotion`. When it is true: skip A4, A7 (still do the haptic), A13, A19 (use a static highlight for 1.5 s), and the particles in A1. Replace springs with `snap()` for decorative motion. Keep functional transitions (row move) as a 150 ms fade.

## 7. Components catalog (`:core:designsystem`)

| Component | Notes |
|-----------|-------|
| `NudgeCheckbox(checked, priority, onToggle)` | A1/A2 animations, 48 dp touch |
| `PriorityFlag(priority)` | |
| `PriorityChipRow(selected, onSelect, includeNone)` | FilterChips with priority colors |
| `CadenceChip / CadencePickerDialog` | |
| `ProgressBarThin(progress)`, `ProgressRing(progress, size, stroke)` | A10 |
| `ListCard(list, stats, onClick, modifier)` | A8 keys, A15 |
| `FocusCard(task, list)` | |
| `TaskRow(uiModel, callbacks, dragState)` | The central row; stateless |
| `SectionHeader(title, count, expanded, onToggle)` | |
| `ColorSwatchPicker(colors, selected)` | |
| `EmojiPicker(selected)` | |
| `EmptyState(illustration, title, body)` | Canvas illustrations |
| `ConfettiOverlay(trigger)` | A13 |
| `HealthBanner(issues, onFix)` | |
| `NudgeSnackbarHost` | |

Every component has `@Preview`s for light/dark, font scale 1.0/2.0, and each priority.

## 8. Iconography

Material Symbols Rounded (`androidx.compose.material:material-icons-extended`, or pick the vector drawables used and add them individually to keep the APK small). App icon: an adaptive icon, a rounded check mark inside a speech-bubble "nudge" shape, brand Indigo background, plus a monochrome layer for themed icons (Android 13+).
