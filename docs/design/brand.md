# Brand

The app follows Amp's own visual identity: a deep forest-green ground, a cream wordmark, and green-tinted neutrals. Every value below comes from ampcode.com on 2026-10-24. The sources are the app icon (`brand/ampcode-app-icon.svg`), the site manifest, and the theme tokens in `/_app/immutable/assets/app.bb1ab650fe7a1c84.css`. A screenshot of the homepage is in `brand/ampcode-home-2026-10-24.png`.

## Amp's identity, as measured

| Element | Value | Source |
|---|---|---|
| Icon ground | Vertical gradient `#2C3A2A` → `#13261C` (38%) → `#071C17` (68%) → `#061615` | `app-icon.svg` `linearGradient#background` |
| Icon glows | `#526044` at 45%, `#504829` at 20%, `#364F28` at 16%, radial | `app-icon.svg` |
| Wordmark | `#DFDFC1` (cream), one path, drop shadow `0 11 8` black at 50% | `app-icon.svg` |
| Monochrome icon | Provided for themed icons | `brand/ampcode-app-icon-monochrome-512.png` |
| Web app background, dark | `#0B0D0B` | `--background` |
| Web app text, dark | `#F6FFF5` | `--foreground` |
| Web app muted text, dark | `#9CA49C` | `--muted-foreground` |
| Web app secondary surface, dark | `#2F372F` | `--secondary` |
| Web app border | `#878B86` at 20% | `--border` |
| Success | `#54B879` | `--success` |
| Warning | `#D6A038` | `--warning` |
| Destructive | `#D67670` | `--destructive` |
| Link | `#6CA0E0` | `--link` |
| Mention | `oklch(70% .13 325)` | `--mention` |
| Diff added | background `#183323`, text `#4BA86E` | `--diff-addition-*` |
| Diff removed | background `#3B2221`, text `#D67670` | `--diff-removal-*` |
| Diff meta | `#D6A038` | `--diff-meta-foreground` |
| Editor | background `#171717`, text `#BABABA` | `--editor-*` |
| Syntax | keyword `#FF7AC6`, string `#F1FA89`, number `#BF95F9`, function `#75DBF0`, class `#52FA7C`, comment `#626DA7` | `--syntax-*` |
| Marketing call to action | `#F6833B` (orange) | homepage stylesheet |
| Fonts | Sagittaire Display, Berkeley Mono, Perfectly Nineties, system sans for body | `@font-face` rules |

## Decisions for the Android app

- **Color comes from one token table.** `:core:design` holds semantic tokens: surfaces, text, accent, status, diff, and syntax. No other module writes a color literal, and the build fails if one does.
- **The ground is Amp's web app.** Window, pane, and control colors are the signed-in web app's own tokens in its default theme. The table is in "Palette".
- **The wordmark is the real vector.** The sidebar header and the sign-in screen draw the path from `app-icon.svg`, not the word "amp" set in a font.
- **Fonts stay on the system.** Sagittaire Display, Berkeley Mono, and Perfectly Nineties are commercial fonts, so the app does not bundle them. Body text uses the system sans, which is what Amp's web app uses too, and code uses the system monospace.
- **Orange is not an accent.** It belongs to Amp's marketing call to action, not to the product, so the app does not use it.
- **The launcher icon is Amp's icon.** It is an adaptive icon with the gradient as the background layer, the wordmark as the foreground layer, and a monochrome layer for themed icons.

## Palette

On 2026-10-26 the app moved to the signed-in web app's colors in its default dark theme, Daydream. Before that it used palette C, Forest and signal: the icon's gradient stops as grounds (`#061615` canvas, `#0A1D18` pane) and a dulled success green, `#619E78`, for actions.

### What the web ships, 2026-10-26

The home page links `/_app/immutable/assets/app.16412b948ca0e0ea.css`. The signed-in shell's grounds are in a separate chunk, `/_app/immutable/assets/app-shell.5b906c4ee5a02648.css`, and the class strings that place them are in the JS chunks `chunks/532df6c0.js` (thread layout), `chunks/cf27bdbc.js` (sidebar), and `chunks/81c43144.js` (buttons). The table quotes each value with the string to search for.

