package dev.eatgrapes.chlorine.transformers;

import org.objectweb.asm.tree.ClassNode;
import java.util.Map;
import java.util.Set;

public abstract class Transformer {
    
    public abstract void transform(Map<String, ClassNode> classes, Map<String, String> manifest, Set<String> keeps);
    
    public abstract String getName();
    
    protected boolean shouldKeep(String name, Set<String> keeps) {
        if (keeps == null) return false;
        // keeps can have com/example/ (package) or com/example/Class
        String dotName = name.replace('/', '.');
        for (String rule : keeps) {
            if (dotName.equals(rule) || dotName.startsWith(rule + ".")) {
                return true;
            }
        }
        return false;
    }
}
