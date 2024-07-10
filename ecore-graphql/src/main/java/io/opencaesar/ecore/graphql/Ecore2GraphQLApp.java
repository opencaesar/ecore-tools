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
package io.opencaesar.ecore.graphql;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
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
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xcore.XcoreStandaloneSetup;

import com.beust.jcommander.IParameterValidator;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.common.base.Objects;

/**
 * An app to generate GraphQL interface from Ecore files
 */
public class Ecore2GraphQLApp {

    @Parameter(
            names = {"--input", "-i"},
            description = "Path of Ecore input folder (Required)",
            validateWith = InputFolderPath.class,
            required = true,
            order = 1)
    private String inputFolderPath = null;

    @Parameter(
            names = {"--output", "-o"},
            description = "Location of the GraphQL output folder (Required)",
            validateWith = OutputFolderPath.class,
            required = true,
            order = 2)
    private String outputFolderPath = ".";

    @Parameter(
            names = {"--debug", "-d"},
            description = "Shows debug logging statements",
            order = 3)
    private boolean debug;

    @Parameter(
            names = {"--help", "-h"},
            description = "Displays summary of options",
            help = true,
            order = 4)
    private boolean help;

    @Parameter(
            names = {"--version", "-v"},
            description = "Displays app version",
            help = true,
            order = 5)
    private boolean version;

    private final Logger LOGGER = LogManager.getLogger(Ecore2GraphQLApp.class);

    /**
     * Default constructor
     */
    public Ecore2GraphQLApp() {
    }
    
    /**
     * The main entry point to the app 
     * 
     * @param args The args of the app
     */
    public static void main(final String... args) {
        final Ecore2GraphQLApp app = new Ecore2GraphQLApp();
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
            app.inputFolderPath = app.inputFolderPath.substring(0, app.inputFolderPath.length() - 1);
        }
        if (app.outputFolderPath.endsWith(File.separator)) {
            app.outputFolderPath = app.outputFolderPath.substring(0, app.outputFolderPath.length() - 1);
        }
        app.run();
    }

    /**
     * The run method
     */
    public void run() {
        LOGGER.info("=================================================================");
        LOGGER.info("                        S T A R T ");
        LOGGER.info("                    Ecore to GraphQL " + getAppVersion());
        LOGGER.info("=================================================================");
        LOGGER.info("Input Folder= " + inputFolderPath);
        LOGGER.info("Output Folder= " + outputFolderPath);

        final File inputFolder = new File(this.inputFolderPath);
        final Collection<File> inputFiles = this.collectInputFiles(inputFolder);

		XcoreStandaloneSetup.doSetup();
		final ResourceSet inputResourceSet = new ResourceSetImpl();

        final HashMap<File, String> outputFiles = new HashMap<>();

        for (final File inputFile : inputFiles) {
            final URI inputURI = URI.createFileURI(inputFile.getAbsolutePath());
            final Resource inputResource = inputResourceSet.getResource(inputURI, true);
            if ((inputResource != null)) {
                this.LOGGER.info(("Reading: " + inputURI));
                String relativePath = this.outputFolderPath +
                        File.separator +
                        inputFolder.toURI().relativize(inputFile.toURI()).getPath();
                final File outputFile =
                        new File(relativePath.substring(0, relativePath.lastIndexOf(".") + 1) + "graphqls");
                final Ecore2GraphQL e2g = new Ecore2GraphQL(inputResource);
                e2g.analyze();
                outputFiles.put(outputFile, e2g.convert());
            }
        }

        outputFiles.forEach((File outputFile, String result) -> {
            BufferedWriter out = null;
            try {
                //noinspection ResultOfMethodCallIgnored
                outputFile.getParentFile().mkdirs();
                final String filePath = outputFile.getCanonicalPath();
                out = new BufferedWriter(new FileWriter(filePath));
                this.LOGGER.info(("Saving: " + filePath));
                out.write(result);
            } catch (final Exception e) {
                this.LOGGER.error("Error saving "+outputFile+" : "+e.getMessage(), e);
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Exception e) {
                this.LOGGER.error("Error closing "+outputFile+" : "+e.getMessage(), e);
            }
        });

        this.LOGGER.info("=================================================================");
        this.LOGGER.info("                          E N D");
        this.LOGGER.info("=================================================================");
    }

    private Collection<File> collectInputFiles(final File directory) {
        final ArrayList<File> files = new ArrayList<>();
        for (final File file : java.util.Objects.requireNonNull(directory.listFiles())) {
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
    private String getAppVersion() {
        var version = this.getClass().getPackage().getImplementationVersion();
        return (version != null) ? version : "<SNAPSHOT>";
    }

    /**
     * A validator for input folder path
     */
    public static class InputFolderPath implements IParameterValidator {

    	/**
    	 * Default constructor
    	 */
    	public InputFolderPath() {
    	}

    	@Override
        public void validate(final String name, final String value) throws ParameterException {
            final File directory = new File(value).getAbsoluteFile();
            if (!directory.isDirectory()) {
                throw new ParameterException("Parameter " + name + " should be a valid folder path: " + directory);
            }
        }
    }

    /**
     * A validator for output folder path
     */
    public static class OutputFolderPath implements IParameterValidator {
    	
    	/**
    	 * Default constructor
    	 */
    	public OutputFolderPath() {
    	}
    	
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
