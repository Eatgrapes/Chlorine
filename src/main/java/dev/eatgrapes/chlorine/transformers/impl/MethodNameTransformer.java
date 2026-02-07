package dev.eatgrapes.chlorine.transformers.impl;

import dev.eatgrapes.chlorine.transformers.Transformer;
import dev.eatgrapes.chlorine.utils.AsmUtils;
import dev.eatgrapes.chlorine.utils.NameGenerator;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.*;

public class MethodNameTransformer extends Transformer {
    @Override
    public String getName() { return "MethodRename"; }

    @Override
    public void transform(Map<String, ClassNode> classes, Map<String, String> manifest, Set<String> keeps) {
        Map<String, String> mapping = new HashMap<>(); 
        Set<String> blocklist = new HashSet<>(Arrays.asList(
            "main", "<init>", "<clinit>", 
            "toString", "hashCode", "equals", "clone", "finalize", "getClass", "notify", "notifyAll", "wait",
            "values", "valueOf"
        ));
        
        Map<String, String> methodGroup = new HashMap<>(); 
        Set<String> immutableGroups = new HashSet<>();

        // 1. Group methods
        for (ClassNode cn : classes.values()) {
            if (AsmUtils.isModuleInfo(cn)) continue;
            for (MethodNode mn : cn.methods) {
                String id = cn.name + "." + mn.name + mn.desc;
                
                boolean isPrivate = (mn.access & Opcodes.ACC_PRIVATE) != 0;
                boolean isReserved = mn.name.equals("<init>") || mn.name.equals("<clinit>") || mn.name.equals("main");
                
                if ((blocklist.contains(mn.name) && (!isPrivate || isReserved)) || shouldKeep(cn.name, keeps)) {
                    immutableGroups.add(id);
                    continue;
                }

                String root = findRoot(cn.name, mn.name, mn.desc, classes);
                methodGroup.put(id, root);
                
                if (root.startsWith("!")) {
                    immutableGroups.add(root);
                }
            }
        }
        
        // 2. Assign names
        Map<String, NameGenerator> descGenerators = new HashMap<>();
        Map<String, String> groupNames = new HashMap<>();

        for (ClassNode cn : classes.values()) {
             for (MethodNode mn : cn.methods) {
                 String id = cn.name + "." + mn.name + mn.desc;
                 if (immutableGroups.contains(id)) continue;

                 String root = methodGroup.get(id);
                 if (root == null) continue;
                 
                 if (root.startsWith("!") || immutableGroups.contains(root)) {
                     continue;
                 }
                 
                 if (!groupNames.containsKey(root)) {
                     NameGenerator gen = descGenerators.computeIfAbsent(mn.desc, k -> new NameGenerator());
                     String newName = gen.next();
                     groupNames.put(root, newName);
                 }
                 
                 mapping.put(id, groupNames.get(root));
             }
        }
        
        if (mapping.isEmpty()) return;

        // 3. Apply
        Remapper remapper = new Remapper() {
            @Override
            public String mapMethodName(String owner, String name, String desc) {
                String key = owner + "." + name + desc;
                if (mapping.containsKey(key)) {
                    return mapping.get(key);
                }
                
                String current = owner;
                Set<String> visited = new HashSet<>();
                Queue<String> queue = new LinkedList<>();
                queue.add(current);
                
                while(!queue.isEmpty()) {
                    String type = queue.poll();
                    if (!visited.add(type)) continue;
                    
                    ClassNode cn = classes.get(type);
                    if (cn == null) continue;
                    
                    String lookupKey = type + "." + name + desc;
                    if (mapping.containsKey(lookupKey)) {
                        return mapping.get(lookupKey);
                    }
                    
                    if (cn.superName != null) queue.add(cn.superName);
                    if (cn.interfaces != null) queue.addAll(cn.interfaces);
                }
                
                return name;
            }
        };
        
        Map<String, ClassNode> newClasses = new HashMap<>();
        for (Map.Entry<String, ClassNode> entry : classes.entrySet()) {
            ClassNode oldNode = entry.getValue();
            ClassNode newNode = new ClassNode();
            oldNode.accept(new ClassRemapper(newNode, remapper));
            
            for (MethodNode mn : newNode.methods) {
                if (mapping.containsValue(mn.name)) {
                    mn.access = (mn.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
                }
            }
            
            newClasses.put(newNode.name, newNode);
        }
        classes.clear();
        classes.putAll(newClasses);
    }
    
    private String findRoot(String owner, String name, String desc, Map<String, ClassNode> classes) {
        ClassNode cn = classes.get(owner);
        if (cn == null) return "!" + owner + "." + name + desc; 
        
        String root = null;
        if (cn.superName != null) {
            if (hasMethod(cn.superName, name, desc, classes)) {
                 root = findRoot(cn.superName, name, desc, classes);
            }
        }
        if (cn.interfaces != null) {
            for (String iface : cn.interfaces) {
                 if (hasMethod(iface, name, desc, classes)) {
                     String ifaceRoot = findRoot(iface, name, desc, classes);
                     if (root == null || ifaceRoot.startsWith("!")) root = ifaceRoot; 
                 }
            }
        }
        
        if (root != null) return root;
        return owner + "." + name + desc;
    }
    
    private boolean hasMethod(String owner, String name, String desc, Map<String, ClassNode> classes) {
        ClassNode cn = classes.get(owner);
        if (cn == null) return false; 
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals(name) && mn.desc.equals(desc)) return true;
        }
        return false;
    }
}