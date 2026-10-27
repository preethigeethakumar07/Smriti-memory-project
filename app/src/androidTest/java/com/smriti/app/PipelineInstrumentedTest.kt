package com.smriti.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smriti.app.memory.AppDatabase
import com.smriti.app.memory.MemoryRepository
import com.smriti.app.memory.ZoneTracker
import com.smriti.app.models.ModelHost
import com.smriti.app.pipeline.EventType
import com.smriti.app.pipeline.FaultInjector
import com.smriti.app.pipeline.Pipeline
import com.smriti.app.pipeline.PipelineBus
import com.smriti.app.pipeline.Stage
import com.smriti.app.pipeline.Status
import com.smriti.app.pipeline.WarmupFrames
import com.smriti.app.privacy.Redactor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the REAL pipeline (real ML Kit models, real Room) on the device, with known synthetic inputs.
 * These are the checks that prove the backend does what the privacy claims say it does.
 */
@RunWith(AndroidJUnit4::class)
class PipelineInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase
    private lateinit var repo: MemoryRepository
    private lateinit var models: ModelHost
    private lateinit var redactor: Redactor
    private lateinit var pipeline: Pipeline

    @Before fun setUp() = runBlocking {
        FaultInjector.clear()
        PipelineBus.clear()
        db = AppDatabase.inMemory(context)
        repo = MemoryRepository(db)
        models = ModelHost()
        redactor = Redactor()
        val zones = ZoneTracker(db.zoneDao())
        pipeline = Pipeline(redactor, models, zones, repo)
        val cfg = pipeline.config.value
        listOf(cfg.detectorId, cfg.labelerId, cfg.ocrId).forEach { models.select(it) }
    }

    @After fun tearDown() {
        FaultInjector.clear()
        models.closeAll()
        redactor.close()
        db.close()
    }

    private fun events(frameId: Long) = PipelineBus.snapshot().filter { it.frameId == frameId }
    private fun stageStatuses(frameId: Long, stage: String) =
        events(frameId).filter { it.type == EventType.STAGE && it.stage == stage }.map { it.status }

    // ---- happy path ------------------------------------------------------------------------------------------------

    @Test fun everyStageReportsStartAndOkWithDurations() = runBlocking {
        val r = pipeline.run(WarmupFrames.scene(), 100, "test", store = true)
        assertFalse(r.aborted); assertEquals(0, r.errors)
        for (s in listOf(Stage.PRIVACY, Stage.DETECT, Stage.LABEL, Stage.OCR, Stage.ZONE, Stage.COMPOSE, Stage.STORE, Stage.LEDGER)) {
            assertEquals("stage $s", listOf(Status.START, Status.OK), stageStatuses(100, s))
        }
        val end = events(100).last { it.type == EventType.FRAME_END }
        assertEquals(Status.OK, end.status)
        assertTrue(end.durMs > 0)
    }

    @Test fun textInFrameIsReadAndNumbersAreScrubbed() = runBlocking {
        val r = pipeline.run(WarmupFrames.textPage("HELLO 9845012345"), 101, "test", store = true)
        val text = r.perception!!.ocrText
        assertTrue("OCR should read the word, got '$text'", text.contains("HELLO", ignoreCase = true))
        assertFalse("digits must be scrubbed, got '$text'", text.contains("9845012345"))
        // and the stored note carries the scrubbed text too
        val stored = db.memoryDao().recent(10)
        assertTrue(stored.none { it.ocrText.contains("9845012345") || it.caption.contains("9845012345") })
    }

    @Test fun blankFrameIsHandledWithoutErrors() = runBlocking {
        val r = pipeline.run(WarmupFrames.blank(), 102, "test", store = true)
        assertFalse(r.aborted); assertEquals(0, r.errors)
        assertNotNull(r.perception)
    }

    @Test fun benchmarkRunsDoNotStoreAnything() = runBlocking {
        pipeline.run(WarmupFrames.scene(), 103, "bench", store = false)
        assertEquals(0, db.memoryDao().recent(10).size)
        assertEquals(listOf(Status.SKIPPED), stageStatuses(103, Stage.STORE))
    }

    // ---- privacy ---------------------------------------------------------------------------------------------------------

    @Test fun privacyFailureFailsClosed() = runBlocking {
        FaultInjector.set(Stage.PRIVACY, true)
        val r = pipeline.run(WarmupFrames.scene(), 110, "test", store = true)
        assertTrue(r.aborted)
        assertNull(r.perception)
        // no model ever ran on the unredacted frame and nothing was stored
        for (s in listOf(Stage.DETECT, Stage.LABEL, Stage.OCR, Stage.STORE, Stage.LEDGER)) {
            assertTrue("stage $s must not run", stageStatuses(110, s).isEmpty())
        }
        assertEquals(0, db.memoryDao().recent(10).size)
        assertEquals(Status.ERROR, events(110).last { it.type == EventType.FRAME_END }.status)
    }

    @Test fun pixelateReplacesTheFaceRegionWithBlocksAndLeavesTheRestAlone() {
        val w = 200; val h = 200
        val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (x in 0 until w) for (y in 0 until h) src.setPixel(x, y, Color.rgb(x, y, (x + y) % 256))   // every pixel distinct-ish
        val before = src.copy(Bitmap.Config.ARGB_8888, false)
        val box = Rect(60, 60, 120, 120)

        Redactor.pixelate(src, box)

        // inside the padded box: far fewer distinct colours (at most 6x6 blocks) than the original gradient
        val inside = HashSet<Int>()
        for (x in 66 until 114) for (y in 66 until 114) inside.add(src.getPixel(x, y))
        assertTrue("expected blocky region, found ${inside.size} colours", inside.size <= 36)
        // outside the padded box (60..120 plus 20% = 48..132): untouched
        for ((x, y) in listOf(5 to 5, 190 to 190, 20 to 150, 150 to 20)) {
            assertEquals("pixel ($x,$y) changed", before.getPixel(x, y), src.getPixel(x, y))
        }
    }

    // ---- fault tolerance ---------------------------------------------------------------------------------------------------

    @Test fun aFailingModelStageDoesNotStopTheFrame() = runBlocking {
        FaultInjector.set(Stage.LABEL, true)
        val r = pipeline.run(WarmupFrames.scene(), 120, "test", store = true)
        assertFalse(r.aborted)
        assertEquals(1, r.errors)
        assertNotNull(r.perception)                              // the frame still produced a note
        assertEquals(Status.ERROR, stageStatuses(120, Stage.LABEL).last())
        assertEquals(listOf(Status.START, Status.OK), stageStatuses(120, Stage.OCR))
    }

    @Test fun repeatedFailuresTripTheStageAndRearmRecovers() = runBlocking {
        FaultInjector.set(Stage.LABEL, true)
        repeat(3) { pipeline.run(WarmupFrames.scene(), 130L + it, "test", store = false) }
        assertTrue(pipeline.guard.isTripped(Stage.LABEL))

        pipeline.run(WarmupFrames.scene(), 140, "test", store = false)
        assertEquals(listOf(Status.DISABLED), stageStatuses(140, Stage.LABEL))

        FaultInjector.set(Stage.LABEL, false)
        pipeline.guard.rearm(Stage.LABEL)
        val r = pipeline.run(WarmupFrames.scene(), 141, "test", store = false)
        assertEquals(0, r.errors)
        assertEquals(listOf(Status.START, Status.OK), stageStatuses(141, Stage.LABEL))
    }

    // ---- models ---------------------------------------------------------------------------------------------------------------

    @Test fun swappingAModelEmitsAnEventAndKeepsWorking() = runBlocking {
        models.select("mlkit-labeler-strict")
        assertEquals("mlkit-labeler-strict", models.labeler!!.spec.id)
        val swap = PipelineBus.snapshot().last { it.type == EventType.MODEL_SWAP }
        assertTrue(swap.message.contains("->"))
        val r = pipeline.run(WarmupFrames.scene(), 150, "test", store = false)
        assertEquals(0, r.errors)
    }

    @Test fun anUnavailableModelCannotBeSelectedAndTheOldOneStays() = runBlocking {
        val before = models.captionerSafe()
        val failed = runCatching { models.select("npu-vlm") }
        assertTrue(failed.isFailure)
        assertEquals(before, models.captionerSafe())
        assertNotNull(models.labeler)
    }

    // ---- storage ------------------------------------------------------------------------------------------------------------------

    @Test fun theMemoryTableHasNoImageColumns() {
        val cursor = db.openHelper.readableDatabase.query("PRAGMA table_info(memories)")
        val columns = mutableMapOf<String, String>()
        while (cursor.moveToNext()) columns[cursor.getString(1)] = cursor.getString(2).uppercase()
        cursor.close()
        assertTrue(columns.isNotEmpty())
        assertTrue("no BLOB columns allowed: $columns", columns.values.none { it.contains("BLOB") })
        assertTrue("no image-like column names: $columns", columns.keys.none { n -> listOf("image", "bitmap", "photo", "pixel", "jpeg", "frame").any { n.lowercase().contains(it) } })
    }

    @Test fun duplicateConsecutiveNotesAreNotStoredTwice() = runBlocking {
        val frame = WarmupFrames.textPage("SAME SCENE")
        pipeline.run(frame, 160, "test", store = true)
        pipeline.run(frame, 161, "test", store = true)
        assertEquals(1, db.memoryDao().recent(10).size)
    }

    private fun ModelHost.captionerSafe() = selectedIds().toString()
}
