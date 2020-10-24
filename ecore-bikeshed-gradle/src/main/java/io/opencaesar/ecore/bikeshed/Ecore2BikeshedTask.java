package io.opencaesar.ecore.bikeshed;

import java.util.ArrayList;
import java.util.List;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskExecutionException;

public class Ecore2BikeshedTask extends DefaultTask {
	
	public String inputFolderPath;
    
    public String outputFolderPath;
        
    @TaskAction
    public void run() {
		List<String> args = new ArrayList<String>();
		if (inputFolderPath != null) {
			args.add("-i");
			args.add(inputFolderPath);
		}
		if (outputFolderPath != null) {
			args.add("-o");
			args.add(outputFolderPath);
		}
		try {
        	Ecore2BikeshedApp.main(args.toArray(new String[0]));
		} catch (Exception e) {
			throw new TaskExecutionException(this, e);
		}
	}
}