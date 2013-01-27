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

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import oncue.backingstore.internal.BackingStore;
import oncue.messages.internal.AbstractWorkRequest;
import oncue.messages.internal.Job;
import oncue.messages.internal.JobFailed;
import oncue.messages.internal.JobProgress;
import oncue.messages.internal.SimpleMessages.SimpleMessage;
import oncue.messages.internal.WorkResponse;
import oncue.settings.Settings;
import oncue.settings.SettingsProvider;
import scala.concurrent.duration.Deadline;
import sun.management.Agent;
import akka.actor.ActorInitializationException;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Cancellable;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.remote.RemoteClientShutdown;

/**
 * A scheduler is responsible for keeping a list of registered
 * {@linkplain Agent}s, broadcasting new work to them when it arrives and
 * distributing the work using a variety of scheduling algorithms, depending on
 * the concrete implementation.
 */
public abstract class AbstractScheduler<WorkRequest extends AbstractWorkRequest> extends UntypedActor {

	// A periodic check for dead agents
	private Cancellable agentMonitor;

	// Map an agent to a deadline for deregistration
	private Map<String, Deadline> agents = new ConcurrentHashMap<>();

	// The optional persistent backing store
	protected BackingStore backingStore;

	// A scheduled check for jobs to broadcast
	private Cancellable jobsBroadcast;

	protected LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	// A flag to indicate that jobs should not be scheduled temporarily
	private boolean paused = false;

	// The map of scheduled jobs
	private ScheduledJobs scheduledJobs;

	protected Settings settings = SettingsProvider.SettingsProvider.get(getContext().system());

	private ActorRef testProbe;

	// The queue of unscheduled jobs
	protected UnscheduledJobs unscheduledJobs;

