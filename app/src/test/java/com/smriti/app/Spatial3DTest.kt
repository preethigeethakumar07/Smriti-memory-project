package com.smriti.app

import com.smriti.app.spatial.Fov
import com.smriti.app.spatial.ObjectTracker
import com.smriti.app.spatial.Point3
import com.smriti.app.spatial.Pose
import com.smriti.app.spatial.Relative
import com.smriti.app.spatial.Spatial3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Spatial3DTest {
    private val fov = Fov(54f, 69f)

    /** Phone held upright, camera looking North: device x = East, device y = Up, camera axis (-z) = North. */
    private fun northUpright(valid: Boolean = true) = Pose(floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f), valid)

    @Test fun poseFacingNorth() {
        val p = northUpright()
        assertEquals(0f, p.azimuthDeg, 0.5f)
        assertEquals(0f, p.pitchDeg, 0.5f)
    }

    @Test fun gravityOnlyGivesPitchButNoHeading() {
        val upright = Pose.fromGravity(0f, 9.8f, 0f)
        assertEquals(0f, upright.pitchDeg, 1f)
        assertFalse(upright.headingValid)
        // tilted to look 30 degrees upward: the up direction gains a component along the camera axis (-z)
        val up30 = Pose.fromGravity(0f, 9.8f * Math.cos(Math.toRadians(30.0)).toFloat(), -9.8f * Math.sin(Math.toRadians(30.0)).toFloat())
        assertEquals(30f, up30.pitchDeg, 1.5f)
    }

    @Test fun centreOfPictureIsStraightAhead() {
        val pt = Spatial3D.place("bottle", 0.45f, 0.4f, 0.55f, 0.6f, northUpright(), fov)
        assertEquals(0f, pt.azimuthDeg, 1f)
        assertEquals(0f, pt.elevationDeg, 1f)
    }

    @Test fun rightEdgeIsHalfTheFieldOfViewToTheEast() {
        val pt = Spatial3D.place("cup", 0.95f, 0.45f, 1.0f, 0.55f, northUpright(), fov)
        // centre of the box is at u = 0.975, about 26 degrees right of the axis
        assertEquals(26f, pt.azimuthDeg, 2.5f)
        assertTrue(pt.x > 0f)
    }

    @Test fun distanceFromKnownSize() {
        // a 24 cm bottle filling 20% of the picture height, vertical field of view 69 degrees
        val d = Spatial3D.distanceFromSize("bottle", 0.08f, 0.20f, fov)!!
        assertEquals(0.87f, d, 0.1f)
        // half the size in the picture means twice as far
        val far = Spatial3D.distanceFromSize("bottle", 0.04f, 0.10f, fov)!!
        assertEquals(d * 2f, far, 0.15f)
        assertEquals(null, Spatial3D.distanceFromSize("mystery thing", 0.2f, 0.2f, fov))
    }

    @Test fun lookingUpMakesObjectsAppearAbove() {
        val upright = northUpright()
        val high = Spatial3D.place("clock", 0.45f, 0.05f, 0.55f, 0.15f, upright, fov)   // near the top of the picture
        assertTrue(high.elevationDeg > 15f)
        assertTrue(high.z > 0f)
    }

    @Test fun turnLeftOrRight() {
        val target = Point3("mouse", 0f, 2f, 0f, 2f, 40f, 0f, 0.6f, true)         // 40 degrees east of north
        val facingNorth = Spatial3D.relative(target, northUpright())
        assertEquals(40f, facingNorth.turnDeg, 1f)                                 // turn right
        assertTrue(Spatial3D.describe(facingNorth).contains("to your right"))
        val facingEast = Pose(floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f), true)  // camera axis (-z) = East
        assertEquals(90f, facingEast.azimuthDeg, 1f)
        assertTrue(Spatial3D.relative(target, facingEast).turnDeg < 0f)            // now it is to the left
    }

    @Test fun noCompassMeansNoDirectionIsClaimed() {
        val r = Relative(2.1f, 40f, 0f, headingValid = false)
        val words = Spatial3D.describe(r)
        assertTrue(words.contains("2.1 m"))
        assertFalse(words.contains("left") || words.contains("right"))
    }

    @Test fun trackerKeepsIdentityAndSeparatesNeighbours() {
        val t = ObjectTracker()
        fun p(az: Float) = Point3("cup", 0f, 0f, 0f, 1f, az, 0f, 0.6f, true)
        val a = t.update(listOf(p(10f), p(60f)), 0).map { it.id }
        assertEquals(2, a.toSet().size)
        val b = t.update(listOf(p(12f), p(58f)), 1000).map { it.id }
        assertEquals(a.sorted(), b.sorted())                                       // same two objects
        val c = t.update(listOf(p(200f)), 2000).first()
        assertNotEquals(a[0], c.id)                                                // far away: a new one
        assertNotNull(c.name)
    }
}
