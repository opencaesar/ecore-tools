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

import graphql.Assert;
import graphql.Scalars;
import graphql.language.BooleanValue;
import graphql.language.IntValue;
import graphql.scalar.GraphqlIntCoercing;
import graphql.scalar.GraphqlStringCoercing;
import graphql.schema.*;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.util.EcoreSwitch;
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Since ECore requires all classifiers within an EPackage to have unique names,
 * this enables referencing the eventual mapping of any EClassifier as a GraphQLTypeReference by name.
 */
class Ecore2GraphQLVisitor extends EcoreSwitch<EObject> {

    public static String ECORE_URI = "https://www.eclipse.org/emf/2002/Ecore";
    public static String OML_ECORE_URI = "https://opencaesar.io/oml/Ecore";

    private final Logger LOGGER = LogManager.getLogger(Ecore2GraphQLVisitor.class);
    private final Map<EClassifier, GraphQLScalarType.Builder> scalarBuilders = new HashMap<>();
    private final Map<EClassifier, GraphQLEnumType.Builder> enumBuilders = new HashMap<>();
    private final Map<EClass, GraphQLInterfaceType.Builder> interfaceBuilders = new HashMap<>();
    private final Map<EClass, GraphQLObjectType.Builder> objectBuilders = new HashMap<>();
    private final Map<EClass, List<GraphQLFieldDefinition>> fields = new HashMap<>();
    private final Map<EClass, List<GraphQLFieldDefinition>> identifiers = new HashMap<>();
    private final Map<EClass, List<EReference>> containments = new HashMap<>();
    private final Set<MetaclassIdentifier2Containment> mutations = new HashSet<>();
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
    private Mode mode;

    ;

    public Ecore2GraphQLVisitor() {
        this.mode = Mode.METACLASSES_ONLY;
    }

    public void contentMode() {
        this.mode = Mode.CONTENTS;
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

        if (Mode.METACLASSES_ONLY == mode) {
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
            identifiers.put(c, new ArrayList<>());
            containments.put(c, new ArrayList<>());
            allMetaclasses.add(c);

            final EList<EClass> cSups = c.getEAllSuperTypes();
            cSups.forEach(sup -> addSubclass(sup, c));
        }
        return c;
    }

