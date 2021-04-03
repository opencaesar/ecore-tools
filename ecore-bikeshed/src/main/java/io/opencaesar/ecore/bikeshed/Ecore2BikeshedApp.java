package io.opencaesar.ecore.bikeshed;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

import org.apache.log4j.Appender;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.xcore.XcoreStandaloneSetup;
import org.eclipse.xtext.resource.XtextResourceSet;

import com.beust.jcommander.IParameterValidator;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.common.base.Objects;
import com.google.common.io.CharStreams;
import com.google.inject.Injector;

@SuppressWarnings("all")
public class Ecore2BikeshedApp {

	@Parameter(
		names = { "--input", "-i" },
		description = "Path of Ecore input folder (Required)",
		validateWith = Ecore2BikeshedApp.InputFolderPath.class,
		required = true,
		order = 1)
	String inputFolderPath = null;

	@Parameter(
		names = { "--output", "-o" },
		description = "Location of the Bikeshed output folder (Required)",
		validateWith = Ecore2BikeshedApp.OutputFolderPath.class,
		required = true,
		order = 2)
	String outputFolderPath = ".";

	@Parameter(
		names = { "--debug", "-d" },
		description = "Shows debug logging statements",
		order = 3)
	boolean debug;

	@Parameter(
		names = { "--help", "-h" },
		description = "Displays summary of options",
		help = true,
		order = 4)
	boolean help;

	@Parameter(
		names = { "--version", "-v" },
		description = "Displays app version",
		help = true,
		order = 5)
	boolean version;

	private final Logger LOGGER = LogManager.getLogger(Ecore2BikeshedApp.class);

	/**
	 * Main method
	 * 
	 * @param args The arguments passed to the application
	 */
	public static void main(final String... args) {
		final Ecore2BikeshedApp app = new Ecore2BikeshedApp();
		final JCommander builder = JCommander.newBuilder().addObject(app).build();
		builder.parse(args);
		if (app.version) {
			System.out.println(app.getAppVersion());
			return;
		}
		if (app.help) {
			builder.usage();
			return;
		}
		if (app.debug) {
			final Appender appender = LogManager.getRootLogger().getAppender("stdout");
			((AppenderSkeleton) appender).setThreshold(Level.DEBUG);
		}
		if (app.inputFolderPath.endsWith(File.separator)) {
			app.inputFolderPath = app.inputFolderPath.substring(0, app.inputFolderPath.length()-1);
		}
		if (app.outputFolderPath.endsWith(File.separator)) {
			app.outputFolderPath = app.outputFolderPath.substring(0, app.outputFolderPath.length()-1);
		}
		app.run();
	}

	/**
	 * Run method
	 */
	public void run() {
		LOGGER.info("=================================================================");
		LOGGER.info("                        S T A R T ");
		LOGGER.info("                    Ecore to Bikeshed "+getAppVersion());
		LOGGER.info("=================================================================");
		LOGGER.info("Input Folder= " + inputFolderPath);
		LOGGER.info("Output Folder= " + outputFolderPath);

		final File inputFolder = new File(this.inputFolderPath);
		final Collection<File> inputFiles = this.collectInputFiles(inputFolder);
		
		final Injector injector = new XcoreStandaloneSetup().createInjectorAndDoEMFRegistration();
		final XtextResourceSet inputResourceSet = injector.getInstance(XtextResourceSet.class);
		
		final HashMap<File, String> outputFiles = new HashMap<>();
		
		for (final File inputFile : inputFiles) {
			final URI inputURI = URI.createFileURI(inputFile.getAbsolutePath());
			final Resource inputResource = inputResourceSet.getResource(inputURI, true);
			if ((inputResource != null)) {
				this.LOGGER.info(("Reading: " + inputURI));
				String relativePath = this.outputFolderPath + File.separator + inputFolder.toURI().relativize(inputFile.toURI()).getPath();
				final File outputFile = new File(relativePath.substring(0, relativePath.lastIndexOf(".") + 1) + "md");
				outputFiles.put(outputFile, new Ecore2Bikeshed(inputResource, this.outputFolderPath).run());
			}
		}
		
		outputFiles.forEach((File outputFile, String result) -> {
			BufferedWriter out = null;
			try {
				outputFile.getParentFile().mkdirs();
				final String filePath = outputFile.getCanonicalPath();
				out = new BufferedWriter(new FileWriter(filePath));
				this.LOGGER.info(("Saving: " + filePath));
				out.write(result.toString());
			} catch (final Exception e) {
			    System.out.println(e);
			} 
			try {
				if (out !=null) {
					out.close();
				}
			} catch(Exception e) {
			    System.out.println(e);
			}
		});
		
		this.LOGGER.info("=================================================================");
		this.LOGGER.info("                          E N D");
		this.LOGGER.info("=================================================================");
	}

	public Collection<File> collectInputFiles(final File directory) {
		final ArrayList<File> files = new ArrayList<File>();
		for (final File file : directory.listFiles()) {
			if (file.isFile()) {
				final String ext = this.getFileExtension(file);
				if ((Objects.equal(ext, "genmodel") || Objects.equal(ext, "xcore"))) {
					files.add(file);
				}
			} else if (file.isDirectory()) {
				files.addAll(this.collectInputFiles(file));
			}
		}
		return files;
	}

	private String getFileExtension(final File file) {
		final String fileName = file.getName();
		if (fileName.lastIndexOf(".") != (-1)) {
			return fileName.substring(fileName.lastIndexOf(".") + 1);
		} else {
			return "";
		}
	}

	/**
	 * Get application version id from properties file.
	 * 
	 * @return version string from build.properties or UNKNOWN
	 */
	public String getAppVersion() {
		String version = "UNKNOWN";
		try {
			final InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream("version.txt");
			final InputStreamReader reader = new InputStreamReader(input);
			version = CharStreams.toString(reader);
		} catch (IOException e) {
			LOGGER.error("Could not read version.txt file." + e, e);
		}
		return version;
	}

	public static class InputFolderPath implements IParameterValidator {
		@Override
		public void validate(final String name, final String value) throws ParameterException {
			final File directory = new File(value).getAbsoluteFile();
			if (!directory.isDirectory()) {
				throw new ParameterException("Parameter "+name+" should be a valid folder path: "+directory);
			}
		}
	}

	public static class OutputFolderPath implements IParameterValidator {
		@Override
		public void validate(final String name, final String value) throws ParameterException {
			final File directory = new File(value).getAbsoluteFile();
			if (!directory.isDirectory()) {
				final boolean created = directory.mkdirs();
				if ((!created)) {
					throw new ParameterException((("Parameter " + name) + " should be a valid folder path"));
				}
			}
		}
	}
}
