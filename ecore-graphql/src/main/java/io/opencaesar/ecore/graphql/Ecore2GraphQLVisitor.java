package io.opencaesar.ecore.graphql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EOperation;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EParameter;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.ETypeParameter;
import org.eclipse.emf.ecore.ETypedElement;
import org.eclipse.emf.ecore.util.EcoreSwitch;
import org.jetbrains.annotations.NotNull;

import graphql.Assert;
import graphql.Scalars;
import graphql.scalar.GraphqlIntCoercing;
import graphql.scalar.GraphqlStringCoercing;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeReference;
import graphql.schema.TypeResolver;

/**
 * Since ECore requires all classifiers within an EPackage to have unique names,
 * this enables referencing the eventual mapping of any EClassifier as a GraphQLTypeReference by name.
 */
public class Ecore2GraphQLVisitor extends EcoreSwitch<EObject> {

    private final Logger LOGGER = LogManager.getLogger(Ecore2GraphQLVisitor.class);
    private final Map<EClassifier, GraphQLScalarType.Builder> scalarBuilders = new HashMap<>();
    private final Map<EClassifier, GraphQLEnumType.Builder> enumBuilders = new HashMap<>();
    private final Map<EClass, GraphQLInterfaceType.Builder> interfaceBuilders = new HashMap<>();
    private final Map<EClass, GraphQLObjectType.Builder> objectBuilders = new HashMap<>();
    private final Map<EClass, List<GraphQLFieldDefinition>> fields = new HashMap<>();

    private final Map<EClass, GraphQLInterfaceType> interfaceTypes = new HashMap<>();
    private final Map<EClass, GraphQLObjectType> objectTypes = new HashMap<>();

    private final Set<EPackage> packages = new HashSet<>();

    private final Set<EClass> allMetaclasses = new HashSet<>();
    private final Set<EClass> containedMetaclasses = new HashSet<>();

    private final TypeResolver typeResolver = env -> {
        if (env.getObject() instanceof EObject) {
            EClass eClass = ((EObject) env.getObject()).eClass();
            return objectTypes.get(eClass);
        }
        return null;
    };

    public Ecore2GraphQLVisitor() {
    }

    @Override
    public EObject caseEPackage(@NotNull EPackage p) {
        LOGGER.debug("EPackage: " + p.getName());
        packages.add(p);
        return p;
    }

    @Override
    public EObject caseEDataType(@NotNull EDataType dt) {
        EList<ETypeParameter> typeParameters = dt.getETypeParameters();
        if (!typeParameters.isEmpty()) {
            LOGGER.warn("EDataType: " + dt.getName() + " -- unsupported case with type parameters!");
            return dt;
        }

        String ic = dt.getInstanceClassName();
        if (null == ic) {
            LOGGER.warn("EDataType: " + dt.getName() + " -- unsupported case without EDataType.instanceClassName!");
            return dt;
        }

        GraphQLScalarType.Builder b = GraphQLScalarType.newScalar().name(dt.getName());

        // TODO: map Ecore's ExtendedMetadata annotations
        // org.eclipse.emf.ecore.util.EObjectValidator.DynamicEDataTypeValidator.DynamicEDataTypeValidator
        // These annotations could be mapped as b.withAppliedDirective(GraphQLAppliedDirective)
        // See graphql.schema.GraphQLAppliedDirective

        if (ic.equals("long") || ic.equals("java.lang.Long") || ic.equals("java.math.BigDecimal"))
            b.coercing(new GraphqlIntCoercing());
        else if (ic.equals("java.lang.String"))
            b.coercing(new GraphqlStringCoercing());
        else {
            LOGGER.warn("EDataType: " + dt.getName() + " -- unsupported EDataType.instanceClassName=" + ic);
            return dt;
        }

        scalarBuilders.put(dt, b);

        return dt;
    }

    @Override
    public EObject caseEEnum(@NotNull EEnum c) {
        GraphQLEnumType.Builder b = GraphQLEnumType.newEnum().name(c.getName());
        enumBuilders.put(c, b);
        return c;
    }

    @Override
    public EObject caseEEnumLiteral(@NotNull EEnumLiteral l) {
        GraphQLEnumType.Builder b = enumBuilders.get(l.getEEnum());
        if (null != b) {
            b.value(l.getName().toUpperCase(), l.getLiteral());
        }
        return l;
    }

