package io.opencaesar.ecore.graphql;

import graphql.Assert;
import graphql.Scalars;
import graphql.scalar.GraphqlIntCoercing;
import graphql.scalar.GraphqlStringCoercing;
import graphql.schema.*;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.util.EcoreSwitch;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Stream;

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
    private final Map<EClass, GraphQLFieldDefinition> idFields = new HashMap<>();

    private final Set<EPackage> packages = new HashSet<>();

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
            b.value(l.getName(), l.getLiteral());
        }
        return l;
    }

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
        return c;
    }

    @Override
    public EObject caseEAttribute(@NotNull EAttribute a) {
        GraphQLFieldDefinition.Builder fb = GraphQLFieldDefinition.newFieldDefinition();
        fb.name(a.getName());

        EClass c = a.getEContainingClass();
        fb.description("EAttribute "+c.getName() + "::" + a.getName());

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

        EAnnotation ag = a.getEAnnotation("http://io.opencaesar.oml/graphql");
        if (null != ag && ag.getDetails().get("id").equalsIgnoreCase("true")) {
            idFields.put(c, f);
        }

        return a;
    }

    @Override
    public EObject caseEReference(@NotNull EReference r) {
        GraphQLFieldDefinition.Builder fb = GraphQLFieldDefinition.newFieldDefinition();
        fb.name(r.getName());

        EClass c = r.getEContainingClass();
        fb.description("EReference "+c.getName() + "::" + r.getName());

        EClass rt = r.getEReferenceType();
        GraphQLOutputType qt = referenceClassifierOutputType(rt);
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
        fb.name(o.getName());
        fb.description("EOperation "+c.getName() + "::" + o.getName());

        GraphQLOutputType qt = referenceClassifierOutputType(t);
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
            LOGGER.warn("The generated GraphQL schema corresponds to mapping the union of all "+packages.size()+" input metamodel packages.");
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
                    b.fields(fields.getOrDefault(sup, Collections.emptyList())));
            GraphQLInterfaceType it = b.build();
            builder.additionalType(it);
            interfaceTypes.put(c, it);
        });

        // For each concrete metaclass, add to its builder all the fields of each of its superclasses.
        objectBuilders.forEach((c, b) -> {
            c.getEAllSuperTypes().forEach(sup ->
                    b.fields(fields.getOrDefault(sup, Collections.emptyList())));
            GraphQLObjectType ot = b.build();
            builder.additionalType(ot);
            objectTypes.put(c, ot);
        });

        GraphQLObjectType.Builder b = GraphQLObjectType.newObject();
        b.name("Query");

        interfaceTypes.forEach((c, it) -> {
            GraphQLFieldDefinition id = lookupIdFieldIncludingSuperclasses(c);
            if (null != id) {
                GraphQLFieldDefinition.Builder all = GraphQLFieldDefinition.newFieldDefinition();
                all.name("all"+pluralize(c.getName()));
                all.type(GraphQLList.list(it));
                b.field(all);

                GraphQLFieldDefinition.Builder lookup = GraphQLFieldDefinition.newFieldDefinition();
                lookup.name(lowercaseInitial(c.getName()));
                GraphQLArgument.Builder arg = GraphQLArgument.newArgument();
                arg.name(id.getName());
                // TODO: id.getType() is an output type; this API requires an input type
                //arg.type(id.getType());
                arg.type(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef("ID")));
                lookup.argument(arg);
                lookup.type(it);
                b.field(lookup);

            }
        });


        objectTypes.forEach((c, ot) -> {
            GraphQLFieldDefinition id = lookupIdFieldIncludingSuperclasses(c);
            if (null != id) {
                GraphQLFieldDefinition.Builder all = GraphQLFieldDefinition.newFieldDefinition();
                all.name("all"+pluralize(c.getName()));
                all.type(GraphQLList.list(ot));
                b.field(all);

                GraphQLFieldDefinition.Builder lookup = GraphQLFieldDefinition.newFieldDefinition();
                lookup.name(lowercaseInitial(c.getName()));
                GraphQLArgument.Builder arg = GraphQLArgument.newArgument();
                arg.name(id.getName());
                // TODO: id.getType() is an output type; this API requires an input type
                //arg.type(id.getType());
                arg.type(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef("ID")));
                lookup.argument(arg);
                lookup.type(ot);
                b.field(lookup);
            }
        });

        builder.query(b);
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

    private GraphQLFieldDefinition lookupIdFieldIncludingSuperclasses(@NotNull EClass c) {
        GraphQLFieldDefinition id = idFields.get(c);
        if (id == null) {
            Optional<GraphQLFieldDefinition> idSup =
                    c.getEAllSuperTypes().stream()
                            .filter(idFields::containsKey)
                            .flatMap(this::lookupIdField)
                            .findFirst();
            id = idSup.orElse(null);
        }
        return id;
    }

    private @NotNull Stream<GraphQLFieldDefinition> lookupIdField(@NotNull EClass c) {
        GraphQLFieldDefinition id = idFields.get(c);
        return Stream.ofNullable(id);
    }

    private @NotNull String lowercaseInitial(@NotNull String n) {
        return n.substring(0,1).toLowerCase()+n.substring(1);
    }
    private @NotNull String pluralize(@NotNull String n) {
        if (n.endsWith("x") || n.endsWith("ss"))
            return n+"es";
        else if (n.endsWith("y"))
            return n.substring(0, n.length()-1)+"ies";
        else
            return n+"s";
    }
}
