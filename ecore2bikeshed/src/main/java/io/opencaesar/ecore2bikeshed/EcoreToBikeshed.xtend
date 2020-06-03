package io.opencaesar.ecore2bikeshed

import java.nio.file.Files
import java.nio.file.Paths
import java.util.ArrayList
import java.util.List
import java.util.regex.Pattern
import java.util.stream.Collectors
import net.sourceforge.plantuml.FileFormat
import net.sourceforge.plantuml.FileFormatOption
import net.sourceforge.plantuml.SourceStringReader
import org.eclipse.emf.ecore.EClass
import org.eclipse.emf.ecore.EClassifier
import org.eclipse.emf.ecore.EEnum
import org.eclipse.emf.ecore.EModelElement
import org.eclipse.emf.ecore.ENamedElement
import org.eclipse.emf.ecore.EPackage
import org.eclipse.emf.ecore.EStructuralFeature
import org.eclipse.emf.ecore.EcorePackage
import org.eclipse.emf.ecore.resource.Resource

class EcoreToBikeshed {

	val Resource inputResource 
	val String outputPath

	static val BIKESHED_HEADINGS = "https://tabatkins.github.io/bikeshed/headings"
	static val BIKESHED = "https://tabatkins.github.io/bikeshed"
	static enum Annotation {
		heading
	}
		
	new(Resource inputResource, String outputPath) {
		this.inputResource = inputResource
		this.outputPath = outputPath
	}
	
	def run() {
		val ePackage = inputResource.contents.filter(EPackage).head
		ePackage.generate.toString
	}
	
	protected def generate(EPackage ePackage) '''
		# «ePackage.heading» # {#«ePackage.heading.replaceAll(' ', '')»}
		«ePackage.documentation»
		
		«val headings = ePackage.headings»
		«headings.add(headings.length, null)»
		«FOR heading : headings»
		«val classifiers = ePackage.EClassifiers.filter[c|c.heading == heading].sortBy[name]»
		«IF !classifiers.empty»
		## «heading?.replaceAll("([^_])([A-Z])", "$1 $2")?:"Other"» ## {#«heading?:"Other"»}
		«generateClassDiagram(heading, ePackage, classifiers)»
		
		«FOR eClassifier : classifiers»
		«IF eClassifier instanceof EClass»
		### <dfn>«IF eClassifier.abstract»*«ENDIF»«eClassifier.name»«IF eClassifier.abstract»*«ENDIF»</dfn> ### {#«eClassifier.name»}
			«eClassifier.documentation»
			
			«val superClasses = eClassifier.ESuperTypes»
			«IF !superClasses.empty»
			*Super classes:*
			«superClasses.sortBy[name].map['[='+name+'=]'].join(', ')»
			«ENDIF»
			
			«val subClasses = eClassifier.eResource.resourceSet.allContents.filter(EClass).filter[ESuperTypes.contains(eClassifier)].toList»
			«IF !subClasses.empty»
			*Sub classes:*
			«subClasses.map['[='+name+'=]'].join(', ')»
			«ENDIF»

			«val attributes = eClassifier.EAttributes»
			«val references = eClassifier.EReferences»
			«IF !attributes.empty || !references.empty»
			*Properties:*
			«FOR eAttribute : attributes»
			«IF eAttribute.EType instanceof EEnum»
			* **«eAttribute.name»** : [=«eAttribute.EType.name»=] [«eAttribute.multiplicity»]
			«ELSE»
			* **«eAttribute.name»** : «eAttribute.EType.label» [«eAttribute.multiplicity»]
			«ENDIF»
			
				«eAttribute.documentation»
			«ENDFOR»
			«FOR eReference : references»
			* **«eReference.name»** : [=«eReference.EType.name»=] [«eReference.multiplicity»]
			
				«eReference.documentation»			
			«ENDFOR»
			«ENDIF»
		«ELSEIF eClassifier instanceof EEnum»
		### <dfn>«eClassifier.name»</dfn> ### {#«eClassifier.name»}
			«eClassifier.documentation»
					
			*Literals:*
			«FOR eLiteral : eClassifier.ELiterals»
			* **«eLiteral.name»**
			
				«eLiteral.documentation»
			«ENDFOR»
		«ENDIF»
		
		«ENDFOR»
		«ENDIF»
		
		«ENDFOR»
	'''
	
	protected def String qualifiedName(ENamedElement element) {
		val parent = element.eContainer
		return (if (parent instanceof ENamedElement) qualifiedName(parent)+'-' else '') + element.name
	}
	
	protected  def String documentation(ENamedElement element) {
		element.EAnnotations.findFirst[source == "http://www.eclipse.org/emf/2002/GenModel"]?.details?.get("documentation")?:""
	}
	