    @SuppressWarnings("deprecation")
	@Override
    public EObject caseEClass(@NotNull EClass c) {
        EList<ETypeParameter> typeParameters = c.getETypeParameters();
        if (!typeParameters.isEmpty()) {
            LOGGER.warn("EClass: " + c.getName() + " -- unsupported case with type parameters!");
            return c;
        }

        if (c.isAbstract()) {
            GraphQLInterfaceType.Builder b = GraphQLInterfaceType.newInterface().name(c.getName());
            //noinspection deprecation
            b.typeResolver(typeResolver);
            interfaceBuilders.put(c, b);
            LOGGER.debug("EClass: " + c.getName() + " -> interface type");
        } else {
            GraphQLObjectType.Builder b = GraphQLObjectType.newObject().name(c.getName());
            c.getEAllSuperTypes().stream()
                    .filter(EClass::isAbstract)
                    .forEach(i -> b.withInterface(GraphQLTypeReference.typeRef(i.getName())));
            objectBuilders.put(c, b);
            LOGGER.debug("EClass: " + c.getName() + " -> object type");
        }
        fields.put(c, new ArrayList<>());
        allMetaclasses.add(c);
        return c;
    }

    @Override
    public EObject caseEAttribute(@NotNull EAttribute a) {
        GraphQLFieldDefinition.Builder fb = GraphQLFieldDefinition.newFieldDefinition();
        fb.name(a.getName());

        EClass c = a.getEContainingClass();
        fb.description("EAttribute " + c.getName() + "::" + a.getName());

        EDataType dt = a.getEAttributeType();
        GraphQLOutputType qt = referenceClassifierOutputType(dt);
        updateMultiplicity(fb, a, qt);

        GraphQLFieldDefinition f = fb.build();

        List<GraphQLFieldDefinition> fs = fields.get(c);
        Assert.assertTrue(
                null != fs,
                () -> "EAttribute: " + c.getName() + "::" + a.getName() + " -- missing fields for " + c.getName());
        fs.add(f);

        GraphQLInterfaceType.Builder ib = interfaceBuilders.get(c);
        if (null != ib)
            ib.field(f);

        GraphQLObjectType.Builder ob = objectBuilders.get(c);
        if (null != ob)
            ob.field(f);

        return a;
    }

    @Override
    public EObject caseEReference(@NotNull EReference r) {
        GraphQLFieldDefinition.Builder fb = GraphQLFieldDefinition.newFieldDefinition();
        fb.name(r.getName());

        EClass c = r.getEContainingClass();
        fb.description("EReference " + c.getName() + "::" + r.getName());

        EClass rt = r.getEReferenceType();
        if (r.isContainment()) {
            containedMetaclasses.add(rt);
            containedMetaclasses.addAll(rt.getEAllSuperTypes().stream().filter(ec -> !ec.isAbstract()).collect(Collectors.toList()));
        }

        GraphQLOutputType qt = referenceClassifierOutputType(rt);
//        EAnnotation a = r.getEAnnotation("http://io.opencaesar.oml/graphql");
//        if (null != a && a.getDetails().containsKey("type")) {
//            qt = GraphQLTypeReference.typeRef(a.getDetails().get("type"));
//        }
        updateMultiplicity(fb, r, qt);

        GraphQLFieldDefinition f = fb.build();

        List<GraphQLFieldDefinition> fs = fields.get(c);
        Assert.assertTrue(
                null != fs,
                () -> "EReference: " + c.getName() + "::" + r.getName() + " -- missing fields for " + c.getName());
        fs.add(f);

        GraphQLInterfaceType.Builder ib = interfaceBuilders.get(c);
        if (null != ib)
            ib.field(f);

        GraphQLObjectType.Builder ob = objectBuilders.get(c);
        if (null != ob)
            ob.field(f);

        return r;
    }

