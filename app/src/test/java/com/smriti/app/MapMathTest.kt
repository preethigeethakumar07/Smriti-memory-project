package com.smriti.app

import com.smriti.app.move.MapMath
import com.smriti.app.move.Pt
import com.smriti.app.move.Trail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapMathTest {
    @Test fun everyPointFitsInsideTheCanvas() {
        val pts = listOf(Pt(0.0, 0.0), Pt(300.0, 100.0), Pt(-50.0, 200.0))
        val v = MapMath.fit(pts, 800.0, 600.0, 40.0)
        for (p in pts) {
            val sx = 400 + (p.x - v.cx) * v.scale
            val sy = 300 - (p.y - v.cy) * v.scale
            assertTrue("x $sx", sx in 39.9..760.1)
            assertTrue("y $sy", sy in 39.9..560.1)
        }
    }

    @Test fun aTinyWalkDoesNotFillTheScreen() {
        val v = MapMath.fit(listOf(Pt(0.0, 0.0), Pt(2.0, 1.0)), 800.0, 600.0, 40.0)
        // at least 40 m across the narrower side: 520 px / 40 m = 13 px per metre at most
        assertTrue("scale ${v.scale}", v.scale <= 13.01)
    }

    @Test fun roundStepsForTheGridAndTheScaleBar() {
        assertEquals(10.0, MapMath.gridStep(5.0, 48.0), 1e-9)       // 10 m is 50 px
        assertEquals(50.0, MapMath.gridStep(1.0, 48.0), 1e-9)
        assertEquals(1.0, MapMath.gridStep(100.0, 48.0), 1e-9)
        assertEquals(20.0, MapMath.barLength(5.0, 140.0), 1e-9)     // 20 m is 100 px; 50 m would be 250
        assertEquals(1.0, MapMath.barLength(100.0, 50.0), 1e-9)     // nothing fits: the smallest
        assertEquals("50 m", MapMath.label(50.0)); assertEquals("1 km", MapMath.label(1000.0)); assertEquals("2.5 km", MapMath.label(2500.0))
    }

    @Test fun arrowsAreSpacedAlongTheLineAndPointTheWayItRuns() {
        val east = MapMath.arrowMarks(listOf(0.0 to 0.0, 100.0 to 0.0), spacingPx = 40.0)
        assertEquals(2, east.size)
        assertEquals(20.0, east[0].x, 1e-9); assertEquals(60.0, east[1].x, 1e-9)
        assertTrue(east.all { it.angle == 0.0 && it.y == 0.0 })
        val down = MapMath.arrowMarks(listOf(0.0 to 0.0, 0.0 to 100.0), spacingPx = 40.0)
        assertEquals(Math.PI / 2, down[0].angle, 1e-9)
    }

    @Test fun arrowsFollowACorner() {
        val m = MapMath.arrowMarks(listOf(0.0 to 0.0, 50.0 to 0.0, 50.0 to 50.0), spacingPx = 30.0, firstAtPx = 15.0)
        // at 15 and 45 along the first leg (east), at 75 along the second (down)
        assertEquals(3, m.size)
        assertEquals(0.0, m[0].angle, 1e-9); assertEquals(0.0, m[1].angle, 1e-9); assertEquals(Math.PI / 2, m[2].angle, 1e-9)
        assertEquals(50.0, m[2].x, 1e-9); assertEquals(25.0, m[2].y, 1e-9)
    }

    @Test fun nothingToDrawGivesNoArrows() {
        assertTrue(MapMath.arrowMarks(emptyList(), 30.0).isEmpty())
        assertTrue(MapMath.arrowMarks(listOf(1.0 to 1.0), 30.0).isEmpty())
        assertTrue(MapMath.arrowMarks(listOf(1.0 to 1.0, 1.0 to 1.0), 30.0).isEmpty())
    }

    @Test fun theExtentCoversEverythingThatIsDrawn() {
        val t = Trail(out = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0)), back = listOf(Pt(5.0, 5.0)), route = listOf(Pt(9.0, 1.0)), here = Pt(1.0, 1.0), dest = Pt(-3.0, 0.0), start = Pt(0.0, 0.0))
        assertEquals(7, MapMath.extent(t).size)
        assertFalse(t.isEmpty)
        assertTrue(Trail().isEmpty)
    }
}
