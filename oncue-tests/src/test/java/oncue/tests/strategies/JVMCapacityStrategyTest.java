/*******************************************************************************
 * Copyright 2013 Michael Marconi
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package oncue.tests.strategies;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

import junit.framework.Assert;
import oncue.agent.JVMCapacityAgent;
import oncue.common.messages.EnqueueJob;
import oncue.common.messages.Job;
import oncue.common.messages.WorkResponse;
import oncue.scheduler.JVMCapacityScheduler;
import oncue.tests.base.ActorSystemTest;
import oncue.tests.workers.TestWorker;

import org.junit.Ignore;
import org.junit.Test;

import akka.actor.Actor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActorFactory;
import akka.testkit.JavaTestKit;
import akka.testkit.TestActorRef;

/**
 * Test the JVM memory capacity strategy by farming jobs of known size out to
 * agents with known capacities.
 */
public class JVMCapacityStrategyTest extends ActorSystemTest {

	/*-
	 *            Jobs: J1(100), J2(200), J3(50), J4(200), J5(500) 
	 *  Agent capacity: A1(500), A2(70), A3(200)
	 *
	 *  J1(100): | A2(70) | A3(200) | A1(500) |
	 *           | x      | J1      | -       |
	 *
	 *  J2(200): | A2(70) | A3(100) | A1(500) |
	 *           | x      | x       | J2      |
	 *
	 *  J3(50):  | A2(70) | A3(100) | A1(300) |
	 *           | J3     | -       | -       |
	 *
	 *  J4(200): | A2(20) | A3(100) | A1(300) |
	 *           | x      | x       | J4      |
	 *
	 *  J5(500): | A2(20) | A3(100) | A1(100) |
	 *           | x      | x       | x       |
	 *
	 *  Final allocation:  A1(J2,J4)
	 *                     A2(J3)
	 *                     A3(J1)
	 *                     Job 5 unscheduled!
	 */

