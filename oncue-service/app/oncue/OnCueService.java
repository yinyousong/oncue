package oncue;

import oncue.common.settings.Settings;
import oncue.common.settings.SettingsProvider;
import oncue.timedjobs.TimedJobFactory;
import play.Application;
import play.GlobalSettings;
import play.libs.Akka;
import akka.actor.Actor;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActorFactory;

import com.typesafe.config.Config;

public class OnCueService extends GlobalSettings {

	// The OnCue actor system
	private static ActorSystem system;

	/**
	 * Boot up the embedded OnCue actor system
	 */
	@SuppressWarnings("serial")
	private static void bootSystem() {
		final Settings settings = SettingsProvider.SettingsProvider.get(Akka.system());

		/*
		 * Boot a custom Akka actor system for the OnCue service components
		 */
		Config config = Akka.system().settings().config();
		system = ActorSystem.create("oncue-service", config.getConfig("oncue").withFallback(config));

		// Start the scheduler
		system.actorOf(new Props(new UntypedActorFactory() {
			@Override
			public Actor create() throws Exception {
				Class<?> schedulerClass = Class.forName(settings.SCHEDULER_CLASS);
				Class<?> backingStoreClass = null;
				if (settings.SCHEDULER_BACKING_STORE_CLASS != null)
					backingStoreClass = Class.forName(settings.SCHEDULER_BACKING_STORE_CLASS);
				return (Actor) schedulerClass.getConstructor(Class.class).newInstance(backingStoreClass);
			}
		}), settings.SCHEDULER_NAME);

		// Start up any timed jobs
		TimedJobFactory.createTimedJobs(system, settings.TIMED_JOBS_TIMETABLE);

		// Start the event stream listener
		system.actorOf(new Props(EventMachine.class), "event-stream-listener");
	}

	/**
	 * @return the embedded OnCue actor system
	 */
	public static ActorSystem system() {
		if (system.isTerminated()) {
			bootSystem();
			while (system.isTerminated()) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
				}
			}
		}
		return system;
	}

	@Override
	public void onStart(Application app) {
		bootSystem();
	}

	@Override
	public void onStop(Application app) {
		system.shutdown();
		while (!system.isTerminated()) {
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
			}
		}
	}
}
