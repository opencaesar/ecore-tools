# Bikeshed Generator for Ecore

[![Gitpod - Code Now](https://img.shields.io/badge/Gitpod-code%20now-blue.svg?longCache=true)](https://gitpod.io#https://github.com/opencaesar/ecore-bikeshed)
[![Build Status](https://travis-ci.org/opencaesar/ecore-bikeshed.svg?branch=master)](https://travis-ci.org/opencaesar/ecore-bikeshed)
[ ![Download](https://api.bintray.com/packages/opencaesar/ecore-bikeshed/ecore2bikeshed/images/download.svg) ](https://bintray.com/opencaesar/ecore-bikeshed/ecore2bikeshed/_latestVersion)

An [Bikeshed](https://github.com/tabatkins/bikeshed) generator for [Ecore](https://www.eclipse.org/modeling/emf/)

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

## Run

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

## Release

Replace \<version\> by the version, e.g., 1.2
```
  git tag -a <version> -m "<version>"
  git push origin <version>
```
