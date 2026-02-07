package dev.eatgrapes.chlorine.utils;

import java.util.Set;

public class KeepUtils {

    public static boolean shouldKeep(String internalName, Set<String> keeps) {
        if (keeps == null || keeps.isEmpty()) return false;
        String dotName = internalName.replace('/', '.');
        for (String rule : keeps) {
            String normalizedRule = rule.replace('/', '.');
            if (dotName.equals(normalizedRule)) return true;
            if (dotName.startsWith(normalizedRule + ".")) return true;
            if (normalizedRule.endsWith(".*")) {
                String prefix = normalizedRule.substring(0, normalizedRule.length() - 2);
                if (dotName.startsWith(prefix + ".")) return true;
            }
            if (normalizedRule.endsWith(".**")) {
                String prefix = normalizedRule.substring(0, normalizedRule.length() - 3);
                if (dotName.startsWith(prefix + ".") || dotName.equals(prefix)) return true;
            }
        }
        return false;
    }

    public static boolean shouldKeepMember(String ownerInternal, String memberName, Set<String> keeps) {
        if (keeps == null || keeps.isEmpty()) return false;
        if (shouldKeep(ownerInternal, keeps)) return true;
        String fullName = ownerInternal.replace('/', '.') + "." + memberName;
        for (String rule : keeps) {
            String normalizedRule = rule.replace('/', '.');
            if (fullName.equals(normalizedRule)) return true;
        }
        return false;
    }

    public static boolean isExternalClass(String internalName) {
        return internalName.startsWith("java/") ||
               internalName.startsWith("javax/") ||
               internalName.startsWith("sun/") ||
               internalName.startsWith("com/sun/") ||
               internalName.startsWith("jdk/");
    }

    public static boolean isArrayType(String internalName) {
        return internalName.startsWith("[");
    }
}
