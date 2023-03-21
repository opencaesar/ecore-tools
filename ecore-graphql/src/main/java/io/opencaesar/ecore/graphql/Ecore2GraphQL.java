package io.opencaesar.ecore.graphql;

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaPrinter;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;

public class Ecore2GraphQL {

    final private Resource r;

    public Ecore2GraphQL(Resource r) {
        this.r = r;
    }

    final Ecore2GraphQLVisitor v = new Ecore2GraphQLVisitor();

    final private GraphQLSchema.Builder builder = GraphQLSchema.newSchema();
    public void analyze() {
        for (TreeIterator<EObject> it = r.getAllContents(); it.hasNext(); ) {
            EObject eo = it.next();
            v.doSwitch(eo);
        }
        v.contentMode();
        for (TreeIterator<EObject> it = r.getAllContents(); it.hasNext(); ) {
            EObject eo = it.next();
            v.doSwitch(eo);
        }
        v.addBuilds(builder);
    }

    public String convert() {
        final GraphQLSchema schema = builder.build();
        final SchemaPrinter.Options options =
                SchemaPrinter.Options
                        .defaultOptions()
                        .includeScalarTypes(true)
                        .includeSchemaDefinition(true);
        final SchemaPrinter printer = new SchemaPrinter(options);
        return printer.print(schema);
    }



}