    @Override
    public EObject caseEOperation(@NotNull EOperation o) {
        EClass c = o.getEContainingClass();

        String fieldName = o.getName();
        EAnnotation a = o.getEAnnotation("http://io.opencaesar.oml/graphql");
        if (null != a && a.getDetails().containsKey("replaceAs")) {
            fieldName = a.getDetails().get("replaceAs");
            return o;
        }

        EList<ETypeParameter> typeParameters = o.getETypeParameters();
        if (!typeParameters.isEmpty()) {
            LOGGER.warn("EOperation: " + c.getName() + "::" + o.getName() + " -- unsupported case with type parameters!");
            return o;
        }

        EClassifier t = o.getEType();
        EList<ETypeParameter> tps = t.getETypeParameters();
        if (!tps.isEmpty()) {
            LOGGER.warn("EOperation: " + c.getName() + "::" + o.getName() + " -- unsupported case with return type parameters!");
            return o;
        }

        GraphQLFieldDefinition.Builder fb = GraphQLFieldDefinition.newFieldDefinition();
        fb.name(fieldName);
        fb.description("EOperation " + c.getName() + "::" + o.getName());

        GraphQLOutputType qt = referenceClassifierOutputType(t);
//        if (null != a && a.getDetails().containsKey("type")) {
//            qt = GraphQLTypeReference.typeRef(a.getDetails().get("type"));
//        }
        updateMultiplicity(fb, o, qt);

        for (EParameter p : o.getEParameters()) {
            GraphQLArgument.Builder pb = GraphQLArgument.newArgument();
            pb.name(p.getName());
            EClassifier pt = p.getEType();
            EList<ETypeParameter> ptps = pt.getETypeParameters();
            if (!ptps.isEmpty()) {
                LOGGER.warn("EOperation: " + c.getName() + "::" + o.getName() + " -- unsupported case with type parameters for parameter: " + p.getName());
                return o;
            }
            GraphQLInputType qpt = referenceClassifierInputType(p.getEType());
            if (p.isMany())
                pb.type(GraphQLList.list(qpt));
            else if (p.isUnique() || 1 == p.getUpperBound())
                pb.type(GraphQLNonNull.nonNull(qpt));
            else
                pb.type(qpt);

            fb.argument(pb);
        }

        GraphQLFieldDefinition f = fb.build();

        List<GraphQLFieldDefinition> fs = fields.get(c);
        Assert.assertTrue(
                null != fs,
                () -> "EOperation: " + c.getName() + "::" + o.getName() + " -- missing fields for " + c.getName());
        fs.add(f);

        GraphQLInterfaceType.Builder ib = interfaceBuilders.get(c);
        if (null != ib)
            ib.field(f);

        GraphQLObjectType.Builder ob = objectBuilders.get(c);
        if (null != ob)
            ob.field(f);

        return o;
    }

    public @NotNull GraphQLInputType referenceClassifierInputType(@NotNull EClassifier c) {
        GraphQLInputType qt;
        String n = c.getName();
        switch (n) {
            case "EString":
                qt = Scalars.GraphQLString;
                break;
            case "EBoolean":
                qt = Scalars.GraphQLBoolean;
                break;
            case "UnsignedInteger":
            case "EInt":
                qt = Scalars.GraphQLInt;
                break;
            case "EDouble":
                qt = Scalars.GraphQLFloat;
                break;
            default:
                qt = GraphQLTypeReference.typeRef(c.getName());
                break;
        }
        return qt;
    }

    public @NotNull GraphQLOutputType referenceClassifierOutputType(@NotNull EClassifier c) {
        GraphQLOutputType qt;
        String n = c.getName();
        switch (n) {
            case "EString":
                qt = Scalars.GraphQLString;
                break;
            case "EBoolean":
                qt = Scalars.GraphQLBoolean;
                break;
            case "UnsignedInteger":
            case "EInt":
                qt = Scalars.GraphQLInt;
                break;
            case "EDouble":
                qt = Scalars.GraphQLFloat;
                break;
            default:
                qt = GraphQLTypeReference.typeRef(c.getName());
                break;
        }
        return qt;
    }

