package dev.eatgrapes.chlorine.transformers.impl;

import dev.eatgrapes.chlorine.transformers.Transformer;
import dev.eatgrapes.chlorine.utils.NameGenerator;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class FieldNameTransformer extends Transformer {
    @Override
    public String getName() { return "FieldRename"; }

    @Override
    public void transform(Map<String, ClassNode> classes, Map<String, String> manifest, Set<String> keeps) {
        Map<String, String> fieldMap = new HashMap<>();
        
        for (ClassNode cn : classes.values()) {
            if (shouldKeep(cn.name, keeps)) continue;
            
            NameGenerator nameGen = new NameGenerator();
            for (FieldNode fn : cn.fields) {
                String newName = nameGen.next();
                String key = cn.name + "." + fn.name + "." + fn.desc; 
                fieldMap.put(cn.name + "." + fn.name, newName);
            }
        }

        if (fieldMap.isEmpty()) return;

        Remapper remapper = new Remapper() {
            @Override
            public String mapFieldName(String owner, String name, String desc) {
                String key = owner + "." + name;
                return fieldMap.getOrDefault(key, name);
            }
        };

        Map<String, ClassNode> newClasses = new HashMap<>();
        for (Map.Entry<String, ClassNode> entry : classes.entrySet()) {
            ClassNode oldNode = entry.getValue();
            ClassNode newNode = new ClassNode();
            oldNode.accept(new ClassRemapper(newNode, remapper));
            
            for (FieldNode fn : newNode.fields) {
                if (fieldMap.containsValue(fn.name)) {
                    fn.access = (fn.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
                }
            }
            
            newClasses.put(newNode.name, newNode);
        }
        classes.clear();
        classes.putAll(newClasses);
    }
}