    private String paginatedCollectionOf(@NotNull EClassifier c) {
        return c.getName() + "PaginatedCollection";
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

    private void addIdentifier(EClass c, GraphQLFieldDefinition f) {
        final List<GraphQLFieldDefinition> fs = identifiers.get(c);
        Assert.assertTrue(
                null != fs,
                () -> "missing identifiers for " + c.getName());
        fs.add(f);
    }

    private List<GraphQLFieldDefinition> collectIdentifiersOf(EClass c) {
        final List<GraphQLFieldDefinition> ids = new ArrayList<>(identifiers.get(c));
        c.getEAllSuperTypes().stream().forEach(sup -> {
            if (identifiers.containsKey(sup)) {
                final List<GraphQLFieldDefinition> supIds = identifiers.get(sup);
                ids.addAll(supIds);
            }
        });
        return ids;
    }

    private void addContainment(EClass c, EReference r) {
        final List<EReference> cs = containments.get(c);
        Assert.assertTrue(
                null != cs,
                () -> "missing containements for " + c.getName());
        cs.add(r);
    }

    @Override
    public EObject caseEAttribute(@NotNull EAttribute a) {
        if (Mode.METACLASSES_ONLY == mode)
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

        final EAnnotation n = a.getEAnnotation(OML_ECORE_URI);
        if (null != n && n.getDetails().containsKey("identifier")) {
            if ("true".equalsIgnoreCase(n.getDetails().get("identifier"))) {
                addIdentifier(c, f);
            }
        }

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
                a1.description("Input enum for filtering the results to one of the subclasses of " + t.getName());
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
            a4.defaultValueLiteral(new BooleanValue(false));
            args.add(a4.build());

            final GraphQLArgument.Builder a5 = GraphQLArgument.newArgument();
            a5.name("skip");
            a5.type(Scalars.GraphQLInt);
            a5.description("number of elements to skip from the sorted sequence of elements.");
            a5.defaultValueLiteral(new IntValue(new BigInteger("0")));
            args.add(a5.build());

            final GraphQLArgument.Builder a6 = GraphQLArgument.newArgument();
            a6.name("take");
            a6.type(Scalars.GraphQLInt);
            a6.description("max number of elements to return following `skip` number of elements in the sorted sequence of elements; defaults to -1, which means taking all available elements.");
            a6.defaultValueLiteral(new IntValue(new BigInteger("-1")));
            args.add(a6.build());

            fb.arguments(args);

            fb.type(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef(paginatedCollectionOf(t))));
        }
    }

    @Override
    public EObject caseEReference(@NotNull EReference r) {

        // Skip this reference if there is an inherited interface getter operation of the same name as this reference.
        final EClass c = r.getEContainingClass();
        final String rName = r.getName();

        if (r.isContainment()) {
            LOGGER.warn("EReference containment: " + c.getName() + "::" + r.getName());
        }

        final boolean found = c.getEAllOperations().stream().anyMatch(op -> {
            final EAnnotation a = op.getEAnnotation(ECORE_URI);
            if (null != a && a.getDetails().containsKey("getterOf")) {
                return rName.equals(a.getDetails().get("getterOf"));
            } else
                return false;
        });
        if (found) {
            LOGGER.warn("skip EReference: " + c.getName() + "::" + r.getName());
            return r;
        }

        if (Mode.METACLASSES_ONLY == mode) {

            final GraphQLFieldDefinition.Builder fb = GraphQLFieldDefinition.newFieldDefinition();
            fb.name(r.getName());
            fb.description("EReference " + c.getName() + "::" + r.getName());

            final EClass rt = r.getEReferenceType();
            if (r.isContainment()) {
                containedMetaclasses.add(rt);
                containedMetaclasses.addAll(rt.getEAllSuperTypes().stream().filter(ec -> !ec.isAbstract()).collect(Collectors.toList()));
                addContainment(c, r);
            }

            final GraphQLOutputType qt = referenceClassifierOutputType(rt);
            updateMultiplicity(fb, r, qt);

            addCollectionFilteringAndSorting(fb, r, rt);

            final GraphQLFieldDefinition f = fb.build();

            addField(c, r, f);
        }

        return r;
    }

    @Override
    public EObject caseEOperation(@NotNull EOperation o) {
        if (mode == Mode.METACLASSES_ONLY)
            return o;
        final EClass c = o.getEContainingClass();

        String fieldName = o.getName();
        final EAnnotation a = o.getEAnnotation(ECORE_URI);
        if (null != a && a.getDetails().containsKey("getterOf")) {
            fieldName = a.getDetails().get("getterOf");
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
                final GraphQLFieldDefinition.Builder all = GraphQLFieldDefinition.newFieldDefinition();
                all.name("all" + pluralize(c.getName()));
                // TODO: Change this to a paginated collection of c
                all.type(GraphQLList.list(it));
                b.field(all);
            }
        });

        objectTypes.forEach((c, ot) -> {
            if (rootMetaclasses.contains(c)) {
                final GraphQLFieldDefinition.Builder all = GraphQLFieldDefinition.newFieldDefinition();
                all.name("all" + pluralize(c.getName()));
                // TODO: Change this to a paginated collection of c
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
            cc.description("Paginated Collection of " + c.getName() + " elements.");

            final GraphQLFieldDefinition.Builder c1 = GraphQLFieldDefinition.newFieldDefinition();
            c1.name("collection");
            c1.type(GraphQLNonNull.nonNull(GraphQLList.list(GraphQLTypeReference.typeRef(c.getName()))));
            c1.description("A collection of " + c.getName() + " elements.");
            cc.field(c1);

            final GraphQLFieldDefinition.Builder c2 = GraphQLFieldDefinition.newFieldDefinition();
            c2.name("pageInfo");
            c2.type(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef("PageInfo")));
            c2.description("The pagination data for the collection of " + c.getName() + " elements.");
            cc.field(c2);

            builder.additionalType(cc.build());
        }
        builder.query(b);

        containments.forEach((mc, cs) -> {
            if (!cs.isEmpty()) {
                collectIdentifiersOf(mc).forEach(id -> {
                    mutations.add(new MetaclassIdentifier2Containment(mc, id, cs));
                });
            }
        });

        final GraphQLObjectType.Builder m = GraphQLObjectType.newObject();
        m.name("Mutation");

        final Map<EClass, GraphQLEnumType> concreteContainedSubtypes = new HashMap<>();
        final Map<EClass, List<EClass>> concreteContainedMetaclasses = new HashMap<>();
        mutations.forEach(mi2c -> {
            mi2c.getContainment().stream().map(EReference::getEReferenceType).forEach(ct -> {
                final List<EClass> concreteContained = new ArrayList<>();
                final Set<EClass> ctsub = allSubclassesMap.getOrDefault(ct, new HashSet<>());
                ctsub.forEach(csub -> {
                    if (!csub.isAbstract()) {
                        concreteContained.add(csub);
                    }
                });
                concreteContained.sort(Comparator.comparing(ENamedElement::getName));
                concreteContainedMetaclasses.put(ct, concreteContained);
            });
        });
        concreteContainedMetaclasses.forEach((ct, concreteContained) -> {
            final GraphQLEnumType.Builder cSubtypesEnum = GraphQLEnumType.newEnum();
            cSubtypesEnum.name("AllConcreteSubtypesOf" + ct.getName());
            concreteContained.stream().map(EClass::getName).forEach(cSubtypesEnum::value);
            final GraphQLEnumType subtypes = cSubtypesEnum.build();
            concreteContainedSubtypes.put(ct, subtypes);
            builder.additionalType(subtypes);
        });
        Arrays.stream(mutations.toArray(new MetaclassIdentifier2Containment[]{})).sorted(Comparator.comparing(MetaclassIdentifier2Containment::getKey)).forEach(mi2c -> {
            LOGGER.warn(
                    "mutations for: " + mi2c.getMetaclass().getName() +
                            "; id:" + mi2c.getIdentifier().getName());
            mi2c.getContainment().forEach(c -> {
                final EClass ct = c.getEReferenceType();
                final GraphQLEnumType subtypes = concreteContainedSubtypes.get(ct);

                final GraphQLFieldDefinition.Builder constructor = GraphQLFieldDefinition.newFieldDefinition();
                String key = mi2c.getIdentifier().getName().substring(0, 1).toUpperCase() + mi2c.getIdentifier().getName().substring(1);
                constructor.name(c.getName() + "Of" + mi2c.getMetaclass().getName()+"By"+key);
                // The result should be the ID of the new instance.
                constructor.type(Scalars.GraphQLString);
                constructor.description("Returns the ID of creating one of " + subtypes.getName() + " in the collection " + mi2c.getMetaclass().getName() + "." + c.getName() + " where the container is identified via " + mi2c.getIdentifier().getName());
                final List<GraphQLArgument> args = new ArrayList<>();
                final GraphQLArgument.Builder a1 = GraphQLArgument.newArgument();
                a1.name(mi2c.getIdentifier().getName());
                a1.type(Scalars.GraphQLString);
                a1.description("Identifies the " + mi2c.getMetaclass().getName() + " mutation context via its " + mi2c.getIdentifier().getName() + " property.");
                args.add(a1.build());

                final GraphQLArgument.Builder a2 = GraphQLArgument.newArgument();
                a2.name(c.getName());
                a2.type(GraphQLTypeReference.typeRef(subtypes.getName()));
                a2.description("Specifies one of " + subtypes.getName() + " to create.");
                args.add(a2.build());

                constructor.arguments(args);

                m.field(constructor);
            });
        });
        builder.mutation(m);
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

    private enum Mode {
        METACLASSES_ONLY,
        CONTENTS
    }
}
