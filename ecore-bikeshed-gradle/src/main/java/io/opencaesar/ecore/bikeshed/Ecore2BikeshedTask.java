/**
 * 
 * Copyright 2019 California Institute of Technology ("Caltech").
 * U.S. Government sponsorship acknowledged.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */
package io.opencaesar.ecore.bikeshed;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.gradle.api.DefaultTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskExecutionException;

/**
 * A gradle task to invoke the Ecore2Bikeshed tool 
 */
public abstract class Ecore2BikeshedTask extends DefaultTask {
	
	/**
	 * Creates a new Ecore2BikeshedTask object
	 */
	public Ecore2BikeshedTask() {
	}

	/**
	 * The path to Ecore input ِfolder
	 * 
	 * @return Directory Property
	 */
	@InputDirectory
	public abstract Property<File> getInputFolderPath();
    
	/**
	 * The path of Bikeshed output folder
	 * 
	 * @return Directory Property
	 */
	@OutputDirectory
    public abstract Property<File> getOutputFolderPath();
        
    /**
     * The gradle task action logic.
     */
    @TaskAction
    public void run() {
		List<String> args = new ArrayList<String>();
		if (getInputFolderPath().isPresent()) {
			args.add("-i");
			args.add(getInputFolderPath().get().getAbsolutePath());
		}
		if (getOutputFolderPath().isPresent()) {
			args.add("-o");
			args.add(getOutputFolderPath().get().getAbsolutePath());
		}
		try {
        	Ecore2BikeshedApp.main(args.toArray(new String[0]));
		} catch (Exception e) {
			throw new TaskExecutionException(this, e);
		}
	}
}