    public void addBuilds(@NotNull GraphQLSchema.Builder builder) {
        if (packages.size() > 1) {
            LOGGER.warn("The generated GraphQL schema corresponds to mapping the union of all " + packages.size() + " input metamodel packages.");
        }

        for (GraphQLScalarType.Builder b : scalarBuilders.values()) {
            builder.additionalType(b.build());
        }

        for (GraphQLEnumType.Builder b : enumBuilders.values()) {
            builder.additionalType(b.build());
        }

        // For each abstract metaclass, add to its builder all the fields of each of its superclasses.
        interfaceBuilders.forEach((c, b) -> {
            c.getEAllSuperTypes().forEach(sup ->
                    addSpecificFields(b, fields.getOrDefault(sup, Collections.emptyList())));
            GraphQLInterfaceType it = b.build();
            builder.additionalType(it);
            interfaceTypes.put(c, it);
        });

        // For each concrete metaclass, add to its builder all the fields of each of its superclasses.
        objectBuilders.forEach((c, b) -> {
            c.getEAllSuperTypes().forEach(sup ->
                    addSpecificFields(b, fields.getOrDefault(sup, Collections.emptyList())));
            GraphQLObjectType ot = b.build();
            builder.additionalType(ot);
            objectTypes.put(c, ot);
        });

        GraphQLObjectType.Builder b = GraphQLObjectType.newObject();
        b.name("Query");

        // Candidate root metaclasses:
        // - it is not a contained metaclass
        // - it is not a superclass of a contained metaclass
        // - none of its superclasses is a contained metaclass
        final List<EClass> candidateMetaclasses =
                allMetaclasses.stream().flatMap(ec -> {
                            if (!containedMetaclasses.contains(ec) &&
                                    containedMetaclasses.stream().filter(c -> c.getEAllSuperTypes().contains(ec)).findFirst().isEmpty() &&
                                    ec.getEAllSuperTypes().stream().filter(containedMetaclasses::contains).findFirst().isEmpty())
                                return Stream.of(ec);
                            else
                                return Stream.empty();
                        })
                        .sorted(Comparator.comparing(EClass::getName))
                        .collect(Collectors.toList());

        // root metaclasses are filtered from the candidate metaclasses as follows:
        // - the candidate is concrete
        // - the candidate is abstract and has at least 1 concrete specialization that is also a candidate.
        final List<EClass> rootMetaclasses =
                candidateMetaclasses.stream()
                        .filter(ec -> !ec.isAbstract() || candidateMetaclasses.stream().anyMatch(c -> !c.isAbstract() && c.getEAllSuperTypes().contains(ec)))
                        .collect(Collectors.toList());

        // TODO: find which interfaces/types are *NOT* contained by any reference
        // These should have toplevel "all..." query fields.
        interfaceTypes.forEach((c, it) -> {
            if (rootMetaclasses.contains(c)) {
                GraphQLFieldDefinition.Builder all = GraphQLFieldDefinition.newFieldDefinition();
                all.name("all" + pluralize(c.getName()));
                all.type(GraphQLList.list(it));
                b.field(all);
            }
        });

        objectTypes.forEach((c, ot) -> {
            if (rootMetaclasses.contains(c)) {
                GraphQLFieldDefinition.Builder all = GraphQLFieldDefinition.newFieldDefinition();
                all.name("all" + pluralize(c.getName()));
                all.type(GraphQLList.list(ot));
                b.field(all);
            }
        });

        builder.query(b);
    }

    private void addSpecificFields(GraphQLObjectType.Builder b, List<GraphQLFieldDefinition> fs) {
        for (GraphQLFieldDefinition f : fs) {
            if (!b.hasField(f.getName())) {
                b.field(f);
            }
        }
    }

    private void addSpecificFields(GraphQLInterfaceType.Builder b, List<GraphQLFieldDefinition> fs) {
        for (GraphQLFieldDefinition f : fs) {
            if (!b.hasField(f.getName())) {
                b.field(f);
            }
        }
    }

    private void updateMultiplicity(
            @NotNull GraphQLFieldDefinition.Builder fb,
            @NotNull ETypedElement te,
            @NotNull GraphQLOutputType qt) {
        if (te.isMany())
            fb.type(GraphQLList.list(qt));
        else if (te.isUnique() || 1 == te.getUpperBound())
            fb.type(GraphQLNonNull.nonNull(qt));
        else
            fb.type(qt);
    }

    private @NotNull String pluralize(@NotNull String n) {
        if (n.endsWith("x") || n.endsWith("ss"))
            return n + "es";
        else if (n.endsWith("y"))
            return n.substring(0, n.length() - 1) + "ies";
        else
            return n + "s";
    }
}
