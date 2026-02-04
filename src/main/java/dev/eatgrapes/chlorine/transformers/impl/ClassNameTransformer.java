package dev.eatgrapes.chlorine.transformers.impl;

import dev.eatgrapes.chlorine.transformers.Transformer;
import dev.eatgrapes.chlorine.utils.NameGenerator;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class ClassNameTransformer extends Transformer {
    @Override
    public String getName() { return "ClassRename"; }

    @Override
    public void transform(Map<String, ClassNode> classes, Map<String, String> manifest, Set<String> keeps) {
        NameGenerator nameGen = new NameGenerator();
        Map<String, String> mapping = new HashMap<>();
        
        for (ClassNode cn : classes.values()) {
            if (shouldKeep(cn.name, keeps)) {
                continue;
            }
            String newName = nameGen.next();
            mapping.put(cn.name, newName);
        }
        
        if (mapping.isEmpty()) return;

        SimpleRemapper remapper = new SimpleRemapper(mapping);
        Map<String, ClassNode> newClasses = new HashMap<>();

        for (Map.Entry<String, ClassNode> entry : classes.entrySet()) {
            ClassNode oldNode = entry.getValue();
            ClassNode newNode = new ClassNode();
            oldNode.accept(new ClassRemapper(newNode, remapper));
            
            newNode.access |= Opcodes.ACC_PUBLIC;
            
            if (newNode.sourceFile != null) {
                newNode.sourceFile = newNode.name.substring(newNode.name.lastIndexOf('/') + 1) + ".java";
            }
            newClasses.put(newNode.name, newNode);
        }
        
        classes.clear();
        classes.putAll(newClasses);
        
        String mainClass = manifest.get("Main-Class");
        if (mainClass != null) {
            String internalMain = mainClass.replace('.', '/');
            if (mapping.containsKey(internalMain)) {
                manifest.put("Main-Class", mapping.get(internalMain).replace('/', '.'));
            }
        }
    }
}