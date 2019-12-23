# Bikeshed Generator for Ecore

[![Build Status](https://travis-ci.org/opencaesar/ecore-bikeshed.svg?branch=master)](https://travis-ci.org/opencaesar/ecore-bikeshed)
[ ![Download](https://api.bintray.com/packages/opencaesar/ecore-bikeshed/ecore2bikeshed/images/download.svg) ](https://bintray.com/opencaesar/ecore-bikeshed/ecore2bikeshed/_latestVersion)

A [Bikeshed](https://github.com/tabatkins/bikeshed) generator for [Ecore](https://www.eclipse.org/modeling/emf/) that can be run as an app from the Terminal or as a Gradle plugin.

## Clone
```
    git clone https://github.com/opencaesar/ecore-bikeshed.git
    cd ecore-bikeshed
```
      
## Build
Requirements: java 8, node 8.x, 
```
    cd ecore2bikeshed
    ./gradlew build
```

## Run from Terminal

MacOS/Linux:
```
    cd ecore2bikeshed
    ./gradlew run --args="-i path/to/ecore/folder -o path/to/bikeshed/folder"
```
Windows:
```
    cd ecore2bikeshed
    gradlew.bat run --args="-i path/to/ecore/folder -o path/to/bikeshed/folder"
```

## Run from Gradle

Add the following to an Ecore project's build.gradle:
```
buildscript {
	repositories {
		maven { url 'https://dl.bintray.com/opencaesar/ecore-bikeshed' }
		jcenter()
	}
	dependencies {
		classpath 'io.opencaesar.bikeshed:ecore2bikeshed:+'
	}
}

apply plugin: 'io.opencaesar.ecore2bikeshed'

ecore2bikeshed {
	inputPath = 'path/to/ecore/folder'
	outputPath = 'path/to/bikeshed/folder'
}

task clean(type: Delete) {
	delete 'path/to/bikeshed/folder'
}
```

## Release

Replace \<version\> by the version, e.g., 1.2
```
  git tag -a <version> -m "<version>"
  git push origin <version>
```
