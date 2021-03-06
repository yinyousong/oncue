package oncue.tests.schedulers;

import static junit.framework.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import oncue.common.messages.Job;
import oncue.scheduler.PriorityJobComparator;

import org.joda.time.DateTime;
import org.junit.Test;

import com.google.common.base.Function;
import com.google.common.collect.Lists;

public class PriorityJobComparatorTest {
	private final PriorityJobComparator comparator = new PriorityJobComparator();

	@Test
	public void returnsJobsWithOldestFirstWhenPriorityIsTheSame() {
		final DateTime now = DateTime.now();

		ArrayList<Job> jobs = Lists.newArrayList(makeJob(1, 1, now),
				makeJob(2, 1, now.minusMinutes(1)), makeJob(3, 1, now.minusMinutes(2)));
		Collections.sort(jobs, comparator);
		assertEquals(Lists.newArrayList(3l, 2l, 1l), extractJobIds(jobs));
	}

	@Test
	public void returnsJobsInIDOrderWhenPriorityAndEnqueueTimeIsTheSame() {
		final DateTime now = DateTime.now();

		ArrayList<Job> jobs = Lists.newArrayList(makeJob(1, 1, now),
				makeJob(2, 1, now), makeJob(3, 1, now));
		Collections.sort(jobs, comparator);
		assertEquals(Lists.newArrayList(1l, 2l, 3l), extractJobIds(jobs));
	}

	@Test
	public void returnsJobsInPriorityOrder() {
		ArrayList<Job> jobs = Lists.newArrayList(makeJob(1, 1), makeJob(2, 3), makeJob(3, 2));
		Collections.sort(jobs, comparator);
		assertEquals(Lists.newArrayList(2l, 3l, 1l), extractJobIds(jobs));
	}

	private List<Long> extractJobIds(ArrayList<Job> jobs) {
		return Lists.transform(jobs, new Function<Job, Long>() {

			@Override
			public Long apply(Job input) {
				return input.getId();
			}

		});
	}

	private Job makeJob(int id, int priority) {
		Job job = new Job(id, null);
		job.setParams(new HashMap<String, String>());
		job.getParams().put("priority", String.valueOf(priority));
		return job;
	}

	private Job makeJob(int id, int priority, DateTime enqueuedAt) {
		Job job = makeJob(id, priority);
		job.setEnqueuedAt(enqueuedAt);
		return job;
	}

}