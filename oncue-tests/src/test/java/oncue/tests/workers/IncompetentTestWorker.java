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
package oncue.tests.workers;

import oncue.common.messages.Job;
import oncue.worker.AbstractWorker;

/**
 * This test worker has a nasty habit of performing dodgy arithmetic!
 */
public class IncompetentTestWorker extends AbstractWorker {

	@Override
	public void doWork(Job job) {
		processJob();
	}

	private void processJob() {
		double progress = 0.0;
		for (int i = 0; i < 3; i++) {
			progress += 0.25;

			/*
			 * WOOP WOOP!
			 */
			@SuppressWarnings("unused")
			int result;
			if (i == 1)
				result = 3 / 0;

			try {
				Thread.sleep(500);
				reportProgress(progress);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	protected void redoWork(Job job) {
		processJob();
	}
}
