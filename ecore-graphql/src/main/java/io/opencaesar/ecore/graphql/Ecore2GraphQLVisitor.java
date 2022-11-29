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

import graphql.schema.*;
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

    private final Set<EClassifier> allSubclassReferences = new HashSet<>();
    private final Map<EClass, Set<EClass>> allSubclassesMap = new HashMap<>();

    private final TypeResolver typeResolver = env -> {
        if (env.getObject() instanceof EObject) {
            EClass eClass = ((EObject) env.getObject()).eClass();
            return objectTypes.get(eClass);
        }
        return null;
    };

    private enum Mode {
        METACLASSES_ONLY,
        CONTENTS
    };

    private Mode mode;

    public void contentMode() {
        this.mode = Mode.CONTENTS;
    }
    public Ecore2GraphQLVisitor() {
        this.mode = Mode.METACLASSES_ONLY;
    }

    @Override
    public EObject caseEPackage(@NotNull EPackage p) {
        LOGGER.debug("EPackage: " + p.getName());
        packages.add(p);
        return p;
    }

    @Override
    public EObject caseEDataType(@NotNull EDataType dt) {
        final EList<ETypeParameter> typeParameters = dt.getETypeParameters();
        if (!typeParameters.isEmpty()) {
            LOGGER.warn("EDataType: " + dt.getName() + " -- unsupported case with type parameters!");
            return dt;
        }

        final String ic = dt.getInstanceClassName();
        if (null == ic) {
            LOGGER.warn("EDataType: " + dt.getName() + " -- unsupported case without EDataType.instanceClassName!");
            return dt;
        }

        final GraphQLScalarType.Builder b = GraphQLScalarType.newScalar().name(dt.getName());

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
        final GraphQLEnumType.Builder b = GraphQLEnumType.newEnum().name(c.getName());
        enumBuilders.put(c, b);
        return c;
    }

    @Override
    public EObject caseEEnumLiteral(@NotNull EEnumLiteral l) {
        final GraphQLEnumType.Builder b = enumBuilders.get(l.getEEnum());
        if (null != b) {
            b.value(l.getName().toUpperCase(), l.getLiteral());
        }
        return l;
    }

    @SuppressWarnings("deprecation")
    @Override
    public EObject caseEClass(@NotNull EClass c) {
        final EList<ETypeParameter> typeParameters = c.getETypeParameters();
        if (!typeParameters.isEmpty()) {
            LOGGER.warn("EClass: " + c.getName() + " -- unsupported case with type parameters!");
            return null;
        }

        if (c.isAbstract()) {
            final GraphQLInterfaceType.Builder b = GraphQLInterfaceType.newInterface().name(c.getName());
            //noinspection deprecation
            b.typeResolver(typeResolver);
            interfaceBuilders.put(c, b);
            LOGGER.debug("EClass: " + c.getName() + " -> interface type");
        } else {
            final GraphQLObjectType.Builder b = GraphQLObjectType.newObject().name(c.getName());
            c.getEAllSuperTypes().stream()
                    .filter(EClass::isAbstract)
                    .forEach(i ->
                            b.withInterface(GraphQLTypeReference.typeRef(i.getName())));
            objectBuilders.put(c, b);
            LOGGER.debug("EClass: " + c.getName() + " -> object type");
        }
        fields.put(c, new ArrayList<>());
        allMetaclasses.add(c);

        final EList<EClass> cSups = c.getEAllSuperTypes();
        cSups.forEach(sup -> addSubclass(sup, c));

        return c;
    }

    private String paginatedCollectionOf(@NotNull EClassifier c) {
        return c.getName()+"PaginatedCollection";
    }

    private String inputNameOfAllSubtypesOf(@NotNull EClassifier c) {
        return "AllSubtypesOf" + c.getName();
    }

    private void addSubclass(@NotNull EClass sup, @NotNull EClass sub) {
        final Set<EClass> subs = allSubclassesMap.getOrDefault(sup, new HashSet<>());
        subs.add(sub);
        allSubclassesMap.put(sup, subs);
    }

    private boolean acceptEAttribute(@NotNull EAttribute a) {
        final EDataType dt = a.getEAttributeType();
        return packages.contains(dt.getEPackage());
    }

    private void addField(EClass c, ETypedElement e, GraphQLFieldDefinition f) {
        final List<GraphQLFieldDefinition> fs = fields.get(c);
        Assert.assertTrue(
                null != fs,
                () -> "EAttribute: " + c.getName() + "::" + e.getName() + " -- missing fields for " + c.getName());
        fs.add(f);

        final GraphQLInterfaceType.Builder ib = interfaceBuilders.get(c);
        if (null != ib)
            ib.field(f);

        final GraphQLObjectType.Builder ob = objectBuilders.get(c);
        if (null != ob)
            ob.field(f);
    }
    @Override
    public EObject caseEAttribute(@NotNull EAttribute a) {
        if (mode == Mode.METACLASSES_ONLY)
            return a;

        final GraphQLFieldDefinition.Builder fb = GraphQLFieldDefinition.newFieldDefinition();
        fb.name(a.getName());

        final EClass c = a.getEContainingClass();
        fb.description("EAttribute " + c.getName() + "::" + a.getName());

        final EDataType dt = a.getEAttributeType();
        if (!acceptEAttribute(a)) {
            // Skip any attribute whose type is outside of the metamodel.
            return a;
        }
        final GraphQLOutputType qt = referenceClassifierOutputType(dt);
        updateMultiplicity(fb, a, qt);

        final GraphQLFieldDefinition f = fb.build();

        addField(c, a, f);

        return a;
    }

    private void addCollectionFilteringAndSorting(
            GraphQLFieldDefinition.Builder fb,
            ETypedElement e,
            EClassifier t) {

        // Add filter arguments according to the signature for a collection reference:
        // collectionAPI (type: <Enum for all subtypes of Type>, filter: String, sort: String, skip: Int, take: Int) [Type]
        if (e.isMany()) {
            allSubclassReferences.add(t);

            final List<GraphQLArgument> args = new ArrayList<>();
            if (!allSubclassesMap.getOrDefault(t, new HashSet<>()).isEmpty()) {
                final GraphQLArgument.Builder a1 = GraphQLArgument.newArgument();
                a1.name("type");
                a1.type(GraphQLTypeReference.typeRef(inputNameOfAllSubtypesOf(t)));
                a1.description("Input enum for filtering the results to one of the subclasses of "+t.getName());
                args.add(a1.build());
            }

            final GraphQLArgument.Builder a2 = GraphQLArgument.newArgument();
            a2.name("filter");
            a2.type(Scalars.GraphQLString);
            a2.description("AQL boolean expression in the context of `type` that will be the body of a `select(...)` call on the returned collection.");
            args.add(a2.build());

            final GraphQLArgument.Builder a3 = GraphQLArgument.newArgument();
            a3.name("sort");
            a3.type(Scalars.GraphQLString);
            a3.description("AQL expression in the context of type that will be the body of a sortedBy(...) call on the filtered collection.");
            args.add(a3.build());

            final GraphQLArgument.Builder a4 = GraphQLArgument.newArgument();
            a4.name("reverse");
            a4.type(Scalars.GraphQLBoolean);
            a4.description("if true, reverse the results of the filtered and sorted collection.");
            args.add(a4.build());

            final GraphQLArgument.Builder a5 = GraphQLArgument.newArgument();
            a5.name("skip");
            a5.type(Scalars.GraphQLInt);
            a5.description("number of elements to skip from the sorted sequence of elements.");
            args.add(a5.build());

            final GraphQLArgument.Builder a6 = GraphQLArgument.newArgument();
            a6.name("take");
            a6.type(Scalars.GraphQLInt);
            a6.description("max number of elements to return following `skip` number of elements in the sorted sequence of elements.");
            args.add(a6.build());

            fb.arguments(args);

            fb.type(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef(paginatedCollectionOf(t))));
        }
    }
    @Override
    public EObject caseEReference(@NotNull EReference r) {
        if (mode == Mode.METACLASSES_ONLY)
            return r;

        final GraphQLFieldDefinition.Builder fb = GraphQLFieldDefinition.newFieldDefinition();
        fb.name(r.getName());

        final EClass c = r.getEContainingClass();
        fb.description("EReference " + c.getName() + "::" + r.getName());

        final EClass rt = r.getEReferenceType();
        if (r.isContainment()) {
            containedMetaclasses.add(rt);
            containedMetaclasses.addAll(rt.getEAllSuperTypes().stream().filter(ec -> !ec.isAbstract()).collect(Collectors.toList()));
        }

        final GraphQLOutputType qt = referenceClassifierOutputType(rt);
//        EAnnotation a = r.getEAnnotation("http://io.opencaesar.oml/graphql");
//        if (null != a && a.getDetails().containsKey("type")) {
//            qt = GraphQLTypeReference.typeRef(a.getDetails().get("type"));
//        }
        updateMultiplicity(fb, r, qt);

        addCollectionFilteringAndSorting(fb, r, rt);

        final GraphQLFieldDefinition f = fb.build();

        addField(c, r, f);

        return r;
    }

    @Override
    public EObject caseEOperation(@NotNull EOperation o) {
        if (mode == Mode.METACLASSES_ONLY)
            return o;
        final EClass c = o.getEContainingClass();

        String fieldName = o.getName();
        final EAnnotation a = o.getEAnnotation("http://io.opencaesar.oml/graphql");
        if (null != a && a.getDetails().containsKey("replaceAs")) {
            fieldName = a.getDetails().get("replaceAs");
        }

        final EList<ETypeParameter> typeParameters = o.getETypeParameters();
        if (!typeParameters.isEmpty()) {
            LOGGER.warn("EOperation: " + c.getName() + "::" + o.getName() + " -- unsupported case with type parameters!");
            return o;
        }

        final EClassifier t = o.getEType();
        if (!packages.contains(t.getEPackage())) {
            // Skip any operation whose return type is outside of the metamodel.
            return o;
        }

        final EList<ETypeParameter> tps = t.getETypeParameters();
        if (!tps.isEmpty()) {
            LOGGER.warn("EOperation: " + c.getName() + "::" + o.getName() + " -- unsupported case with return type parameters!");
            return o;
        }

        final GraphQLFieldDefinition.Builder fb = GraphQLFieldDefinition.newFieldDefinition();
        fb.name(fieldName);
        fb.description("EOperation " + c.getName() + "::" + o.getName());

        final GraphQLOutputType qt = referenceClassifierOutputType(t);
//        if (null != a && a.getDetails().containsKey("type")) {
//            qt = GraphQLTypeReference.typeRef(a.getDetails().get("type"));
//        }
        updateMultiplicity(fb, o, qt);

        for (EParameter p : o.getEParameters()) {
            final GraphQLArgument.Builder pb = GraphQLArgument.newArgument();
            pb.name(p.getName());
            final EClassifier pt = p.getEType();
            if (!packages.contains(pt.getEPackage())) {
                // Skip any operation whose return type is outside of the metamodel.
                return o;
            }

            final EList<ETypeParameter> ptps = pt.getETypeParameters();
            if (!ptps.isEmpty()) {
                LOGGER.warn("EOperation: " + c.getName() + "::" + o.getName() + " -- unsupported case with type parameters for parameter: " + p.getName());
                return o;
            }
            final GraphQLInputType qpt = referenceClassifierInputType(p.getEType());
            if (p.isMany())
                pb.type(GraphQLList.list(qpt));
            else if (p.isUnique() || 1 == p.getUpperBound())
                pb.type(GraphQLNonNull.nonNull(qpt));
            else
                pb.type(qpt);

            fb.argument(pb);
        }

        addCollectionFilteringAndSorting(fb, o, t);

        final GraphQLFieldDefinition f = fb.build();

        addField(c, o, f);

        return o;
    }

    public @NotNull GraphQLInputType referenceClassifierInputType(@NotNull EClassifier c) {
        GraphQLInputType qt;
        final String n = c.getName();
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
        final String n = c.getName();
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

        final GraphQLObjectType.Builder b = GraphQLObjectType.newObject();
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

        final GraphQLObjectType.Builder bpage = GraphQLObjectType.newObject();
        bpage.name("PageInfo"); // TODO: Need to check that this does not conflict with metamodel name

        final GraphQLFieldDefinition.Builder f1 = GraphQLFieldDefinition.newFieldDefinition();
        f1.name("nextSkip");
        f1.type(Scalars.GraphQLInt);
        f1.description("The value for the skip argument of a subsequent call if hasNext is true.");
        bpage.field(f1);

        final GraphQLFieldDefinition.Builder f2 = GraphQLFieldDefinition.newFieldDefinition();
        f2.name("hasNext");
        f2.type(GraphQLNonNull.nonNull(Scalars.GraphQLBoolean));
        f2.description("If true, nextSkip provides the value for the skip argument of a subsequent call.");
        bpage.field(f2);

        final GraphQLFieldDefinition.Builder f3 = GraphQLFieldDefinition.newFieldDefinition();
        f3.name("totalCount");
        f3.type(GraphQLNonNull.nonNull(Scalars.GraphQLInt));
        f3.description("The total count of elements of the filtered collection; if hasNext=true, the remaining number of elements will be totalCount - nextSkip.");
        bpage.field(f3);

        final GraphQLObjectType page = bpage.build();
        builder.additionalType(page);

        for (EClassifier c : allSubclassReferences) {
            final List<String> subclasses = new ArrayList<>();
            allSubclassesMap.getOrDefault(c, new HashSet<>()).forEach(sub -> subclasses.add(sub.getName()));
            if (!subclasses.isEmpty()) {
                subclasses.add(c.getName());
                subclasses.sort(String.CASE_INSENSITIVE_ORDER);

                final GraphQLEnumType.Builder cSubtypesEnum = GraphQLEnumType.newEnum();
                cSubtypesEnum.name(inputNameOfAllSubtypesOf(c));
                subclasses.forEach(cSubtypesEnum::value);
                builder.additionalType(cSubtypesEnum.build());
            }

            final GraphQLObjectType.Builder cc = GraphQLObjectType.newObject();
            cc.name(paginatedCollectionOf(c));
            cc.description("Paginated Collection of "+c.getName()+" elements.");

            final GraphQLFieldDefinition.Builder c1 = GraphQLFieldDefinition.newFieldDefinition();
            c1.name("collection");
            c1.type(GraphQLNonNull.nonNull(GraphQLList.list(GraphQLTypeReference.typeRef(c.getName()))));
            c1.description("A collection of "+c.getName()+" elements.");
            cc.field(c1);

            final GraphQLFieldDefinition.Builder c2 = GraphQLFieldDefinition.newFieldDefinition();
            c2.name("pageInfo");
            c2.type(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef("PageInfo")));
            c2.description("The pagination data for the collection of "+c.getName()+" elements.");
            cc.field(c2);

            builder.additionalType(cc.build());
        }
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
