package io.opencaesar.ecore2bikeshed

import com.beust.jcommander.IParameterValidator
import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.ParameterException
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.ArrayList
import java.util.Collection
import java.util.HashMap
import org.apache.log4j.AppenderSkeleton
import org.apache.log4j.Level
import org.apache.log4j.LogManager
import org.eclipse.emf.codegen.ecore.genmodel.GenModel
import org.eclipse.emf.common.util.URI
import org.eclipse.emf.ecore.EPackage
import org.eclipse.emf.ecore.xcore.XcoreStandaloneSetup
import org.eclipse.xtext.resource.XtextResourceSet

class App {

	@Parameter(
		names=#["--input","-i"], 
		description="Location of Ecore input folder (Required)",
		validateWith=FolderPath, 
		required=true, 
		order=1)
	package String inputPath = null

	@Parameter(
		names=#["--output", "-o"], 
		description="Location of the Bikeshed output folder", 
		validateWith=FolderPath, 
		order=2
	)
	package String outputPath = "."

	@Parameter(
		names=#["-d", "--debug"], 
		description="Shows debug logging statements", 
		order=3
	)
	package boolean debug

	@Parameter(
		names=#["--help","-h"], 
		description="Displays summary of options", 
		help=true, 
		order=4) package boolean help

	val LOGGER = LogManager.getLogger(App)

	/*
	 * Main method
	 */
	def static void main(String ... args) {
		val app = new App
		val builder = JCommander.newBuilder().addObject(app).build()
		builder.parse(args)
		if (app.help) {
			builder.usage()
			return
		}
		if (app.debug) {
			val appender = LogManager.getRootLogger.getAppender("stdout")
			(appender as AppenderSkeleton).setThreshold(Level.DEBUG)
		}
		if (app.inputPath.endsWith('/')) {
			app.inputPath = app.inputPath.substring(0, app.inputPath.length-1)
		}
		if (app.outputPath.endsWith('/')) {
			app.outputPath = app.outputPath.substring(0, app.outputPath.length-1)
		}
		app.run()
	}

	/*
	 * Run method
	 */
	def void run() {
		LOGGER.info("=================================================================")
		LOGGER.info("                        S T A R T")
		LOGGER.info("=================================================================")
		LOGGER.info("Input Folder= " + inputPath)
		LOGGER.info("Output Folder= " + outputPath)

		val inputFolder = new File(inputPath)
		val inputFiles = collectInputFiles(inputFolder)
		
		val injector = new XcoreStandaloneSetup().createInjectorAndDoEMFRegistration();
		val inputResourceSet = injector.getInstance(XtextResourceSet);

		val outputFiles = new HashMap<String, String>

		for (inputFile : inputFiles) {
			val inputURI = URI.createFileURI(inputFile.absolutePath)
			val inputResource = inputResourceSet.getResource(inputURI, true)
			if (inputResource !== null) {
				LOGGER.info("Reading: "+inputURI)
				val ePackage = inputResource.contents.filter(EPackage).head
				val genModel = inputResource.contents.filter(GenModel).head
				val genPackage = genModel.findGenPackage(ePackage)
				val packageQName = genPackage.basePackage+'.'+ePackage.name
				outputFiles.put(outputPath+'/src-gen/'+packageQName+'.md', new EcoreToBikeshed(ePackage, outputPath).run)
			}
		}

		outputFiles.forEach[filePath, result|
			val out = new BufferedWriter(new FileWriter(filePath))
			try {
				LOGGER.info("Saving: "+filePath)
			    out.write(result.toString) 
			}
			catch (IOException e) {
			    System.out.println(e);
			}
			finally {
			    out.close();
			}
		]

		LOGGER.info("=================================================================")
		LOGGER.info("                          E N D")
		LOGGER.info("=================================================================")
	}

	// Utility methods

	def Collection<File> collectInputFiles(File directory) {
		val files = new ArrayList<File>
		for (file : directory.listFiles()) {
			if (file.isFile) {
				val ext = getFileExtension(file)
				if (ext == "genmodel" || ext == "xcore") {
					files.add(file)
				}
			} else if (file.isDirectory) {
				files.addAll(collectInputFiles(file))
			}
		}
		return files
	}

	private def String getFileExtension(File file) {
        val fileName = file.getName()
        if(fileName.lastIndexOf(".") != -1)
        	return fileName.substring(fileName.lastIndexOf(".")+1)
        else 
        	return ""
    }

	static class FolderPath implements IParameterValidator {
		override validate(String name, String value) throws ParameterException {
			val directory = new File(value)
			if (!directory.isDirectory) {
				throw new ParameterException("Parameter " + name + " should be a valid folder path");
			}
	  	}
	}
	
}
