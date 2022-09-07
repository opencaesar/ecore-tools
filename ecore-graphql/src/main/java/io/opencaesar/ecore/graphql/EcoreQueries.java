package io.opencaesar.ecore.graphql;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.*;

import java.util.Comparator;
import java.util.stream.Stream;

public class EcoreQueries {
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
