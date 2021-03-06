/*******************************************************************************
 * Copyright 2013 Michael Marconi
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package oncue.tests;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import oncue.agent.UnlimitedCapacityAgent;
import oncue.common.messages.EnqueueJob;
import oncue.common.messages.Job;
import oncue.common.messages.JobProgress;
import oncue.common.messages.WorkResponse;
import oncue.tests.base.ActorSystemTest;
import oncue.tests.workers.JobEnqueueingTestWorker;
import oncue.tests.workers.JobSummaryRequestTestWorker;
import oncue.tests.workers.TestWorker;

import org.junit.Ignore;
import org.junit.Test;

import redis.clients.jedis.Jedis;
import akka.actor.Actor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActorFactory;
import akka.testkit.JavaTestKit;
import akka.testkit.TestActorRef;

/**
 * When an agent receives a {@linkplain WorkResponse}, it will attempt to spawn
 * an instance of an {@linkplain IWorker} for each {@linkplain Job} in the list.
 */
public class WorkerTest extends ActorSystemTest {

	@Test
	public void spawnWorkerAndStartJob() {
		new JavaTestKit(system) {

			{
				// Create an agent probe
				final JavaTestKit agentProbe = new JavaTestKit(system) {

					{
						new IgnoreMsg() {

							protected boolean ignore(Object message) {
								if (message instanceof WorkResponse || message instanceof JobProgress)
									return false;

								return true;
							}
						};
					}
				};

				// Create a scheduler
				ActorRef scheduler = createScheduler(system, null);

				// Create and expose an agent
				@SuppressWarnings("serial")
				TestActorRef<UnlimitedCapacityAgent> agentRef = TestActorRef.create(system, new Props(
						new UntypedActorFactory() {

							@Override
							public Actor create() throws Exception {
								UnlimitedCapacityAgent agent = new UnlimitedCapacityAgent(new HashSet<>(Arrays
										.asList(TestWorker.class.getName())));
								agent.injectProbe(agentProbe.getRef());
								return agent;
							}
						}), settings.AGENT_NAME);
				UnlimitedCapacityAgent agent = agentRef.underlyingActor();

				// Wait until the agent receives an empty work response
				WorkResponse workResponse = agentProbe.expectMsgClass(WorkResponse.class);
				assertEquals(0, workResponse.getJobs().size());

				// Check that there are no workers
				assertFalse("Expected no child workers", agent.getContext().getChildren().iterator().hasNext());

				// Enqueue a job
				scheduler.tell(new EnqueueJob(TestWorker.class.getName()), getRef());

				// Expect a work response
				agentProbe.expectMsgClass(WorkResponse.class);

				// Expect a Job Progress message, showing no progress
				JobProgress jobProgress = agentProbe.expectMsgClass(JobProgress.class);
				assertEquals("Expected no progress on the job", 0.0, jobProgress.getJob().getProgress());

				final ActorRef worker = agent.getContext().getChildren().iterator().next();

				// Wait for the job to complete
				new AwaitCond(duration("5 seconds")) {

					@Override
					protected boolean cond() {
						return agentProbe.expectMsgClass(JobProgress.class).getJob().getProgress() == 1;
					}
				};

				// Ensure the worker is dead
				new AwaitCond(duration("5 seconds")) {

					@Override
					protected boolean cond() {
						return worker.isTerminated() == true;
					}
				};
			}
		};
	}

	@Test
	public void workerEnqueuesJob() {
		new JavaTestKit(system) {

			{
				// Create a scheduler with a probe
				final JavaTestKit schedulerProbe = new JavaTestKit(system) {

					{
						new IgnoreMsg() {

							protected boolean ignore(Object message) {
								if (message instanceof EnqueueJob)
									return false;
								return true;
							}
						};
					}
				};
				ActorRef scheduler = createScheduler(system, schedulerProbe.getRef());

				// Create an agent
				createAgent(
						system,
						new HashSet<String>(Arrays.asList(JobEnqueueingTestWorker.class.getName(),
								TestWorker.class.getName())), null);

				// Enqueue a job
				Map<String, String> params = new HashMap<String, String>();
				params.put("key", "value");
				scheduler.tell(new EnqueueJob(JobEnqueueingTestWorker.class.getName(), params), null);

				// Expect the first job
				EnqueueJob enqueueJob1 = schedulerProbe.expectMsgClass(EnqueueJob.class);
				assertEquals(JobEnqueueingTestWorker.class.getName(), enqueueJob1.getWorkerType());
				assertEquals(params, enqueueJob1.getParams());

				// Expect the JobEnqueueingTestWorker to enqueue a TestWorker
				EnqueueJob enqueueJob2 = schedulerProbe.expectMsgClass(EnqueueJob.class);
				assertEquals(TestWorker.class.getName(), enqueueJob2.getWorkerType());
				assertEquals(params, enqueueJob2.getParams());
			}
		};
	}

