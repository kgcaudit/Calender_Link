package app.olo.calendar

import app.olo.calendar.model.currentWords
import app.olo.calendar.model.eulReul
import app.olo.calendar.model.iGa
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/08: the screens speak one vocabulary. */
class WordingTest {
    /** Words docs/08 §2 retired, with what replaced them. */
    private val retired = mapOf(
        "구글" to "Google",
        "맞춤" to "연동",
        "맞추" to "연동",
        "되살" to "복원",
        "짝" to "연동된 일정",
        "이 기기만" to "이 기기에만 저장",
        "›" to ">",
    )

    @Test
    fun screensUseTheCurrentWords() {
        val root = File("src/commonMain/kotlin/app/olo/calendar")
        val literal = Regex("\"([^\"\\\\]|\\\\.)*\"")
        val found = root.walkTopDown().filter { it.extension == "kt" }
            // The legacy table and the glossary name the old words on purpose.
            .filter { it.name != "Terms.kt" }
            .flatMap { f ->
                f.readLines().withIndex().flatMap { (i, line) ->
                    if (line.trimStart().startsWith("*") || line.trimStart().startsWith("//") || " to " in line && f.name == "BridgeLog.kt") emptyList()
                    else literal.findAll(line).map { it.value }.filter { s -> retired.keys.any { it in s } }.map { "${f.name}:${i + 1} $it" }.toList()
                }
            }.toList()
        assertTrue(found.isEmpty(), "retired words on screen:\n" + found.joinToString("\n"))
    }

    @Test
    fun oldRecordsReadInTodaysWords() {
        assertEquals("다시 연동", currentWords("지금 맞추기"))
        assertEquals("복원", currentWords("되살림"))
        assertEquals("동시 수정 2건(삼성 쪽 유지)", currentWords("양쪽 수정 2건 · Samsung 기준"))
    }

    @Test
    fun particlesFollowTheLastSound() {
        assertEquals("‘회의’를", "‘회의’".eulReul())
        assertEquals("‘일정’을", "‘일정’".eulReul())
        assertEquals("Google을", "Google".eulReul())
        assertEquals("‘병원’이", "‘병원’".iGa())
        assertEquals("‘치과’가", "‘치과’".iGa())
    }
}