	/**
	 * @param backingStore
	 *            is either an implementation of {@linkplain BackingStore} or
	 *            null
	 * @throws NoSuchJobException
	 */
	public AbstractScheduler(Class<? extends BackingStore> backingStore) {

		if (backingStore == null) {
			unscheduledJobs = new UnscheduledJobs(null, log);
			scheduledJobs = new ScheduledJobs(null);
			log.info("{} is running without a backing store", getClass().getSimpleName());
			return;
		}

		try {
			this.backingStore = backingStore.getConstructor(ActorSystem.class, Settings.class).newInstance(
					getContext().system(), settings);
			unscheduledJobs = new UnscheduledJobs(this.backingStore, log);
			scheduledJobs = new ScheduledJobs(this.backingStore);
			log.info("{} is running, backed by {}", getClass().getSimpleName(), backingStore.getSimpleName());
		} catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException
				| NoSuchMethodException | SecurityException e) {
			throw new ActorInitializationException(getSelf(), "Failed to create a backing store from class: "
					+ backingStore.getName(), e);
		}
	}

	/**
	 * While there are jobs in the queue, continue sending a "Work available"
	 * message to all registered agents.
	 */
	private void broadcastJobs() {

		/*
		 * Don't broadcast jobs if there are no agents, no more jobs on the
		 * unscheduled queue or scheduling is paused
		 */
		if (agents.size() == 0 || unscheduledJobs.isEmpty() || paused)
			return;

		log.debug("Broadcasting jobs");

		for (String agent : agents.keySet()) {
			if (testProbe != null)
				testProbe.tell(SimpleMessage.WORK_AVAILABLE, getSelf());
			getContext().actorFor(agent).tell(SimpleMessage.WORK_AVAILABLE, getSelf());
		}

		// Tee-up another broadcast if necessary
		if (unscheduledJobs.getSize() > 0) {

			// Cancel any scheduled broadcast
			if (jobsBroadcast != null)
				jobsBroadcast.cancel();

			jobsBroadcast = getContext().system().scheduler()
					.scheduleOnce(settings.SCHEDULER_BROADCAST_JOBS_FREQUENCY, new Runnable() {

						@Override
						public void run() {
							broadcastJobs();
						}
					}, getContext().dispatcher());
		}
	}

	/**
	 * Check to see that each agent has sent a heart beat by the deadline.
	 */
	private void checkAgents() {
		for (String agent : agents.keySet()) {
			Deadline deadline = agents.get(agent);

			if (deadline.isOverdue()) {
				log.error("Found a dead agent: {}", agent);

				if (testProbe != null)
					testProbe.tell(SimpleMessage.AGENT_DEAD, getSelf());

				deregisterAgent(agent);
				rebroadcastJobs(agent);
			}
		}
	}

	/**
	 * When a job is finished or has failed, it must be removed from the
	 * scheduler's records.
	 * 
	 * @param job
	 *            is the {@linkplain Job} to clean up after
	 */
	private void cleanupJob(Job job, String agent) {
		scheduledJobs.removeJob(job, agent);
	}

	/**
	 * Deregister an agent
	 */
	private void deregisterAgent(String agent) {
		agents.remove(agent);

		/*
		 * TODO Is this necessary? If an agent is dead, we can't look it up
		 * anyway!
		 * 
		 * Stop listening to remote events
		 */
		getContext().system().eventStream().unsubscribe(getContext().actorFor(agent));
	}

	/**
	 * Dispatch jobs to agents according to entries in the schedule. This method
	 * will also keep record of the jobs scheduled to each agent, in case an
	 * agent dies.
	 * 
	 * @param schedule
	 *            is the {@linkplain Schedule} that maps agents to jobs
	 */
	protected void dispatchJobs(Schedule schedule) {
		for (Map.Entry<String, WorkResponse> entry : schedule.getEntries()) {
			ActorRef agent = getContext().actorFor(entry.getKey());
			WorkResponse workResponse = entry.getValue();

			// Assign the jobs to the agent
			scheduledJobs.addJobs(agent, workResponse.getJobs());

			// Tell the agent about the work
			agent.tell(workResponse, getSelf());
		}
	}

	/**
	 * @return the set of all registered {@linkplain Agent}s
	 */
	protected Set<String> getAgents() {
		return agents.keySet();
	}

	/**
	 * Inject a probe into this actor for testing
	 * 
	 * @param testProbe
	 *            is a JavaTestKit probe
	 */
	public void injectProbe(ActorRef testProbe) {
		this.testProbe = testProbe;
	}

	/**
	 * Set up a monitor that periodically checks for dead Agents
	 */
	private void monitorAgents() {
		agentMonitor = getContext()
				.system()
				.scheduler()
				.schedule(settings.SCHEDULER_MONITOR_AGENTS_FREQUENCY, settings.SCHEDULER_MONITOR_AGENTS_FREQUENCY,
						new Runnable() {

							@Override
							public void run() {
								getSelf().tell(SimpleMessage.CHECK_AGENTS, getSelf());
							}
						}, getContext().dispatcher());
	}

	@SuppressWarnings("unchecked")
	@Override
	public void onReceive(Object message) throws Exception {

		if (testProbe != null)
			testProbe.forward(message, getContext());

		if (message.equals(SimpleMessage.AGENT_HEARTBEAT)) {
			log.debug("Got a heartbeat from agent {}", getSender());
			registerAgent(getSender().path().toString());
		}

		else if (message instanceof RemoteClientShutdown) {
			String system = ((RemoteClientShutdown) message).getRemoteAddress().system();
			if (system.equals("oncue-agent")) {
				String agent = ((RemoteClientShutdown) message).getRemoteAddress().toString() + settings.AGENT_PATH;
				log.info("Agent {} has shut down", agent);
				deregisterAgent(agent);
				rebroadcastJobs(agent);

				if (testProbe != null)
					testProbe.tell(SimpleMessage.AGENT_SHUTDOWN, getSelf());
			}
		}

		else if (message.equals(SimpleMessage.CHECK_AGENTS)) {
			log.debug("Checking for dead agents...");
			checkAgents();
		}

		else if (message instanceof Job) {
			log.debug("Got a new job to schedule: {}", message);
			unscheduledJobs.addJob((Job) message);
			startJobsBroadcast();
		}

		else if (message instanceof AbstractWorkRequest) {
			log.debug("Got a work request from agent {}: {}", getSender(), message);
			if (unscheduledJobs.getSize() == 0 || paused)
				replyWithNoWork(getSender());
			else
				scheduleJobs((WorkRequest) message);
		}

		else if (message instanceof JobProgress) {
			log.debug("Agent reported progress of {} on job {}", ((JobProgress) message).getProgress(),
					((JobProgress) message).getJob());
			recordJobProgress((JobProgress) message, getSender().path().toString());
		}

		else if (message instanceof JobFailed) {
			log.debug("Agent reported a failed job {} ({})", ((JobFailed) message).getJob(), ((JobFailed) message)
					.getError().getMessage());
			recordJobFailure((JobFailed) message);
		}

		else {
			log.error("Unrecognised message: {}", message);
			unhandled(message);
		}
	}

	/**
	 * Pause job scheduling temporarily
	 */
	public void pause() {
		paused = true;
	}

	@Override
	public void postStop() {
		super.postStop();

		if (agentMonitor != null)
			agentMonitor.cancel();
		if (jobsBroadcast != null)
			jobsBroadcast.cancel();

		log.info("Shut down.");
	}

	@Override
	public void preStart() {
		monitorAgents();
		super.preStart();
	}

	/**
	 * In the case where an Agent has died or shutdown before completing the
	 * jobs assigned to it, we need to re-broadcast the jobs so they are run by
	 * another agent.
	 * 
	 * @param agent
	 *            is the Agent to check for incomplete jobs
	 */
	private void rebroadcastJobs(String agent) {
		if (!scheduledJobs.getJobs(agent).isEmpty()) {

			// Grab the list of jobs scheduled for this agent
			List<Job> agentJobs = new ArrayList<>();
			for (Job scheduledJob : scheduledJobs.getJobs(agent)) {
				agentJobs.add(scheduledJob);
			}

			for (Job job : agentJobs) {

				// Remove job from the agent
				scheduledJobs.removeJob(job, agent);

				// Add jobs back onto the unscheduled queue
				unscheduledJobs.addJob(job);
			}
		}
		broadcastJobs();
	}

	/**
	 * Record the details of a failed job
	 * 
	 * @param jobFailed
	 *            contains both the failed job and the cause of failure
	 */
	private void recordJobFailure(JobFailed jobFailed) {
		if (backingStore != null)
			backingStore.persistJobFailure(jobFailed);
	}

	/**
	 * Record any progress made against a job. If the job is complete, remove it
	 * from the jobs scheduled against an agent.
	 * 
	 * @param jobProgress
	 *            describes the job and associated progress.
	 */
	private void recordJobProgress(JobProgress jobProgress, String agent) {
		if (backingStore != null)
			backingStore.persistJobProgress(jobProgress);
		if (jobProgress.getProgress() == 1.0) {
			log.debug("{} is complete.", jobProgress.getJob());
			cleanupJob(jobProgress.getJob(), agent);
		}
	}

	/**
	 * Register the heartbeat of an {@linkplain Agent}, capturing the heartbeat
	 * time as a timestamp. If this is a new Agent, return a message indicating
	 * that it has been registered.
	 * 
	 * @param agent
	 *            is the {@linkplain Agent} to register
	 */
	private void registerAgent(String agent) {
		if (!agents.containsKey(agent)) {
			getContext().actorFor(agent).tell(SimpleMessage.AGENT_REGISTERED, getSelf());
			getContext().system().eventStream().subscribe(getSelf(), RemoteClientShutdown.class);
			log.info("Registered agent: {}", agent);
		}

		agents.put(agent, settings.SCHEDULER_AGENT_HEARTBEAT_TIMEOUT.fromNow());
	}

	/**
	 * Send a response to the requesting agent containing a
	 * {@linkplain WorkResponse} with no jobs.
	 */
	private void replyWithNoWork(ActorRef agent) {
		agent.tell(new WorkResponse(), getSelf());
	}

	/**
	 * Create a schedule that maps agents to work responses. Once the schedule
	 * has been created, the work should be dispatched by calling the
	 * <i>dispatchJobs</i> method.
	 */
	protected abstract void scheduleJobs(WorkRequest workRequest);

	/**
	 * Schedule a jobs broadcast. Cancel any previously scheduled broadcast, to
	 * ensure quiescence in the case where lots of new jobs arrive in a short
	 * time.
	 */
	private void startJobsBroadcast() {
		if (jobsBroadcast != null && !jobsBroadcast.isCancelled())
			jobsBroadcast.cancel();

		jobsBroadcast = getContext().system().scheduler()
				.scheduleOnce(settings.SCHEDULER_BROADCAST_JOBS_QUIESCENCE_PERIOD, new Runnable() {

					@Override
					public void run() {
						broadcastJobs();
					}
				}, getContext().dispatcher());
	}

	/**
	 * Allow the scheduler to continue scheduling jobs.
	 */
	public void unpause() {
		paused = false;
	}
}
