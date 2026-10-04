package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Where the game's canvas starts on a display, against what LDPlayer and
 * two phones were measured to draw (PLAN_FORMATE.md 5 and V3). Every case
 * is a fit of scale and offset against the 1080 x 1920 twin; the expected
 * number is the measured ty, rounded.
 */
class CanvasTopTest {

    @Test
    fun `under the ceiling the cutout decides`() {
        assertEquals(0, Dungeon.canvasTop(1080, 1920, 0))      // the reference
        assertEquals(0, Dungeon.canvasTop(1080, 2340, 0))      // corpus/tall, exactly at the ceiling
        assertEquals(136, Dungeon.canvasTop(1080, 2340, 136))  // LDPlayer hole, ty 136.5
        assertEquals(136, Dungeon.canvasTop(1080, 2400, 136))  // LDPlayer hole, ty 134.6
        assertEquals(80, Dungeon.canvasTop(1080, 2400, 80))    // the Poco F3, ty 79.8
        assertEquals(105, Dungeon.canvasTop(1080, 2340, 105))  // the S26 Ultra, ty 104.7
    }

    @Test
    fun `over the ceiling the band decides, whatever the cutout`() {
        assertEquals(30, Dungeon.canvasTop(1080, 2370, 0))     // ty 30.5
        assertEquals(60, Dungeon.canvasTop(1080, 2400, 0))     // ty 58.5
        assertEquals(120, Dungeon.canvasTop(1080, 2460, 0))    // ty 119.5
        assertEquals(180, Dungeon.canvasTop(1080, 2520, 0))    // ty 179.2
        assertEquals(180, Dungeon.canvasTop(1080, 2520, 84))   // LDPlayer tall, ty 179.2
        assertEquals(300, Dungeon.canvasTop(1080, 2640, 0))    // ty 300.8
        assertEquals(80, Dungeon.canvasTop(1440, 3200, 0))     // ty 79.6
        assertEquals(40, Dungeon.canvasTop(720, 1600, 0))      // ty 41.4
        assertEquals(0, Dungeon.canvasTop(720, 1560, 0))       // ty 0.8
    }

    @Test
    fun `a landscape display has no strip`() {
        assertEquals(0, Dungeon.canvasTop(1920, 1080, 136))
        assertEquals(0, Dungeon.canvasTop(0, 0, 0))
    }
}
