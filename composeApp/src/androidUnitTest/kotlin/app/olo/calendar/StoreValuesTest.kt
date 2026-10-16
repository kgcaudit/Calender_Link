package app.olo.calendar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoreValuesTest {
    @Test
    fun durationsAsAndroidStoresThem() {
        assertEquals(3_600_000L, durationMillis("P3600S"))
        assertEquals(86_400_000L, durationMillis("P1D"))
        assertEquals(5_400_000L, durationMillis("PT1H30M"))
        assertEquals(7 * 86_400_000L, durationMillis("P1W"))
        assertEquals(0L, durationMillis(null))
        assertEquals(0L, durationMillis("garbage"))
    }

    @Test
    fun unchangedValuesAreNotRewritten() {
        assertTrue(sameValue(null, ""))          // empty text vs missing column
        assertTrue(sameValue(1L, 1))             // provider Long vs Int
        assertTrue(sameValue("a", "a"))
        assertFalse(sameValue(null, 5L))
        assertFalse(sameValue("FREQ=DAILY", null))
    }
}