	@Test
	public void workerRequestsJobSummary() {
		new JavaTestKit(system) {

			{
				final Jedis jedis = new Jedis("localhost");
				jedis.del("oncue.tests.workers.JobSummaryRequestTestWorker");

				// Create a scheduler
				ActorRef scheduler = createScheduler(system);

				// Create an agent
				createAgent(system, new HashSet<String>(Arrays.asList(JobSummaryRequestTestWorker.class.getName())),
						null);

				// Enqueue a job
				scheduler.tell(new EnqueueJob(JobSummaryRequestTestWorker.class.getName()), null);

				// Ensure the worker got the right job
				new AwaitCond(duration("5 seconds")) {

					@Override
					protected boolean cond() {
						String result = jedis.get("oncue.tests.workers.JobSummaryRequestTestWorker");
						if (result != null) {
							return result.contains("Job 1")
									&& result.contains("workerType=oncue.tests.workers.JobSummaryRequestTestWorker");
						} else
							return false;
					}
				};
				jedis.disconnect();
			}
		};
	}

	@Test
	@Ignore("Ignoring until MF has had a chance to review this")
	// TODO MF: Please review this test
	public void workerFailsWhenSchedulerCannotBeReached() {
//		new JavaTestKit(system) {
//
//			{
//				// Create a probe
//				final JavaTestKit schedulerProbe = new JavaTestKit(system) {
//
//					{
//						new IgnoreMsg() {
//
//							protected boolean ignore(Object message) {
//								if (message instanceof EnqueueJob || message instanceof JobFailed)
//									return false;
//
//								return true;
//							}
//						};
//					}
//				};
//
//				// Create a naked simple scheduler with our probe
//				@SuppressWarnings("serial")
//				final Props schedulerProps = new Props(new UntypedActorFactory() {
//
//					@Override
//					public Actor create() throws Exception {
//						SimpleQueuePopScheduler simpleQueuePopScheduler = new SimpleQueuePopScheduler(null);
//						simpleQueuePopScheduler.injectProbe(schedulerProbe.getRef());
//						return simpleQueuePopScheduler;
//					}
//				});
//
//				final TestActorRef<SimpleQueuePopScheduler> schedulerRef = TestActorRef.create(system, schedulerProps,
//						settings.SCHEDULER_NAME);
//				final SimpleQueuePopScheduler scheduler = schedulerRef.underlyingActor();
//				scheduler.pause();
//
//				// Create an agent
//				createAgent(
//						system,
//						new HashSet<String>(Arrays.asList(JobEnqueueingTestWorker.class.getName(),
//								TestWorker.class.getName())), null);
//
//				// Enqueue a job
//				Map<String, String> params = new HashMap<String, String>();
//				params.put("key", "value");
//				scheduler.tell(new EnqueueJob(JobEnqueueingTestWorker.class.getName(), params), null);
//
//				// Kill the queue manager, this should cause an exception as
//				// soon as the scheduler
//				// is unpaused and gives the agent the enqueued job
//				scheduler.tell(PoisonPill.getInstance(), null);
//				expectNoMsg(duration("1 second"));
//
//				scheduler.unpause();
//
//				// Expect a job failure message at the scheduler
//				JobFailed jobFailed = schedulerProbe.expectMsgClass(JobFailed.class);
//				assertEquals(JobEnqueueingTestWorker.class.getName(), jobFailed.getJob().getWorkerType());
//				assertEquals(params, jobFailed.getJob().getParams());
//			}
//		};
	}
}
