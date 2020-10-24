# Ecore Bikeshed

[ ![Download](https://api.bintray.com/packages/opencaesar/ecore-tools/ecore-bikeshed/images/download.svg) ](https://bintray.com/opencaesar/ecore-tools/ecore-bikeshed/_latestVersion)

A tool to generate [Bikeshed](https://tabatkins.github.io/bikeshed/) specifications from [Ecore](https://www.eclipse.org/modeling/emf/) models

## Run as CLI

MacOS/Linux:
```
./gradlew ecore-bikeshed:run --args="..."
```
Windows:
```
gradlew.bat ecore-bikeshed:run --args="..."
```
Args:
```
--input-folder-path | -i path/to/input/ecore/folder [Required]
--output-folder-path | -o path/to/output/bikeshed/folder [Required]
```

## Run as Gradle Task

```
buildscript {
	repositories {
		maven { url 'https://dl.bintray.com/opencaesar/ecore-tools' }
  		mavenCentral()
		jcenter()
	}
	dependencies {
		classpath 'io.opencaesar.ecore:ecore-bikeshed-gradle:+'
	}
}
task ecoreToBikeshed(type:io.opencaesar.ecore.bikeshed.Ecore2BikeshedTask) {
	inputFolderPath = file('path/to/input/ecore/folder') [Required]
	outputFolderPath = file('path/to/output/bikeshed/folder') [Required]
}               
```