	protected def String getLabel(EClassifier classifier) {
		switch(classifier.name) {
			case EcorePackage.Literals.ESTRING.name: 'String'
			case EcorePackage.Literals.EINT.name: 'Integer'
			case EcorePackage.Literals.EINTEGER_OBJECT.name: 'Integer'
			case EcorePackage.Literals.EBOOLEAN.name: 'Boolean'
			case EcorePackage.Literals.EDOUBLE.name: 'Double'
			case EcorePackage.Literals.EDOUBLE_OBJECT.name: 'Double'
			case EcorePackage.Literals.EFLOAT.name: 'Float'
			case EcorePackage.Literals.EFLOAT_OBJECT.name: 'Float'
			case EcorePackage.Literals.EBIG_DECIMAL.name: 'BigDecimal'
			default: classifier.name
		}
	}

	protected def String generateClassDiagram(String group, EPackage ePackage, List<EClassifier> classifiers) '''
		«generateClassDiagram('''«outputPath»/images/«ePackage.name»-«group».svg''', generatePlatUMLDiagram(classifiers))»
		<pre class=include>
		path: images/«ePackage.name»-«group».svg
		</pre>
	'''
	
	protected def String generatePlatUMLDiagram(List<EClassifier> classifiers) '''
		@startuml
		skinparam classBackgroundColor LightGray
		skinparam classBorderColor Black
		skinparam enumBorderColor Black
		skinparam ArrowColor Black
		hide methods
		«FOR classifier : classifiers»
			«IF classifier instanceof EClass»
				«IF classifier.abstract»abstract «ENDIF»class «classifier.name» [[#«classifier.name»]] #white {
					«FOR attribute : classifier.EAttributes»
						«attribute.name» : «attribute.EType.label»
					«ENDFOR»
					«FOR reference : classifier.EReferences.filter[!isContainment]»
						«reference.name» : «reference.EType.label» [«reference.multiplicity»]
					«ENDFOR»
				}
				«FOR superClass : classifier.ESuperTypes.filter[c|!classifiers.contains(c)]»
					«IF superClass.abstract»abstract «ENDIF»class «superClass.name» [[#«superClass.name»]]
					hide «superClass.name» members
				«ENDFOR»
				«FOR type : classifier.EReferences.filter[isContainment].map[EType].filter(EClass).filter[c|!classifiers.contains(c)]»
					«IF type.abstract»abstract «ENDIF»class «type.name» [[#«type.name»]]
					hide «type.name» members
				«ENDFOR»
				«FOR superClass : classifier.ESuperTypes»
					«classifier.name» -up-|> «superClass.name» [[#«superClass.name»]]
				«ENDFOR»
				«FOR reference : classifier.EReferences.filter[isContainment]»
					«classifier.name» *--> "«reference.multiplicity»" «reference.EType.name» : «reference.name»
				«ENDFOR»
			«ELSEIF classifier instanceof EEnum»
				enum «classifier.name» [[#«classifier.name»]] #white {
					«FOR literal : classifier.ELiterals»
						«literal.name»
					«ENDFOR»
				}
			«ENDIF»
		«ENDFOR»		
		@enduml
	'''

	protected def String getMultiplicity(EStructuralFeature feature) {
		if (feature.isMany) {
			return "*"
		} else if (feature.isRequired) {
			return "1"
		} else {
			return "0..1"
		}
	}

	protected def void generateClassDiagram(String path, String content) {
		val pumlReader = new SourceStringReader(content, "UTF-8")
      	val svgFile = Paths.get(path)
      	svgFile.getParent.toFile.mkdirs()
      	val os = Files.newOutputStream(svgFile)
      	pumlReader.outputImage(os, new FileFormatOption(FileFormat.SVG))
      	
      	// remove the id properties from the SVG since they cause conflicts
        val p = Pattern.compile('(id="[^"]*")')
        val lines = Files.lines(svgFile);
        val replaced = lines.map[line|
        	var varLine = line
	        val m = p.matcher(line)
	        if (m.find()) {// skip the first id (affecting style)
		        while (m.find) {
		        	varLine = varLine.replaceAll(m.group(1), '')
		        }
	        }
	        varLine
        ].collect(Collectors.toList())
        Files.write(svgFile, replaced)
        lines.close()
	}

	protected def getHeadings(EPackage ePackage) {
		new ArrayList(ePackage.EAnnotations.filter[source == BIKESHED_HEADINGS].flatMap[details].map[key].toList)
	}

	protected def getHeading(ENamedElement element) {
		element.getAnnotationValue(Annotation.heading,element.name)
	}
	
	protected def isAnnotationSet(EModelElement element, Annotation annotation) {
		getAnnotationValue(element, annotation) == "true"
	}

	protected def getAnnotationValue(EModelElement element, Annotation annotation, String defaultValue) {
		getAnnotationValue(element, annotation) ?: defaultValue
	}

	protected def getAnnotationValue(EModelElement element, Annotation annotation) {
		element.EAnnotations.findFirst[BIKESHED == source]?.details?.get(annotation.toString)
	}

}