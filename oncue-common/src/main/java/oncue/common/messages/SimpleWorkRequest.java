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
package oncue.common.messages;

import java.util.Set;

import akka.actor.ActorRef;

/**
 * A simple work request includes no more information apart from the agent that
 * is making the request.
 */
public class SimpleWorkRequest extends AbstractWorkRequest {

	private static final long serialVersionUID = -9114194566914906613L;

	public SimpleWorkRequest(ActorRef agent, Set<String> workerTypes) {
		super(agent, workerTypes);
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		for (String workerType : getWorkerTypes()) {
			builder.append("[" + workerType + "]");
		}
		return "Simple work request for worker types: " + builder.toString();
	}
}
