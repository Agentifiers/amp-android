package com.agentifiers.amp.model.diff

import com.agentifiers.amp.model.Language
import com.agentifiers.amp.model.SyntaxKind.Comment
import com.agentifiers.amp.model.SyntaxKind.Keyword
import com.agentifiers.amp.model.SyntaxKind.Literal
import com.agentifiers.amp.model.SyntaxKind.Number
import com.agentifiers.amp.model.SyntaxKind.Type
import com.agentifiers.amp.model.SyntaxSpan
import org.junit.Assert.assertEquals
import org.junit.Test

class SyntaxTest {
    @Test
    fun languageComesFromExtensionOrFileName() {
        assertEquals(Language.Kotlin, languageOf("src/main/App.kt"))
        assertEquals(Language.Kotlin, languageOf("app/build.gradle.kts"))
        assertEquals(Language.Dockerfile, languageOf("Dockerfile"))
        assertEquals(Language.Dockerfile, languageOf("deploy/Dockerfile.dev"))
        assertEquals(Language.Makefile, languageOf("Makefile"))
        assertEquals(Language.Ruby, languageOf("Gemfile"))
        assertEquals(Language.TypeScript, languageOf("web/Index.TSX"))
        assertEquals(Language.Plain, languageOf("notes.txt"))
    }

    @Test
    fun kotlinBlockCommentCarriesAcrossLines() {
        assertEquals(
            listOf(
                listOf(SyntaxSpan(0, 3, Keyword), SyntaxSpan(8, 12, Literal), SyntaxSpan(13, 20, Comment)),
                listOf(SyntaxSpan(0, 8, Comment)),
                listOf(SyntaxSpan(0, 6, Comment), SyntaxSpan(7, 10, Keyword)),
            ),
            Syntax.tokenize(Language.Kotlin, listOf("val x = \"hi\" // note", "/* start", "end */ fun")),
        )
    }

    @Test
    fun kotlinCapitalizedIdentifiersAreTypesAndNumbersAreNumbers() {
        assertEquals(
            listOf(listOf(SyntaxSpan(0, 3, Keyword), SyntaxSpan(7, 13, Type), SyntaxSpan(16, 19, Number), SyntaxSpan(22, 25, Literal))),
            Syntax.tokenize(Language.Kotlin, listOf("val n: Double = 1.5 + 'c'")),
        )
    }

    @Test
    fun pythonTripleQuotedStringCarriesAcrossLines() {
        assertEquals(
            listOf(
                listOf(SyntaxSpan(0, 3, Keyword)),
                listOf(SyntaxSpan(8, 14, Literal)),
                listOf(SyntaxSpan(0, 10, Literal), SyntaxSpan(11, 14, Comment)),
            ),
            Syntax.tokenize(Language.Python, listOf("def f():", "    s = \"\"\"doc", "    end\"\"\" # c")),
        )
    }

    @Test
    fun rustLifetimeIsNotACharLiteral() {
        assertEquals(
            listOf(listOf(SyntaxSpan(0, 2, Keyword), SyntaxSpan(12, 15, Literal))),
            Syntax.tokenize(Language.Rust, listOf("fn f<'a>(c: 'x')")),
        )
    }

    @Test
    fun sqlKeywordsIgnoreCase() {
        assertEquals(
            listOf(listOf(SyntaxSpan(0, 6, Keyword), SyntaxSpan(9, 13, Keyword), SyntaxSpan(16, 21, Keyword), SyntaxSpan(22, 26, Comment))),
            Syntax.tokenize(Language.Sql, listOf("SELECT * from t where -- x")),
        )
    }

    @Test
    fun markdownHeadingsAndFencedCode() {
        assertEquals(
            listOf(
                listOf(SyntaxSpan(0, 7, Keyword)),
                listOf(SyntaxSpan(4, 8, Literal)),
                listOf(SyntaxSpan(0, 5, Literal)),
                listOf(SyntaxSpan(0, 4, Literal)),
                listOf(SyntaxSpan(0, 3, Literal)),
            ),
            Syntax.tokenize(Language.Markdown, listOf("# Title", "Run `ls` now", "```sh", "# no", "```")),
        )
    }

    @Test
    fun plainHasNoTokens() {
        assertEquals(listOf(emptyList<SyntaxSpan>()), Syntax.tokenize(Language.Plain, listOf("val x = 1")))
        assertEquals(listOf(listOf(SyntaxSpan(0, 3, Keyword), SyntaxSpan(8, 9, Number))), Syntax.tokenize(Language.Kotlin, listOf("val x = 1")))
    }
}
