/*******************************************************************************
 * Copyright 2013 Michael Marconi
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package oncue.scheduler.internal;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;

import oncue.backingstore.internal.IBackingStore;
import oncue.comparators.JobComparator;
import oncue.messages.internal.Job;
import akka.event.LoggingAdapter;

/**
 * An encapsulated job queue of unscheduled {@linkplain Job}s. If this queue has
 * a backing store, all actions on the queue will be persisted.
 */
public class UnscheduledJobs {

	// The prioritised queue of jobs
	private PriorityQueue<Job> unscheduledJobs = new PriorityQueue<>(10, new JobComparator());

	// An optional, persistent backing store
	private IBackingStore backingStore;

	private LoggingAdapter log;

	/**
	 * @param backingStore
	 *            is an optional instance of {@linkplain IBackingStore}
	 */
	public UnscheduledJobs(IBackingStore backingStore, LoggingAdapter log) {
		this.backingStore = backingStore;
		this.log = log;
		if (backingStore != null)
			restoreJobs();
	}

	/**
	 * Add a job to the queue
	 */
	public void addJob(Job job) {
		unscheduledJobs.add(job);

		if (backingStore != null)
			backingStore.addUnscheduledJob(job);
	}

	/**
	 * @return the number of jobs in the queue
	 */
	public int getSize() {
		return unscheduledJobs.size();
	}

	public Job popJob() throws NoJobsException {
		try {
			Job job = unscheduledJobs.remove();

			if (backingStore != null && job != null)
				backingStore.popUnscheduledJob();

			return job;
		} catch (NoSuchElementException e) {
			throw new NoJobsException();
		}
	}

	/**
	 * Remove a job from anywhere in the queue
	 * 
	 * @return a boolean, indicating if the removal was successful
	 */
	public boolean removeJob(Job job) {
		boolean removed = unscheduledJobs.remove(job);
		if (backingStore != null && removed)
			backingStore.removeUnscheduledJob(job);

		return removed;
	}

	/**
	 * Restore any scheduled and unscheduled jobs from the backing store
	 */
	private void restoreJobs() {
		List<Job> restoredJobs = backingStore.restoreJobs();
		if (restoredJobs != null && restoredJobs.size() > 0)
			log.info("Restoring {} jobs from the backing store", restoredJobs.size());
		unscheduledJobs.addAll(restoredJobs);
	}
}
