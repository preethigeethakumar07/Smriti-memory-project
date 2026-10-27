package com.smriti.app

import com.smriti.app.perception.Spatial
import com.smriti.app.perception.Spatial.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialTest {
    @Test fun bottleOnTableNextToCup() {
        val r = Spatial.relationsOf(
            listOf(
                Box("dining table", 0.05f, 0.55f, 0.95f, 0.95f),
                Box("bottle", 0.40f, 0.35f, 0.48f, 0.57f),
                Box("cup", 0.55f, 0.42f, 0.63f, 0.57f)
            )
        )
        assertEquals("on the table, next to the cup", r["bottle"])
    }

    @Test fun farApartStillNamesWhatElseWasInView() {
        val r = Spatial.relationsOf(listOf(Box("book", 0.1f, 0.1f, 0.2f, 0.2f), Box("tv", 0.7f, 0.7f, 0.95f, 0.95f)))
        assertEquals("with the TV in view", r["book"])
    }

    @Test fun aloneGivesNoRelation() {
        assertTrue(Spatial.relationsOf(listOf(Box("book", 0.1f, 0.1f, 0.2f, 0.2f))).isEmpty())
    }

    @Test fun roomFromFurniture() {
        assertEquals("bedroom", Spatial.roomOf(emptyList(), listOf("bed")))
        assertEquals("kitchen", Spatial.roomOf(emptyList(), listOf("refrigerator")))
        assertEquals("kitchen", Spatial.roomOf(listOf("Kitchen", "Room"), emptyList()))
    }

    @Test fun medicineWords() {
        assertTrue(Spatial.looksLikeMedicine("PARACETAMOL TABLETS"))
        assertTrue(!Spatial.looksLikeMedicine("hello world"))
    }
}
