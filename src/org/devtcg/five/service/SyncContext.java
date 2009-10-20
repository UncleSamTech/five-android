package org.devtcg.five.service;

/**
 * Holds meta information about a sync as its happening.
 */
public class SyncContext
{
	public int numberOfTries;
	public boolean moreRecordsToGet;

	public int numberOfInserts;
	public int numberOfDeletes;
	public int numberOfUpdates;

	/**
	 * Holds the largest (most recent) sync time of all the feeds being merged.
	 * This is useful only for rough reporting purposes (i.e. "Last synchronized 10:32pm")
	 */
	public long newestSyncTime;

	private volatile boolean hasCanceled;
	public boolean networkError;

	/**
	 * Attached to from within getServerDiffs() to provide a way to immediately
	 * release blocking I/O operations.
	 */
	public volatile CancelTrigger trigger;

	public int getTotalRecordsProcessed()
	{
		return numberOfInserts + numberOfDeletes + numberOfUpdates;
	}

	public boolean hasSuccess()
	{
		return hasCanceled() == false && hasError() == false;
	}

	public boolean hasCanceled()
	{
		return hasCanceled;
	}

	public void cancel()
	{
		hasCanceled = true;

		if (trigger != null)
			trigger.onCancel();
	}

	public boolean hasError()
	{
		return networkError;
	}

	public interface CancelTrigger
	{
		public void onCancel();
	}
}