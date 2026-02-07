package dev.eatgrapes.chlorine.transformers.impl;

import dev.eatgrapes.chlorine.transformers.Transformer;
import dev.eatgrapes.chlorine.utils.AsmUtils;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

public class NumberObfuscationTransformer extends Transformer {
    private final Random random = new Random();

    @Override
    public String getName() {
        return "NumberObfuscation";
    }

    @Override
    public void transform(Map<String, ClassNode> classes, Map<String, String> manifest, Set<String> keeps) {
        for (ClassNode cn : classes.values()) {
            if (shouldKeep(cn.name, keeps)) continue;
            if (AsmUtils.isInterface(cn)) continue;
            if (AsmUtils.isModuleInfo(cn)) continue;

            for (MethodNode mn : cn.methods) {
                ListIterator<AbstractInsnNode> it = mn.instructions.iterator();
                while (it.hasNext()) {
                    AbstractInsnNode insn = it.next();
                    InsnList replacement = null;

                    if (insn instanceof InsnNode) {
                        replacement = handleInsn(insn.getOpcode());
                    } else if (insn instanceof IntInsnNode) {
                        replacement = handleIntInsn((IntInsnNode) insn);
                    } else if (insn instanceof LdcInsnNode) {
                        replacement = handleLdcInsn((LdcInsnNode) insn);
                    }

                    if (replacement != null) {
                        mn.instructions.insertBefore(insn, replacement);
                        it.remove();
                    }
                }
            }
        }
    }

    private InsnList handleInsn(int opcode) {
        switch (opcode) {
            case Opcodes.ICONST_M1: return obfuscateInt(-1, 3);
            case Opcodes.ICONST_0: return obfuscateInt(0, 3);
            case Opcodes.ICONST_1: return obfuscateInt(1, 3);
            case Opcodes.ICONST_2: return obfuscateInt(2, 3);
            case Opcodes.ICONST_3: return obfuscateInt(3, 3);
            case Opcodes.ICONST_4: return obfuscateInt(4, 3);
            case Opcodes.ICONST_5: return obfuscateInt(5, 3);
            case Opcodes.LCONST_0: return obfuscateLong(0L, 3);
            case Opcodes.LCONST_1: return obfuscateLong(1L, 3);
            case Opcodes.FCONST_0: return obfuscateFloat(0.0f);
            case Opcodes.FCONST_1: return obfuscateFloat(1.0f);
            case Opcodes.FCONST_2: return obfuscateFloat(2.0f);
            case Opcodes.DCONST_0: return obfuscateDouble(0.0);
            case Opcodes.DCONST_1: return obfuscateDouble(1.0);
        }
        return null;
    }

    private InsnList handleIntInsn(IntInsnNode insn) {
        if (insn.getOpcode() == Opcodes.BIPUSH || insn.getOpcode() == Opcodes.SIPUSH) {
            return obfuscateInt(insn.operand, 3);
        }
        return null;
    }

    private InsnList handleLdcInsn(LdcInsnNode insn) {
        if (insn.cst instanceof Integer) {
            return obfuscateInt((Integer) insn.cst, 3);
        } else if (insn.cst instanceof Long) {
            return obfuscateLong((Long) insn.cst, 3);
        } else if (insn.cst instanceof Float) {
            return obfuscateFloat((Float) insn.cst);
        } else if (insn.cst instanceof Double) {
            return obfuscateDouble((Double) insn.cst);
        }
        return null;
    }

    private InsnList obfuscateInt(int value, int depth) {
        InsnList list = new InsnList();
        if (depth <= 0) {
            list.add(new LdcInsnNode(value));
            return list;
        }

        int type = random.nextInt(4);
        int key = random.nextInt();

        switch (type) {
            case 0:
                list.add(obfuscateInt(value ^ key, depth - 1));
                list.add(obfuscateInt(key, depth - 1));
                list.add(new InsnNode(Opcodes.IXOR));
                break;
            case 1:
                list.add(obfuscateInt(value - key, depth - 1));
                list.add(obfuscateInt(key, depth - 1));
                list.add(new InsnNode(Opcodes.IADD));
                break;
            case 2:
                list.add(obfuscateInt(value + key, depth - 1));
                list.add(obfuscateInt(key, depth - 1));
                list.add(new InsnNode(Opcodes.ISUB));
                break;
            case 3:
                list.add(obfuscateInt(~value, depth - 1));
                list.add(new InsnNode(Opcodes.ICONST_M1));
                list.add(new InsnNode(Opcodes.IXOR));
                break;
        }
        return list;
    }

    private InsnList obfuscateLong(long value, int depth) {
        InsnList list = new InsnList();
        if (depth <= 0) {
            list.add(new LdcInsnNode(value));
            return list;
        }

        int type = random.nextInt(4);
        long key = random.nextLong();

        switch (type) {
            case 0:
                list.add(obfuscateLong(value ^ key, depth - 1));
                list.add(obfuscateLong(key, depth - 1));
                list.add(new InsnNode(Opcodes.LXOR));
                break;
            case 1:
                list.add(obfuscateLong(value - key, depth - 1));
                list.add(obfuscateLong(key, depth - 1));
                list.add(new InsnNode(Opcodes.LADD));
                break;
            case 2:
                list.add(obfuscateLong(value + key, depth - 1));
                list.add(obfuscateLong(key, depth - 1));
                list.add(new InsnNode(Opcodes.LSUB));
                break;
            case 3:
                list.add(obfuscateLong(~value, depth - 1));
                list.add(new LdcInsnNode(-1L));
                list.add(new InsnNode(Opcodes.LXOR));
                break;
        }
        return list;
    }

    private InsnList obfuscateFloat(float value) {
        InsnList list = new InsnList();
        list.add(new LdcInsnNode(value));
        return list;
    }

    private InsnList obfuscateDouble(double value) {
        InsnList list = new InsnList();
        list.add(new LdcInsnNode(value));
        return list;
    }
}