| Web token or class | Dark value | Where, and the search string |
|---|---|---|
| Default theme | `theme-0`, "Daydream", "Faintly green, pleasantly elsewhere" | `chunks/78043217.js`, `` m=`theme-0` ``; the root `<html>` carries `data-amp-theme="theme-0"` |
| `--app-shell-background` | `#0b0d0b` | `app-shell.css`, `--app-shell-background:light-dark(oklch(92.971% .01478 132.48),#0b0d0b)` |
| `--app-surface-background` | `#0c1516` | `app-shell.css`, `--app-surface-background:light-dark(oklch(99% .01 131.32),#0c1516)` |
| `--app-pane-canvas-background` | `color-mix(in oklab, var(--app-shell-background) 60%, black)`, which is `#020302` | `app-shell.css`, `--app-pane-canvas-background` |
| `--app-pane-card-gap` | `2px` | `app-shell.css`, `--app-pane-card-gap:2px` |
| Pane cards | `md:rounded-lg` on `bg-[color:var(--app-surface-background)]` | `chunks/532df6c0.js`, `md:rounded-lg` |
| Sidebar | `--sidebar:var(--app-surface-background)` with `border-e` | `chunks/cf27bdbc.js`, `app-navigation-sidebar [--sidebar:var(--app-surface-background)]` |
| `--border` | `#878b861f`, which overrides the theme block's `#878b8633` | `app.css`, `:root{--border:#878b861f` |
| Pane resize handle | `w-px bg-border/70 after:z-10 md:bg-transparent` | `chunks/532df6c0.js`, `resizeHandleClass:` |
| `--app-shell-foreground` | `#ccccc9`; the shell sets `--foreground` to it | `app.css`, `--app-shell-foreground:light-dark(#0b0d0b,#ccccc9)`; `app-shell.css`, `--foreground:var(--app-shell-foreground)` |
| `--muted-foreground` | `#9ca49c` | `app.css`, `--muted-foreground:light-dark(#595959,#9ca49c)` |
| `--primary` | `color-mix(in srgb, var(--background) 5%, var(--foreground))`, which is `#c2c2c0` in the shell | `app.css`, `--primary:color-mix(in srgb` |
| `--primary-foreground` | `var(--background)`, `#0b0d0b` | `app.css`, `--primary-foreground:var(--background)` |
| Primary button | `bg-primary text-primary-foreground` | `chunks/81c43144.js`, `` default:`bg-primary text-primary-foreground `` |
| Ghost button | `text-foreground/80` | `chunks/81c43144.js`, `` ghost:`text-foreground/80 `` |
| `--accent` (hover and selection) | `#f6fff512` | `app.css`, `--accent:light-dark(#0b0d0b12,#f6fff512)` |
| `--ring` | `var(--foreground)` | `app.css`, `--ring:var(--foreground)` |
| `--secondary` | `#2f372f` | `app.css`, `--secondary:light-dark(#d6dcd6,#2f372f)` |
| Selector pill | `dark:bg-white/12`, `0 0 0 1px rgba(255,255,255,0.14)`, `inset 0 1px 0` glint `rgba(255,255,255,0.08)` | `chunks/cf27bdbc.js`, `inset_0_1px_0_var(--selector-glint)` |
| `--success` | `#54b879` | `app.css`, `--success:light-dark(#2f8052,#54b879)` |
| `--warning`, `--destructive`, `--link`, `--mention` | `#d6a038`, `#d67670`, `#6ca0e0`, `oklch(70% .13 325)` | `app.css`, each `--<name>:light-dark(` |
| Diff | added `#183323` on `#4ba86e`, removed `#3b2221` on `#d67670`, meta `#d6a038` | `app.css`, `--diff-addition-background` and its siblings |
| Editor and syntax | unchanged from the 2026-10-24 table above | `app.css`, `--editor-background`, `--syntax-keyword` |

Where the forest green comes from: no surface is saturated. The shell ground `#0b0d0b` is a near-neutral black with a green cast (green channel 13 against red and blue 11). The pane surface `#0c1516` is a dark teal (red 12, green 21, blue 22; Oklch hue 205°, chroma 0.014). The muted text `#9ca49c` and the border base `#878b86` are green grays. The old app's grounds were more saturated (`#0A1D18`, chroma 0.027), which is why the web reads calmer.

The light edge beside a divider is, as far as the CSS shows, the sidebar's `border-e`: a 1px line of `--border` over the sidebar's surface, sitting just left of the 2px pane-canvas gutter. Over `#0c1516` that line is `#1b2324`. A dark docs screenshot, `https://static.ampcode.com/docs/desktop-android-development.png`, shows the older 1px `bg-border/70` handle between the thread and a pane, measured at `#181f20`, which is that border at 70% over the surface. No box-shadow or gradient draws a white line beside a divider. The search covered `inset 1px 0` and every `box-shadow` in the CSS and the JS class strings.

### In the app

The token table is `core/design/src/main/kotlin/com/agentifiers/amp/design/theme/`. `AmpPalette.kt` defines the groups, `Forest.kt` holds the values, and `SurfaceRoles.kt` says which ground each part of the window uses. Composables read `AmpTheme.colors`. The build fails on a color literal anywhere else. `Forest.kt` writes a `color-mix(in oklab, …)` as `lerp`, which mixes in Oklab, and a translucent web color over a ground as `compositeOver`.

