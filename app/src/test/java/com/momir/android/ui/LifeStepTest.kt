package com.momir.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LifeStepTest {
    @Test
    fun stepsByOneOrToTheNextMultipleOfFive() {
        assertEquals(21, lifeStep(20, 1, big = false))
        assertEquals(19, lifeStep(20, -1, big = false))
        assertEquals(25, lifeStep(20, 1, big = true))
        assertEquals(25, lifeStep(23, 1, big = true))
        assertEquals(15, lifeStep(20, -1, big = true))
        assertEquals(20, lifeStep(23, -1, big = true))
        assertEquals(1, lifeStep(3, -1, big = true))
        assertEquals(999, lifeStep(998, 1, big = true))
    }
}
