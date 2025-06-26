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

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaPrinter;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;

/**
 * Generates a Graph QL file from Ecore
 */
public class Ecore2GraphQL {

    final private Resource r;

    /**
     * Constructor
     * 
     * @param r the given Ecore Resource
     */
    public Ecore2GraphQL(Resource r) {
        this.r = r;
    }

    final private Ecore2GraphQLVisitor v = new Ecore2GraphQLVisitor();

    final private GraphQLSchema.Builder builder = GraphQLSchema.newSchema();
    
    /**
     * Analyzes the contents of the resource to build the GraphQL interface
     */
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

    /**
     * Converts the Ecore resource to GraphQL
     * @return the GraphQL schema as a string
     */
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