| App token | Value | Web source, or how it is derived |
|---|---|---|
| canvas | `#020302` | `--app-pane-canvas-background`. The window bar, the system bars, and the gutters between panes. |
| pane | `#0C1516` | `--app-surface-background`. The Sidebar, Thread, and machine panes all sit on it, as the web's sidebar and pane cards do. |
| raised | `#1D2626` | `--accent` `#f6fff512` over the surface. Selected rows, fields, the composer, and user messages. The web composer is `bg-primary/[0.12]`, `#222A2A`; the app keeps one raised ground. |
| overlay | `#2F372F` | `--secondary`. Menus, gate cards, and Secondary buttons. The web's popover is the surface plus a border and shadow; on the app's single pane ground a menu would vanish, so menus take the web's secondary surface. |
| control | `#2A3132` | The selector pill's `dark:bg-white/12` over the surface. Chips and pickers. |
| line | `#1B2324` | `--border` `#878b861f` over the surface. Dividers, borders, and the sidebar edge. |
| rim, glint | white at 14% and 8% | The selector pill's ring and `inset 0 1px 0` glint. |
| scrim | canvas at 60% | Derived; the web has no scrim token. |
| ink | `#CCCCC9` | `--app-shell-foreground`. |
| inkMuted | `#9CA49C` | `--muted-foreground`. |
| inkFaint | `#5E635E` | Derived: inkMuted 35% toward canvas in Oklab. |
| onAccent | `#0B0D0B` | `--primary-foreground`. |
| accent | `#C2C2C0` | `--primary`. Primary buttons, the send button, selected tabs, and a row's call to action. The web's ghost button is foreground at 80%; the app's Tertiary rank keeps the accent. |
| accentMuted | `#1D2626` | `--accent` over the surface. Selected chips, the cover nav pill, search highlights. |
| focus | `#CCCCC9` | `--ring`, the foreground. |
| brandMark | `#DFDFC1` | The icon's cream wordmark. |
| success, green thread color | `#54B879` | `--success`. |
| working, warning, diff meta | `#D6A038` | `--warning`. |
| needsYou, error, danger, removed diff text | `#D67670` | `--destructive`. |
| done, link | `#6CA0E0` | `--link`. |
| mention | `#C781CB` | `--mention`, converted from Oklch. |
| idle | `#8E968E` | Derived: a green gray at 5.1:1 or more on every raised ground, because the status word takes the dot's color. |
| diff added | background `#183323`, text `#4BA86E` | `--diff-addition-*`. |
| diff removed | background `#3B2221`, text `#D67670` | `--diff-removal-*`. |
| strong diff backgrounds | each diff background 25% toward its text | Derived. |

The 16 terminal colors use the destructive, success, warning, link, and mention colors, the syntax function color, and editor text. Black is a green gray, `#5C665C`, so it stays readable on the canvas. Each bright color is its base 35% toward ink.

The panes keep no borders and no rounded corners, so no width moves off the creases. Each crease gets the web's divider as an overlay: a 2dp canvas gutter (`--app-pane-card-gap`) centered on the crease, and where the Sidebar is the left pane, its 1dp `line` border just left of the gutter. The divider grips keep the app's own pill, because the web's handle draws nothing until hovered.

Contrast, computed with the WCAG 2.1 formula and asserted in `PaletteContrastTest`: `#CCCCC9` on `#0C1516` is 11.50:1, `#C2C2C0` on `#0C1516` is 10.38:1, `#0B0D0B` on `#C2C2C0` is 10.93:1, `#4BA86E` on `#183323` is 4.64:1, and the cream on the canvas is 15.2:1. The lowest text pairing the test checks is danger on raised, 4.92:1. Every web value passed, so no token deviates for contrast.

## Components

Controls come from `:core:design` too: `AmpButton` in five ranks, `AmpIconButton`, `AmpChip`, `AmpPaneHeader`, `AmpListRow`, `AmpSectionLabel`, `AmpSearchField`, `AmpTextField`, `AmpTabRow`, `AmpInputBar`, `AmpDropdownMenu`, and `AmpEmptyState`. The ranks are Primary, Secondary, Tertiary, Link, and Destructive. Link is for a navigation link or a disclosure toggle, such as Open on Web or the subthreads toggle. It draws inkMuted text with a chevron, so the accent stays on the primary action, the selection, and a row's call to action, which is Tertiary. Controls share a 6dp corner, Amp's web `rounded-md`, and a 48dp touch target around a smaller visual. Icons are Lucide, the set Amp's web app draws, generated into `AmpIcons` from a pinned `lucide-static` release.

## Type and sizes

