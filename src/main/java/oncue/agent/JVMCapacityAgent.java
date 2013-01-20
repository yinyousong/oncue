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
package oncue.agent;

import java.util.List;

import oncue.agent.internal.AbstractAgent;
import oncue.agent.internal.WorkerTypeException;
import oncue.messages.JVMCapacityWorkRequest;

/**
 * This agent will report a snapshot of the JVM memory capacity of this node
 * when requesting work.
 */
public class JVMCapacityAgent extends AbstractAgent {

	// Only used for testing
	private long testCapacity;

	public JVMCapacityAgent(List<String> workerTypes) throws WorkerTypeException {
		super(workerTypes);
	}

	/**
	 * This constructor should only be used for testing!
	 * 
	 * @param testCapacity
	 *            overrides the maximum number of jobs to deal with concurrently
	 */
	public JVMCapacityAgent(List<String> workerTypes, long testCapacity) throws WorkerTypeException {
		super(workerTypes);
		this.testCapacity = testCapacity;
	}

	@Override
	protected void requestWork() {
		if (testProbe != null) {
			getScheduler().tell(new JVMCapacityWorkRequest(getSelf(), testCapacity, testCapacity, testCapacity),
					getSelf());
			return;
		}

		long freeMemory = Runtime.getRuntime().freeMemory();
		long totalMemory = Runtime.getRuntime().totalMemory();
		long maxMemory = Runtime.getRuntime().maxMemory();

		getScheduler().tell(new JVMCapacityWorkRequest(getSelf(), freeMemory, totalMemory, maxMemory), getSelf());
	}

}
