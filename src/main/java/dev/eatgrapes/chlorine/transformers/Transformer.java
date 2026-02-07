package dev.eatgrapes.chlorine.transformers;

import dev.eatgrapes.chlorine.utils.KeepUtils;
import org.objectweb.asm.tree.ClassNode;
import java.util.Map;
import java.util.Set;

public abstract class Transformer {

    public abstract void transform(Map<String, ClassNode> classes, Map<String, String> manifest, Set<String> keeps);

    public abstract String getName();

    protected boolean shouldKeep(String internalName, Set<String> keeps) {
        return KeepUtils.shouldKeep(internalName, keeps);
    }

    protected boolean shouldKeepMember(String ownerInternal, String memberName, Set<String> keeps) {
        return KeepUtils.shouldKeepMember(ownerInternal, memberName, keeps);
    }

    protected boolean isExternal(String internalName) {
        return KeepUtils.isExternalClass(internalName) || KeepUtils.isArrayType(internalName);
    }
}