Every size starts from direction A2 of the density prototype. A2 is the compact direction A with two values from C: the section label and the pane surfaces. Where A2 drew a tappable row or control smaller than 48dp, the touch rule below wins. The tables live in `core/design/src/main/kotlin/com/agentifiers/amp/design/theme/`. `AmpType.kt` holds the type roles. `AmpMetrics.kt` holds `AmpMetrics` for component sizes, `AmpSpacing` for gaps, and `AmpShapes` for corners. `SurfaceRoles.kt` says which Forest surface each part of the window sits on. Composables read them as `AmpTheme.type`, `AmpTheme.metrics`, `AmpTheme.spacing`, `AmpTheme.shapes`, and `AmpTheme.surfaceRoles`.

Pick a type role by what the text is, not by the size you want. A thread row's title is `rowTitle` even though `body` has the same size. The text is the system sans (Roboto), and `mono` is the system monospace. Sizes are sp, so the user's font scale applies, and every text container takes a minimum height, never a fixed one.

| Role | Size/line, weight | For |
|---|---|---|
| threadTitle | 15/20 w600 | The open thread's title |
| paneTitle | 15/20 w600 | A pane, sheet, or dialog title |
| body | 14/20 w400 | Prose, menu items, field text, empty states |
| rowTitle | 14/20 w500 | The title line of a list row |
| secondary | 13/18 w400 | Supporting text, hints, errors under a field |
| label | 12/16 w500 | Chip text, captions, badges |
| meta | 12/16 w400 | Ages, counts, a row's second line |
| section | 11/16 w600, +0.08em | Group headings, drawn uppercase |
| mono | 13/18 w400 | Code, commands, paths |
| monoSmall | 12/16 w400 | A tool call's arguments, a Portal URL, a notice's JSON lines. Not in A2 |
| tab | 13/18 w500 | Tab labels |
| button | 13/18 w500 | Button labels |
| nav | 12/16 w500 | Bottom navigation labels |
| thinking | 13/18 w400 italic | The agent's thinking |
| tool | 13/18 w500 | A tool call's name |
| toolMeta | 12/16 w400 | A tool call's counts and status |
| headline | 20/28 w600 | The heading of a full-screen flow, such as sign-in or the lock screen. Not in A2 |
| heading1, heading2, heading3 | 16/22, 15/20, 14/20 w600 | Markdown headings. Not in A2 |
| deviceCode | mono 32/40 w600, +0.15em | The sign-in code. Not in A2 |

Material's own roles resolve to these, so a stock component matches: displayLarge to headlineSmall are `headline`, titleLarge is `threadTitle`, titleMedium is `paneTitle`, titleSmall is `tab`, bodyLarge and bodyMedium are `body`, bodySmall is `secondary`, labelLarge is `body` (menu items), labelMedium is `label`, and labelSmall is `meta`.

Component sizes in dp: window bar 40 with 48 by 40 targets, pane header 48 with the title's baseline 21 below its top, list row 52, section label 32, search field 36, text field 40 (a minimum, so multi-line text grows it) with 12dp sides, tab row 40 with a 2dp indicator, icon tab 48, button and icon button 32 inside a 48 target, icons 18 and 14, chip 24, input bar 48, bottom navigation 56, action bar 48. Every row a user taps is at least 48 tall: Changes file rows, Files tree rows, tool rows, queued messages, and choice rows. Lines nobody taps, such as a loading line in the file tree, keep the 36dp file row. Corners: 6 for controls, rows, and the search field, 12 for chips, 8 for a user message, and 24 for the input bar. Panes have no borders. Every pane sits on `pane`, and the creases carry the web's divider described in "Palette".

Every tappable row or control has its own 48dp touch target, and no two targets overlap. Icon buttons keep a 48dp pitch, so a row of them never packs tighter than one target each. When a header or toolbar runs out of room, controls move into its **More Actions** instead of shrinking. A list row pads 8dp at its start, and a row with no trailing control pads 8dp at its end too.

The build enforces this. `ampSourceBans` fails on an `N.sp` literal, a `fontSize =`, `lineHeight =`, or `letterSpacing =` argument, a `TextStyle(` construction, or `MaterialTheme.typography` anywhere outside the `theme/` folder and tests. The one exception is the home screen widget's `WidgetType.kt`, which converts the roles to Glance's own text style. `scripts/dp-budget.sh`, run by every `./gradlew test`, fails when raw `N.dp` literals in `feature/` and `app/` exceed the budget in `scripts/dp-budget.txt`. It also fails on a `TextField(`, `OutlinedTextField(`, or `BasicTextField(` call, or a secure variant of one, outside `:core:design`. Forms use `AmpTextField` or `AmpSecureTextField`, search boxes `AmpSearchField`, and chat-style inputs `AmpInputBar`. The desktop pane's invisible keyboard sink, `DesktopToolbar.kt`, is the one exception. `scripts/check-alignment.sh <serial>` checks on an emulator that every pane header lines up.

## Using Amp's marks

The wordmark and the icon belong to Amp. The app is unofficial and is not affiliated with or endorsed by Amp.
