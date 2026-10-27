package com.agentifiers.amp.design.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp

/**
 * Amp's signed-in web app in its default dark theme, Daydream, as measured on 2026-10-26 (docs/design/brand.md
 * "Palette"). CSS `color-mix(in oklab, …)` is [lerp], which mixes in Oklab; a translucent CSS color over a ground is
 * [compositeOver]. A token the web lacks is a mix of measured ones.
 */
val Forest: AmpPalette = run {
    val shell = Color(0xFF0B0D0B)
    val surface = Color(0xFF0C1516)
    // --app-pane-canvas-background: the ground under the pane cards, which shows as the gutter between them.
    val paneCanvas = lerp(shell, Color.Black, 0.4f)
    val ink = Color(0xFFCCCCC9)
    val inkMuted = Color(0xFF9CA49C)
    val cream = Color(0xFFDFDFC1)
    // --primary: color-mix(in srgb, var(--background) 5%, var(--foreground)) inside the app shell, as a browser rounds it.
    val primary = Color(0xFFC2C2C0)
    val selection = Color(0x12F6FFF5).compositeOver(surface)

    val success = Color(0xFF54B879)
    val warning = Color(0xFFD6A038)
    val destructive = Color(0xFFD67670)
    val link = Color(0xFF6CA0E0)
    val mention = Color(0xFFC781CB)

    val syntax = SyntaxTokens(
        keyword = Color(0xFFFF7AC6),
        string = Color(0xFFF1FA89),
        number = Color(0xFFBF95F9),
        function = Color(0xFF75DBF0),
        type = Color(0xFF52FA7C),
        comment = Color(0xFF626DA7),
    )

    val addedBackground = Color(0xFF183323)
    val addedText = Color(0xFF4BA86E)
    val removedBackground = Color(0xFF3B2221)

    // Black is lifted to a green gray so it stays readable on the dark canvas.
    val ansiNormal = listOf(Color(0xFF5C665C), destructive, success, warning, link, mention, syntax.function, Color(0xFFBABABA))
    val ansiBright = listOf(inkMuted) + ansiNormal.subList(1, 7).map { lerp(it, ink, 0.35f) } + ink

    AmpPalette(
        surfaces = SurfaceTokens(
            canvas = paneCanvas,
            pane = surface,
            raised = selection,
            overlay = Color(0xFF2F372F),
            // The web's composer selector pill: white at 12% fill, a 1px ring of white at 14%, a 1px top glint at 8%.
            control = Color(0x1FFFFFFF).compositeOver(surface),
            line = Color(0x1F878B86).compositeOver(surface),
            rim = Color(0x24FFFFFF),
            glint = Color(0x14FFFFFF),
            scrim = paneCanvas.copy(alpha = 0.6f),
        ),
        text = TextTokens(ink, inkMuted, inkFaint = lerp(inkMuted, paneCanvas, 0.35f), onAccent = shell),
        accents = AccentTokens(accent = primary, accentMuted = selection, focus = ink, brandMark = cream),
        status = StatusTokens(
            working = warning,
            needsYou = destructive,
            done = link,
            error = destructive,
            idle = Color(0xFF8E968E),
            success = success,
            warning = warning,
            danger = destructive,
            link = link,
            mention = mention,
        ),
        code = CodeTokens(
            editorBackground = Color(0xFF171717),
            editorText = Color(0xFFBABABA),
            syntax = syntax,
            diff = DiffTokens(
                addedBackground = addedBackground,
                addedText = addedText,
                addedStrongBackground = lerp(addedBackground, addedText, 0.25f),
                removedBackground = removedBackground,
                removedText = destructive,
                removedStrongBackground = lerp(removedBackground, destructive, 0.25f),
                meta = warning,
            ),
            ansi = ansiNormal + ansiBright,
        ),
        threadColors = ThreadColorTokens(
            red = destructive,
            orange = lerp(destructive, warning, 0.5f),
            yellow = warning,
            green = success,
            blue = link,
            purple = syntax.number,
            gray = inkMuted,
        ),
        media = MediaTokens(
            letterbox = Color(0xFF000000),
            lightboxDark = Color(0xFF111111),
            lightboxLight = Color(0xFFFFFFFF),
            lightboxChecker = Color(0xFFCCCCCC),
        ),
        debug = DebugTokens(crease = Color(0xFFFF2BD6)),
    )
}
