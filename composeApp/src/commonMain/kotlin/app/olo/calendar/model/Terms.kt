package app.olo.calendar.model

/**
 * The words the screens use for OLO's own ideas (docs/08 §2), defined once so a
 * rename is one edit. Screens build their sentences from these; a test keeps the
 * retired words ("구글 메인", "맞춤", "되살리기", …) from coming back.
 */
object Term {
    /** Samsung's calendar on the Galaxy side (a calendar's real name is shown as is). */
    const val SAMSUNG = "삼성 캘린더"

    /** The Google calendar the bridge copies into, which the iPhone reads. */
    const val GOOGLE_MAIN = "Google 메인 캘린더"

    /** The account holding [GOOGLE_MAIN], where an account (not its calendar) is meant. */
    const val GOOGLE_MAIN_ACCOUNT = "Google 메인 계정"

    /** What the bridge does between [SAMSUNG] and [GOOGLE_MAIN]; "동기화" stays the phone's own. */
    const val BRIDGE = "연동"

    /** Bringing deleted events back. */
    const val RESTORE = "복원"

    /** An account OLO must be allowed to see once (the system account chooser). */
    const val NEEDS_ACCESS = "허용 필요"
}

/**
 * Korean particles that follow a word's last sound ("회의를", "일정을"): a word
 * ending in a final consonant takes [withFinal], else [without]. Latin letters
 * and digits are read by their Korean names ("Google을", "3을").
 */
fun String.josa(withFinal: String, without: String): String {
    // A closing quote or bracket is not read: "‘회의’를".
    val word = trimEnd().trimEnd('’', '\'', '"', ')', ']')
    val c = word.lastOrNull() ?: return this + without
    val final = when {
        word.endsWith("le", ignoreCase = true) -> true // "Google" is read 구글
        c in '가'..'힣' -> (c - '가') % 28 != 0
        c.isDigit() -> c in "013678"
        c.lowercaseChar() in "lmnr" -> true
        else -> false
    }
    return this + if (final) withFinal else without
}

/** "회의를" / "일정을". */
fun String.eulReul() = josa("을", "를")

/** "회의가" / "일정이". */
fun String.iGa() = josa("이", "가")