	@Test
	@SuppressWarnings("serial")
	@Ignore("Ignore this test until this strategy becomes necessary")
	public void jvmCapacityStrategyTest() {
		new JavaTestKit(system) {

			{
				// Create a naked JVM capacity-aware scheduler
				final Props schedulerProps = new Props(new UntypedActorFactory() {
					@Override
					public Actor create() {
						return new JVMCapacityScheduler(null);
					}
				});
				final TestActorRef<JVMCapacityScheduler> schedulerRef = TestActorRef.create(system, schedulerProps,
						settings.SCHEDULER_NAME);
				final JVMCapacityScheduler scheduler = schedulerRef.underlyingActor();

				// Create agent probes
				final JavaTestKit agentProbe1 = createAgentProbe();
				final JavaTestKit agentProbe2 = createAgentProbe();
				final JavaTestKit agentProbe3 = createAgentProbe();

				/*
				 * Create three capacity-aware agents with pre-determined
				 * capacity
				 */
				createAgent(agentProbe1, "agent1", 500);
				createAgent(agentProbe2, "agent2", 70);
				createAgent(agentProbe3, "agent3", 200);

				// Wait for initial work response at agents
				agentProbe1.expectMsgClass(WorkResponse.class);
				agentProbe2.expectMsgClass(WorkResponse.class);
				agentProbe3.expectMsgClass(WorkResponse.class);

				/*
				 * Pause the scheduler, as we want the scheduler to see all
				 * enqueued jobs in a single batch for testing purposes
				 */
				scheduler.pause();

				/*
				 * Enqueue jobs with various sizes
				 */
				enqueueJob(schedulerRef, getRef(), 100);
				Job job1 = expectMsgClass(Job.class);

				enqueueJob(schedulerRef, getRef(), 200);
				Job job2 = expectMsgClass(Job.class);

				enqueueJob(schedulerRef, getRef(), 50);
				Job job3 = expectMsgClass(Job.class);

				enqueueJob(schedulerRef, getRef(), 200);
				Job job4 = expectMsgClass(Job.class);

				enqueueJob(schedulerRef, getRef(), 500);
				Job job5 = expectMsgClass(Job.class);

				// Unpause the scheduler
				scheduler.unpause();

				// Expect Job 2 and Job 4 at Agent 1
				WorkResponse agent1WorkResponse = agentProbe1.expectMsgClass(WorkResponse.class);
				Assert.assertEquals(2, agent1WorkResponse.getJobs().size());

				Job agent1Job2 = agent1WorkResponse.getJobs().get(0);
				Assert.assertEquals(job2.getParams().get(JVMCapacityScheduler.JOB_SIZE),
						agent1Job2.getParams().get(JVMCapacityScheduler.JOB_SIZE));

				Job agent1Job4 = agent1WorkResponse.getJobs().get(0);
				Assert.assertEquals(job4.getParams().get(JVMCapacityScheduler.JOB_SIZE),
						agent1Job4.getParams().get(JVMCapacityScheduler.JOB_SIZE));

				// Expect Job 3 at Agent 2
				WorkResponse agent2WorkResponse = agentProbe2.expectMsgClass(WorkResponse.class);
				Assert.assertEquals(1, agent2WorkResponse.getJobs().size());
				Job agent2Job3 = agent2WorkResponse.getJobs().get(0);
				Assert.assertEquals(job3.getParams().get(JVMCapacityScheduler.JOB_SIZE),
						agent2Job3.getParams().get(JVMCapacityScheduler.JOB_SIZE));

				// Expect Job 1 at Agent 3
				WorkResponse agent3WorkResponse = agentProbe3.expectMsgClass(WorkResponse.class);
				Assert.assertEquals(1, agent3WorkResponse.getJobs().size());
				Job agent3Job1 = agent3WorkResponse.getJobs().get(0);
				Assert.assertEquals(job1.getParams().get(JVMCapacityScheduler.JOB_SIZE),
						agent3Job1.getParams().get(JVMCapacityScheduler.JOB_SIZE));

				/*
				 * Now, Jobs 1 - 4 will complete and all the agents will ask for
				 * new work. Since Agent 1 is the only one big enough to fit Job
				 * 5, it will receive it.
				 */
				agent1WorkResponse = agentProbe1.expectMsgClass(duration("5 seconds"), WorkResponse.class);
				Assert.assertEquals(1, agent1WorkResponse.getJobs().size());
				Job agent1Job5 = agent1WorkResponse.getJobs().get(0);
				Assert.assertEquals(job5.getParams().get(JVMCapacityScheduler.JOB_SIZE),
						agent1Job5.getParams().get(JVMCapacityScheduler.JOB_SIZE));
			}
		};
	}

	private JavaTestKit createAgentProbe() {
		return new JavaTestKit(system) {

			{
				new IgnoreMsg() {

					@Override
					protected boolean ignore(Object message) {
						return !(message instanceof WorkResponse);
					}
				};
			}
		};
	}

	@SuppressWarnings("serial")
	private void createAgent(final JavaTestKit agentProbe, String name, final int capacity) {
		system.actorOf(new Props(new UntypedActorFactory() {

			@Override
			public Actor create() throws Exception {
				JVMCapacityAgent agent = new JVMCapacityAgent(new HashSet<String>(Arrays.asList(TestWorker.class
						.getName())), capacity);
				agent.injectProbe(agentProbe.getRef());
				return agent;
			}
		}), name);
	}

	@SuppressWarnings("serial")
	private void enqueueJob(ActorRef scheduler, ActorRef testKit, final int jobSize) {
		scheduler.tell(new EnqueueJob(TestWorker.class.getName(), new HashMap<String, String>() {

			{
				put(JVMCapacityScheduler.JOB_SIZE, new Integer(jobSize).toString());
			}
		}), testKit);
	}
}
