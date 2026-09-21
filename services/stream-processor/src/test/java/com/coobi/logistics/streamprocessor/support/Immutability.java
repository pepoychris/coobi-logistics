package com.coobi.logistics.streamprocessor.support;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

/**
 * Shared reflection checks for the "events are immutable" acceptance criterion.
 *
 * <p>A record is immutable only when it is a record, every field is final and no
 * setter-like method exists; the tests then check the component types themselves.
 */
public final class Immutability {

    private Immutability() {
    }

    public static boolean isRecordWithFinalFields(Class<?> type) {
        return type.isRecord() && Arrays.stream(type.getDeclaredFields()).allMatch(Immutability::isFinal);
    }

    public static List<String> setterLikeMethods(Class<?> type) {
        return Arrays.stream(type.getMethods())
                .filter(method -> method.getName().startsWith("set") && method.getParameterCount() == 1)
                .map(Method::getName)
                .toList();
    }

    /** Whether every component type is a primitive, an enum or one of the allowed types. */
    public static boolean componentTypesAreImmutable(Class<?> type, List<Class<?>> allowedTypes) {
        for (Field field : type.getDeclaredFields()) {
            if (field.getType().isPrimitive() || field.getType().isEnum()) {
                continue;
            }
            if (!allowedTypes.contains(field.getType())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFinal(Field field) {
        return Modifier.isFinal(field.getModifiers());
    }
}
