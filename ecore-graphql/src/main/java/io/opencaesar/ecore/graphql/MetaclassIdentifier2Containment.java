package io.opencaesar.ecore.graphql;

import graphql.schema.GraphQLFieldDefinition;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EReference;

import java.util.List;

public class MetaclassIdentifier2Containment {

    private final EClass metaclass;
    private final GraphQLFieldDefinition identifier;
    private final List<EReference> containment;

    public MetaclassIdentifier2Containment(
            EClass metaclass,
            GraphQLFieldDefinition identifier,
            List<EReference> containment) {
        this.metaclass = metaclass;
        this.identifier = identifier;
        this.containment = containment;
    }

    public EClass getMetaclass() {
        return metaclass;
    }

    public GraphQLFieldDefinition getIdentifier() {
        return identifier;
    }

    public List<EReference> getContainment() {
        return containment;
    }
}
