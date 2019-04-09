package io.opencaesar.ecore2bikeshed

import java.io.File
import java.nio.file.Files
import java.util.List
import net.sourceforge.plantuml.FileFormat
import net.sourceforge.plantuml.FileFormatOption
import net.sourceforge.plantuml.SourceStringReader
import org.eclipse.emf.ecore.EClass
import org.eclipse.emf.ecore.EClassifier
import org.eclipse.emf.ecore.EEnum
import org.eclipse.emf.ecore.ENamedElement
import org.eclipse.emf.ecore.EPackage
import org.eclipse.emf.ecore.EcorePackage
import org.eclipse.emf.ecore.EReference
import java.util.ArrayList

class EcoreToBikeshed {

	static val OML = "http://opencaesar.io/Oml"

	val EPackage ePackage 
	val String outputPath
	
	new(EPackage ePackage, String outputPath) {
		this.ePackage = ePackage
		this.outputPath = outputPath
	}
	
	def run() {
		ePackage.generate.toString
	}
	
	def generate(EPackage ePackage) '''
		# Package «ePackage.name» # {#«ePackage.qualifiedName»}
		«ePackage.documentation»
		«val groups = new ArrayList(ePackage.EAnnotations.filter[source == OML].flatMap[details].map[key].toList)»
		«groups.add(groups.length, null)»
		«FOR group : groups»
		«val classifiers = ePackage.EClassifiers.filter[EAnnotations.filter[source == OML].flatMap[details].findFirst[key == "subsection"]?.value == group].sortBy[name]»
		«IF !classifiers.empty»
		## «group.replaceAll("([^_])([A-Z])", "$1 $2")?:"Other"» ## {#group-«group?:"Other"»}
		«generateClassDiagram(group, ePackage, classifiers)»
		«FOR eClassifier : classifiers»
		«IF eClassifier instanceof EClass»
		### <dfn>«IF eClassifier.abstract»*«ENDIF»«eClassifier.name»«IF eClassifier.abstract»*«ENDIF»</dfn> ### {#«eClassifier.qualifiedName»}
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
			* **«eAttribute.name»** : [=«eAttribute.EType.name»=]«IF eAttribute.isMany» [*]«ENDIF»
			«ELSE»
			* **«eAttribute.name»** : «eAttribute.EType.label»«IF eAttribute.isMany» [*]«ENDIF»
			«ENDIF»
			«eAttribute.documentation»
			«ENDFOR»
			«FOR eReference : references»
			* **«eReference.name»** : [=«eReference.EType.name»=]«IF eReference.isMany» [*]«ENDIF»
			«eReference.documentation»			
			«ENDFOR»
			«ENDIF»
		«ELSEIF eClassifier instanceof EEnum»
		### <dfn>«eClassifier.name»</dfn> ### {#«eClassifier.qualifiedName»}
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
	
	def String qualifiedName(ENamedElement element) {
		val parent = element.eContainer
		return (if (parent instanceof ENamedElement) qualifiedName(parent)+'-' else '') + element.name
	}
	
	def String documentation(ENamedElement element) {
		element.EAnnotations.findFirst[source == "http://www.eclipse.org/emf/2002/GenModel"]?.details?.get("documentation")?:""
	}
	
	def String getLabel(EClassifier classifier) {
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

	def String generateClassDiagram(String group, EPackage ePackage, List<EClassifier> classifiers) '''
		«generateClassDiagram('''«outputPath»/images/«ePackage.name»-«group».svg''', generatePlatUMLDiagram(classifiers))»
		<pre class=include>
		path: images/«ePackage.name»-«group».svg
		</pre>
	'''
	
	def String generatePlatUMLDiagram(List<EClassifier> classifiers) '''
		@startuml
		skinparam classBackgroundColor LightGray
		skinparam classBorderColor Black
		skinparam enumBorderColor Black
		skinparam ArrowColor Black
		hide methods
		«FOR classifier : classifiers»
		«IF classifier instanceof EClass»
		«IF classifier.abstract»abstract «ENDIF»class «classifier.name» [[#«classifier.qualifiedName»]] #white {
			«FOR attribute : classifier.EAttributes»
			«attribute.name» : «attribute.EType.label»
			«ENDFOR»
			«FOR reference : classifier.EReferences.filter[!isContainment]»
			«reference.name» : «reference.EType.label» [«reference.multiplicity»]
			«ENDFOR»
		}
		«FOR superClass : classifier.ESuperTypes.filter[c|!classifiers.contains(c)]»
		«IF superClass.abstract»abstract «ENDIF»class «superClass.name» [[#«superClass.qualifiedName»]]
		hide «superClass.name» members
		«ENDFOR»
		«FOR type : classifier.EReferences.filter[isContainment].map[EType].filter(EClass).filter[c|!classifiers.contains(c)]»
		«IF type.abstract»abstract «ENDIF»class «type.name» [[#«type.qualifiedName»]]
		hide «type.name» members
		«ENDFOR»
		«FOR superClass : classifier.ESuperTypes»
		«classifier.name» -up-|> «superClass.name» [[#«superClass.qualifiedName»]]
		«ENDFOR»
		«FOR reference : classifier.EReferences.filter[isContainment]»
		«classifier.name» *--> "«reference.multiplicity»" «reference.EType.name» : «reference.name»
		«ENDFOR»
		«ELSEIF classifier instanceof EEnum»
		enum «classifier.name» [[#«classifier.qualifiedName»]] #white {
			«FOR literal : classifier.ELiterals»
			«literal.name»
			«ENDFOR»
		}
		«ENDIF»
		«ENDFOR»		
		@enduml
	'''

	def String getMultiplicity(EReference reference) {
		if (reference.isMany) {
			return "*"
		} else if (reference.isRequired) {
			return "1"
		} else {
			return "0..1"
		}
	}

	def void generateClassDiagram(String path, String content) {
		val pumlReader = new SourceStringReader(content, "UTF-8")
      	val svgFile = new File(path).toPath
      	svgFile.getParent.toFile.mkdirs()
      	val os = Files.newOutputStream(svgFile)
      	pumlReader.outputImage(os, new FileFormatOption(FileFormat.SVG))		
	}
}