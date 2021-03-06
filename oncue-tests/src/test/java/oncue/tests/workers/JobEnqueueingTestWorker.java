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
package oncue.tests.workers;

import oncue.common.messages.Job;
import oncue.worker.AbstractWorker;

public class JobEnqueueingTestWorker extends AbstractWorker {

	@Override
	public void doWork(Job job) {
		processJob(job);
	}

	private void processJob(Job job) {
		try {
			// Give this job to a test worker
			Thread.sleep(500);
			getSchedulerClient().enqueueJob(TestWorker.class.getName(), job.getParams());
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	protected void redoWork(Job job) {
		processJob(job);
	}
}
