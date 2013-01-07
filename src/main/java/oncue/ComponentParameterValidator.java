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
package oncue;

import com.beust.jcommander.IParameterValidator;
import com.beust.jcommander.ParameterException;

/**
 * Valid components include 'service', 'agent' and 'api'
 */
public class ComponentParameterValidator implements IParameterValidator {

	@Override
	public void validate(String name, String value) throws ParameterException {
		if (!value.equals("service") && !value.equals("agent") && !value.equals("api"))
			throw new ParameterException(value
					+ " is an unrecognised component type. Valid components are include 'service', 'agent' and 'api'");
	}

}
