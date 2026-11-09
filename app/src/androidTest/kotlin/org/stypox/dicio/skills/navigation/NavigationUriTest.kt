package org.stypox.dicio.skills.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationUriTest {
    @Test
    fun destinationIsEncodedAsOneQueryValue() {
        assertEquals("geo:0,0?q=A%26B%20%23%20%C3%A9", navigationUri("A&B # é").toString())
    }
}
