package io.opencaesar.ecore2bikeshed

import org.gradle.api.Plugin
import org.gradle.api.Project

class GradlePlugin implements Plugin<Project> {
	
    override apply(Project project) {
    	val params = project.extensions.create('ecore2bikeshed', Ecore2BikeshedParams)
        val task = project.getTasks().create("generateBikeshed").doLast([task|
	       	App.main("-i", project.file(params.inputPath).absolutePath, "-o", project.file(params.outputPath).absolutePath) 
        ])
        val assemble = project.getTasksByName('assemble', false).head
        assemble.dependsOn(task)
    }
    
}

class Ecore2BikeshedParams {
	public var String inputPath
    public var String outputPath;
}