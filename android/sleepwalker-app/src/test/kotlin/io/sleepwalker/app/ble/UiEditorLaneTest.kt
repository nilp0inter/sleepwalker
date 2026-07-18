package io.sleepwalker.app.ble

import android.os.Handler
import android.os.Looper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.sleepwalker.core.editor.Editor
import io.sleepwalker.core.editor.EditorResult
import io.sleepwalker.core.editor.EditorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UiEditorLaneTest {

    private lateinit var mainHandler: Handler
    private lateinit var editorLock: Any
    private lateinit var mockEditor: Editor
    private val captured = mutableListOf<Pair<UiEditorRequest, UiEditorResult>>()

    @Before
    fun setUp() {
        mainHandler = Handler(Looper.getMainLooper())
        editorLock = Any()
        captured.clear()
        mockEditor = mockk<Editor>(relaxed = false)
        every { mockEditor.setText(any()) } returns EditorResult.Synced("", emptyList())
        every { mockEditor.reset() } returns Unit
        every { mockEditor.state() } returns EditorState.Synced
    }

    // ── Helpers ──

    private fun createListener() = object : UiEditorListener {
        override fun onUiEditorResult(request: UiEditorRequest, result: UiEditorResult) {
            captured.add(request to result)
        }
    }

    private fun createLane(
        executor: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor(),
        snapshotProvider: () -> EditorSnapshot? = { null },
        onReset: () -> Unit = {},
    ) = UiEditorCommandLane(
        executor = executor,
        mainHandler = mainHandler,
        editorLock = editorLock,
        editorProvider = { mockEditor },
        snapshotProvider = snapshotProvider,
        onReset = onReset,
    )

    /** Drain the main looper so all posted listener callbacks fire. */
    private fun drainMain() {
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
    }

    /** Shut down the executor and drain main looper. */
    private fun flush(executor: java.util.concurrent.ExecutorService) {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
        drainMain()
    }

    // ── Monotonic IDs ──

    @Test
    fun `nextUiChangeId returns strictly increasing values`() {
        val lane = createLane()
        assertEquals(1L, lane.nextUiChangeId())
        assertEquals(2L, lane.nextUiChangeId())
        assertEquals(3L, lane.nextUiChangeId())
    }

    @Test
    fun `changeId reflects current generator value without incrementing`() {
        val lane = createLane()
        assertEquals(0L, lane.changeId())
        assertEquals(1L, lane.nextUiChangeId())
        assertEquals(1L, lane.changeId())
        lane.nextUiChangeId()
        assertEquals(2L, lane.changeId())
    }

    // ── FIFO ordering ──

    @Test
    fun `snapshots arrive in submission order through single-thread lane`() {
        val executor = Executors.newSingleThreadExecutor()
        val lane = createLane(executor = executor)
        lane.listener = createListener()

        val reqs = listOf(
            UiEditorRequest.Snapshot(id = 10, generation = 1, text = "a"),
            UiEditorRequest.Snapshot(id = 20, generation = 1, text = "ab"),
            UiEditorRequest.Snapshot(id = 30, generation = 1, text = "abc"),
        )
        reqs.forEach { lane.submit(it) }
        flush(executor)

        assertEquals(3, captured.size)
        assertEquals("a", (captured[0].second as UiEditorResult.Snapshot).text)
        assertEquals("ab", (captured[1].second as UiEditorResult.Snapshot).text)
        assertEquals("abc", (captured[2].second as UiEditorResult.Snapshot).text)
        assertEquals(10L, captured[0].second.id)
        assertEquals(20L, captured[1].second.id)
        assertEquals(30L, captured[2].second.id)
    }

    @Test
    fun `FIFO order preserved when first request blocks and subsequent requests queue`() {
        val executor = Executors.newSingleThreadExecutor()
        val lane = createLane(executor = executor)
        lane.listener = createListener()

        val processingStarted = CountDownLatch(1)
        val unblockFirst = CountDownLatch(1)
        every { mockEditor.setText("first") } answers {
            processingStarted.countDown()
            assertTrue("Timeout waiting for unblock", unblockFirst.await(5, TimeUnit.SECONDS))
            EditorResult.Synced("first", emptyList())
        }

        lane.submit(UiEditorRequest.Snapshot(id = 1, generation = 1, text = "first"))
        assertTrue("First request should start processing", processingStarted.await(3, TimeUnit.SECONDS))

        // These queue behind the blocked first request
        lane.submit(UiEditorRequest.Snapshot(id = 2, generation = 1, text = "second"))
        lane.submit(UiEditorRequest.Snapshot(id = 3, generation = 1, text = "third"))

        unblockFirst.countDown()
        flush(executor)

        assertEquals(3, captured.size)
        assertEquals(1L, captured[0].second.id)
        assertEquals(2L, captured[1].second.id)
        assertEquals(3L, captured[2].second.id)
    }

    @Test
    fun `reset is ordered after prior snapshots on the same lane`() {
        val executor = Executors.newSingleThreadExecutor()
        val lane = createLane(executor = executor)
        lane.listener = createListener()

        lane.submit(UiEditorRequest.Snapshot(id = 1, generation = 1, text = "hello"))
        lane.submit(UiEditorRequest.Snapshot(id = 2, generation = 1, text = "hello!"))
        lane.submit(UiEditorRequest.Reset(id = 3, generation = 1, acknowledgedEmpty = true))
        flush(executor)

        assertEquals(3, captured.size)
        assertTrue("First should be Snapshot", captured[0].second is UiEditorResult.Snapshot)
        assertTrue("Second should be Snapshot", captured[1].second is UiEditorResult.Snapshot)
        assertTrue("Third should be Reset", captured[2].second is UiEditorResult.Reset)
        verify(exactly = 2) { mockEditor.setText(any()) }
        verify(exactly = 1) { mockEditor.reset() }
    }

    @Test
    fun `reset without prior snapshots still processes`() {
        val executor = Executors.newSingleThreadExecutor()
        val lane = createLane(executor = executor)
        lane.listener = createListener()

        lane.submit(UiEditorRequest.Reset(id = 1, generation = 1, acknowledgedEmpty = true))
        flush(executor)

        assertEquals(1, captured.size)
        assertTrue("Should be Reset", captured[0].second is UiEditorResult.Reset)
        assertEquals(1L, captured[0].second.id)
        verify(exactly = 1) { mockEditor.reset() }
    }

    @Test
    fun `reset invokes onReset callback`() {
        val executor = Executors.newSingleThreadExecutor()
        var onResetCalled = false
        val lane = createLane(
            executor = executor,
            onReset = { onResetCalled = true },
        )
        lane.listener = createListener()

        lane.submit(UiEditorRequest.Reset(id = 1, generation = 1, acknowledgedEmpty = true))
        flush(executor)

        assertTrue("onReset must be called", onResetCalled)
    }

    // ── No coalescing ──

    @Test
    fun `every submission produces exactly one callback even for identical text`() {
        val executor = Executors.newSingleThreadExecutor()
        val lane = createLane(executor = executor)
        lane.listener = createListener()

        repeat(5) { i ->
            lane.submit(UiEditorRequest.Snapshot(id = i.toLong() + 1, generation = 1, text = "same"))
        }
        flush(executor)

        assertEquals(5, captured.size)
        assertEquals(5, captured.map { (it.second as UiEditorResult.Snapshot).text }.count { it == "same" })
        verify(exactly = 5) { mockEditor.setText("same") }
    }

    // ── Main-thread delivery ──

    @Test
    fun `results are delivered on the main thread`() {
        val executor = Executors.newSingleThreadExecutor()
        val lane = createLane(executor = executor)

        val deliveryThreads = mutableListOf<Thread>()
        lane.listener = object : UiEditorListener {
            override fun onUiEditorResult(request: UiEditorRequest, result: UiEditorResult) {
                deliveryThreads.add(Thread.currentThread())
                captured.add(request to result)
            }
        }

        lane.submit(UiEditorRequest.Snapshot(id = 1, generation = 1, text = "x"))
        flush(executor)

        assertEquals(1, deliveryThreads.size)
        val mainThread = Looper.getMainLooper().thread
        assertTrue("Delivery should be on main looper thread",
            deliveryThreads.all { it === mainThread })
    }

    @Test
    fun `listener is not called before main looper is drained`() {
        val executor = Executors.newSingleThreadExecutor()
        val lane = createLane(executor = executor)
        lane.listener = createListener()

        lane.submit(UiEditorRequest.Snapshot(id = 1, generation = 1, text = "a"))
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        // Do NOT drain main — listener should not have fired yet
        assertTrue("Listener must NOT be called before main looper runs", captured.isEmpty())

        drainMain()
        assertEquals(1, captured.size)
    }

    // ── Immutable request/result correlation ──

    @Test
    fun `result carries the exact id generation and text from the request`() {
        val executor = Executors.newSingleThreadExecutor()
        val lane = createLane(executor = executor)
        lane.listener = createListener()

        val req = UiEditorRequest.Snapshot(id = 42, generation = 7, text = "hello world")
        lane.submit(req)
        flush(executor)

        assertEquals(1, captured.size)
        val (capturedReq, capturedResult) = captured[0]
        assertTrue("Same request id/generation",
            capturedReq.id == req.id && capturedReq.generation == req.generation)
        val snap = capturedResult as UiEditorResult.Snapshot
        assertEquals(42L, snap.id)
        assertEquals(7L, snap.generation)
        assertEquals("hello world", snap.text)
        assertNotNull("EditorResult must be present", snap.result)
    }

    @Test
    fun `reset result carries the exact id and generation from the request`() {
        val executor = Executors.newSingleThreadExecutor()
        val lane = createLane(executor = executor)
        lane.listener = createListener()

        val req = UiEditorRequest.Reset(id = 99, generation = 3, acknowledgedEmpty = true)
        lane.submit(req)
        flush(executor)

        assertEquals(1, captured.size)
        val resetResult = captured[0].second as UiEditorResult.Reset
        assertEquals(99L, resetResult.id)
        assertEquals(3L, resetResult.generation)
    }


    // ── Edge cases ──

    @Test
    fun `empty string snapshot is submitted and processed`() {
        val executor = Executors.newSingleThreadExecutor()
        val lane = createLane(executor = executor)
        lane.listener = createListener()

        lane.submit(UiEditorRequest.Snapshot(id = 1, generation = 1, text = ""))
        flush(executor)

        assertEquals(1, captured.size)
        val snap = captured[0].second as UiEditorResult.Snapshot
        assertEquals("", snap.text)
        verify(exactly = 1) { mockEditor.setText("") }
    }

    @Test
    fun `processing error is caught without crashing the lane`() {
        val executor = Executors.newSingleThreadExecutor()
        val lane = createLane(executor = executor)
        lane.listener = createListener()

        every { mockEditor.setText(any()) } throws RuntimeException("Boom")

        lane.submit(UiEditorRequest.Snapshot(id = 1, generation = 1, text = "crash"))

        // Wait for crash to process without shutting down executor
        val crashLatch = CountDownLatch(1)
        executor.submit { crashLatch.countDown() }
        crashLatch.await(5, TimeUnit.SECONDS)
        drainMain()

        // After a crash, the lane should still accept new requests (no callback from crash)
        assertEquals(0, captured.size)

        every { mockEditor.setText(any()) } returns EditorResult.Synced("fixed", emptyList())
        lane.submit(UiEditorRequest.Snapshot(id = 2, generation = 1, text = "fixed"))

        val fixedLatch = CountDownLatch(1)
        executor.submit { fixedLatch.countDown() }
        fixedLatch.await(5, TimeUnit.SECONDS)
        drainMain()

        assertEquals(1, captured.size)
        assertEquals("fixed", (captured[0].second as UiEditorResult.Snapshot).text)

        executor.shutdown()
    }

    @Test
    fun `lane processes multiple submissions across different generations`() {
        val executor = Executors.newSingleThreadExecutor()
        val lane = createLane(executor = executor)
        lane.listener = createListener()

        lane.submit(UiEditorRequest.Snapshot(id = 1, generation = 1, text = "gen1"))
        lane.submit(UiEditorRequest.Snapshot(id = 2, generation = 2, text = "gen2"))
        lane.submit(UiEditorRequest.Reset(id = 3, generation = 2, acknowledgedEmpty = true))
        flush(executor)

        assertEquals(3, captured.size)
        assertEquals(1L, captured[0].second.generation)
        assertEquals(2L, captured[1].second.generation)
        assertEquals(2L, captured[2].second.generation)
    }
}
