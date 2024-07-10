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

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.*;

import java.util.Comparator;
import java.util.stream.Stream;

class EcoreQueries {
    public static Stream<EPackage> filterEPackage(EObject eo) {
        if (EPackage.class.isInstance(eo))
            return Stream.of(EPackage.class.cast(eo));
        else
            return Stream.empty();
    }

    public static Stream<EEnum> filterEnum(EClassifier ec) {
        if (ec instanceof EEnum)
            return Stream.of((EEnum) ec);
        else
            return Stream.empty();
    }

    public static Stream<EClass> filterInterface(EClassifier ec) {
        if (ec instanceof EClass) {
            EClass c = (EClass) ec;
            if (c.isAbstract())
                return Stream.of(c);
        }
        return Stream.empty();
    }

    public static Stream<EClass> filterConcreteClass(EClassifier ec) {
        if (ec instanceof EClass) {
            EClass c = (EClass) ec;
            if (!c.isAbstract())
                return Stream.of(c);
        }
        return Stream.empty();
    }

    public static Comparator<EClass> inheritanceOrdering = (o1, o2) -> {
        if (o1 == o2)
            return 0;

        final String n1 = o1.getName();
        final String n2 = o2.getName();
        final EList<EClass> s1 = o1.getEAllSuperTypes();
        final EList<EClass> s2 = o2.getEAllSuperTypes();

        if (s1.isEmpty()) {
            if (s2.isEmpty())
                return n1.compareTo(n2);
            else
                return -1;
        } else if (s2.isEmpty()) {
            return 1;
        } else if (s2.contains(o1)) {
            return -1;
        } else if (s1.contains(o2)) {
            return 1;
        } else
            return n1.compareTo(n2);
    };
}
