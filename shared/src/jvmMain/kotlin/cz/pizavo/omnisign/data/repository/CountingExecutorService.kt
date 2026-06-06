package cz.pizavo.omnisign.data.repository

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * An [ExecutorService] decorator that counts DSS's per-trusted-list analysis tasks (runtime class
 * simple name [LIST_ANALYSIS_TASK]) across a refresh, forwarding actual execution to a shared
 * [delegate] pool so no extra threads are created.
 *
 * DSS schedules one such task per trusted list — each EU LOTL member-state list and each custom
 * list — once its parent has been parsed, plus LOTL/pivot analyses (different class names)
 * beforehand. Only the per-list tasks are counted; the rest run normally but are ignored, so the
 * reported total stays `0` until lists are scheduled — exactly when a determinate "loaded of total"
 * reading becomes meaningful.
 *
 * The coupling to the DSS class name is deliberate and degrades gracefully: if a future DSS version
 * renames the task, nothing is counted and progress simply stays indeterminate (no error, no wrong
 * number). Every submit and completion invokes [onProgress] with the running (submitted, completed)
 * counts; [reset] zeroes the counters for a new refresh session.
 *
 * @property delegate The shared pool that actually runs the tasks.
 * @property onProgress Invoked with the current (submitted, completed) per-list task counts.
 */
class CountingExecutorService(
	private val delegate: ExecutorService,
	private val onProgress: (submitted: Int, completed: Int) -> Unit,
) : ExecutorService by delegate {

	private val submitted = AtomicInteger(0)
	private val completed = AtomicInteger(0)

	/** Zero the counters at the start of a refresh session and publish the cleared state. */
	fun reset() {
		submitted.set(0)
		completed.set(0)
		onProgress(0, 0)
	}

	private fun isListAnalysisTask(task: Runnable): Boolean =
		task::class.java.simpleName == LIST_ANALYSIS_TASK

	/**
	 * Wrap a per-list [task] so its submission and completion bump the counters; pass any other
	 * task through untouched.
	 */
	private fun track(task: Runnable): Runnable {
		if (!isListAnalysisTask(task)) return task
		onProgress(submitted.incrementAndGet(), completed.get())
		return Runnable {
			try {
				task.run()
			} finally {
				onProgress(submitted.get(), completed.incrementAndGet())
			}
		}
	}

	override fun execute(command: Runnable) = delegate.execute(track(command))

	override fun submit(task: Runnable): Future<*> = delegate.submit(track(task))

	override fun <T : Any?> submit(task: Runnable, result: T): Future<T> =
		delegate.submit(track(task), result)

	override fun <T : Any?> submit(task: Callable<T>): Future<T> = delegate.submit(task)

	private companion object {
		/** DSS runnable class (simple name) for a single trusted-list analysis (LOTL member or custom). */
		const val LIST_ANALYSIS_TASK = "TLAnalysis"
	}
}
