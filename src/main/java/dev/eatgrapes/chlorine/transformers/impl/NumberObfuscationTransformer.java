package dev.eatgrapes.chlorine.transformers.impl;

import dev.eatgrapes.chlorine.transformers.Transformer;
import dev.eatgrapes.chlorine.utils.NameGenerator;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

public class NumberObfuscationTransformer extends Transformer {
    @Override
    public String getName() { return "NumberObfuscation"; }

    @Override
    public void transform(Map<String, ClassNode> classes, Map<String, String> manifest, Set<String> keeps) {
        for (ClassNode cn : classes.values()) {
            if (shouldKeep(cn.name, keeps) || (cn.access & Opcodes.ACC_INTERFACE) != 0) continue;
            
            List<Integer> constants = new ArrayList<>();
            for (MethodNode mn : cn.methods) {
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof IntInsnNode) {
                    } else if (insn instanceof LdcInsnNode) {
                        LdcInsnNode ldc = (LdcInsnNode) insn;
                        if (ldc.cst instanceof Integer) {
                            constants.add((Integer) ldc.cst);
                        }
                    }
                }
            }
            
            if (constants.isEmpty()) continue;
            
            String fieldName = new NameGenerator().next() + "_ints";
            FieldNode fn = new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, fieldName, "[I", null, null);
            cn.fields.add(fn);
            
            MethodNode clinit = null;
            for (MethodNode mn : cn.methods) {
                if (mn.name.equals("<clinit>")) {
                    clinit = mn;
                    break;
                }
            }
            if (clinit == null) {
                clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
                clinit.instructions.add(new InsnNode(Opcodes.RETURN));
                cn.methods.add(clinit);
            }
            
            InsnList init = new InsnList();
            init.add(new LdcInsnNode(constants.size()));
            init.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
            
            for (int i = 0; i < constants.size(); i++) {
                init.add(new InsnNode(Opcodes.DUP));
                init.add(new LdcInsnNode(i));
                init.add(new LdcInsnNode(constants.get(i)));
                init.add(new InsnNode(Opcodes.IASTORE));
            }
            init.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, fieldName, "[I"));
            
            clinit.instructions.insert(init);
            
            for (MethodNode mn : cn.methods) {
                 ListIterator<AbstractInsnNode> it = mn.instructions.iterator();
                 while(it.hasNext()) {
                     AbstractInsnNode insn = it.next();
                     if (insn instanceof LdcInsnNode) {
                         LdcInsnNode ldc = (LdcInsnNode) insn;
                         if (ldc.cst instanceof Integer) {
                             int val = (Integer) ldc.cst;
                             int idx = constants.indexOf(val); 
                         }
                     }
                 }
            }
        }
        
        for (ClassNode cn : classes.values()) {
            if (shouldKeep(cn.name, keeps) || (cn.access & Opcodes.ACC_INTERFACE) != 0) continue;

            List<Integer> distinctInts = new ArrayList<>();
            
            for (MethodNode mn : cn.methods) {
                 if (mn.name.equals("<clinit>")) continue; 
                 ListIterator<AbstractInsnNode> it = mn.instructions.iterator();
                 while(it.hasNext()) {
                     AbstractInsnNode insn = it.next();
                     if (insn instanceof LdcInsnNode) {
                         LdcInsnNode ldc = (LdcInsnNode) insn;
                         if (ldc.cst instanceof Integer) {
                             int val = (Integer) ldc.cst;
                             if (!distinctInts.contains(val)) distinctInts.add(val);
                         }
                     } else if (insn instanceof IntInsnNode) {
                         IntInsnNode intInsn = (IntInsnNode) insn;
                         if (intInsn.getOpcode() == Opcodes.SIPUSH || intInsn.getOpcode() == Opcodes.BIPUSH) {
                             int val = intInsn.operand;
                             if (!distinctInts.contains(val)) distinctInts.add(val);
                         }
                     }
                 }
            }
            
            if (distinctInts.isEmpty()) continue;
            
            NameGenerator ng = new NameGenerator();
            String fieldName;
            boolean collision;
            do {
                fieldName = ng.next();
                collision = false;
                for (FieldNode fn : cn.fields) {
                    if (fn.name.equals(fieldName)) {
                        collision = true;
                        break;
                    }
                }
            } while (collision);
            cn.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, fieldName, "[I", null, null));
            
            MethodNode clinit = null;
            for (MethodNode mn : cn.methods) {
                if (mn.name.equals("<clinit>")) {
                    clinit = mn;
                    break;
                }
            }
            if (clinit == null) {
                clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
                clinit.instructions.add(new InsnNode(Opcodes.RETURN));
                cn.methods.add(clinit);
            }
            
            InsnList il = new InsnList();
            il.add(new LdcInsnNode(distinctInts.size()));
            il.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
            for(int i=0; i<distinctInts.size(); i++) {
                il.add(new InsnNode(Opcodes.DUP));
                il.add(new LdcInsnNode(i));
                il.add(new LdcInsnNode(distinctInts.get(i)));
                il.add(new InsnNode(Opcodes.IASTORE));
            }
            il.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, fieldName, "[I"));
            clinit.instructions.insert(il);
            
            for (MethodNode mn : cn.methods) {
                 if (mn.name.equals("<clinit>")) continue;
                 ListIterator<AbstractInsnNode> it = mn.instructions.iterator();
                 while(it.hasNext()) {
                     AbstractInsnNode insn = it.next();
                     int val = Integer.MAX_VALUE; 
                     boolean found = false;
                     
                     if (insn instanceof LdcInsnNode) {
                         LdcInsnNode ldc = (LdcInsnNode) insn;
                         if (ldc.cst instanceof Integer) {
                             val = (Integer) ldc.cst;
                             found = true;
                         }
                     } else if (insn instanceof IntInsnNode) {
                         IntInsnNode intInsn = (IntInsnNode) insn;
                         if (intInsn.getOpcode() == Opcodes.SIPUSH || intInsn.getOpcode() == Opcodes.BIPUSH) {
                             val = intInsn.operand;
                             found = true;
                         }
                     }
                     
                     if (found) {
                         int idx = distinctInts.indexOf(val);
                         if (idx != -1) {
                             InsnList replacement = new InsnList();
                             replacement.add(new FieldInsnNode(Opcodes.GETSTATIC, cn.name, fieldName, "[I"));
                             replacement.add(new LdcInsnNode(idx));
                             replacement.add(new InsnNode(Opcodes.IALOAD));
                             
                             mn.instructions.insertBefore(insn, replacement);
                             it.remove();
                         }
                     }
                 }
            }
        }
    }
}
