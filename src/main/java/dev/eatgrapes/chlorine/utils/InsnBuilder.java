package dev.eatgrapes.chlorine.utils;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public class InsnBuilder {
    private final InsnList list = new InsnList();

    public static InsnBuilder create() {
        return new InsnBuilder();
    }

    public InsnList build() {
        return list;
    }

    public InsnBuilder insn(int opcode) {
        list.add(new InsnNode(opcode));
        return this;
    }

    public InsnBuilder ldc(Object cst) {
        list.add(new LdcInsnNode(cst));
        return this;
    }

    public InsnBuilder type(int opcode, String type) {
        list.add(new TypeInsnNode(opcode, type));
        return this;
    }

    public InsnBuilder field(int opcode, String owner, String name, String desc) {
        list.add(new FieldInsnNode(opcode, owner, name, desc));
        return this;
    }

    public InsnBuilder method(int opcode, String owner, String name, String desc) {
        list.add(new MethodInsnNode(opcode, owner, name, desc));
        return this;
    }

    public InsnBuilder method(int opcode, String owner, String name, String desc, boolean itf) {
        list.add(new MethodInsnNode(opcode, owner, name, desc, itf));
        return this;
    }

    public InsnBuilder var(int opcode, int varIndex) {
        list.add(new VarInsnNode(opcode, varIndex));
        return this;
    }

    public InsnBuilder jump(int opcode, LabelNode label) {
        list.add(new JumpInsnNode(opcode, label));
        return this;
    }

    public InsnBuilder label(LabelNode label) {
        list.add(label);
        return this;
    }

    public InsnBuilder bipush(int value) {
        list.add(new IntInsnNode(Opcodes.BIPUSH, value));
        return this;
    }

    public InsnBuilder sipush(int value) {
        list.add(new IntInsnNode(Opcodes.SIPUSH, value));
        return this;
    }

    public InsnBuilder iinc(int varIndex, int increment) {
        list.add(new IincInsnNode(varIndex, increment));
        return this;
    }

    public InsnBuilder add(AbstractInsnNode insn) {
        list.add(insn);
        return this;
    }

    public InsnBuilder add(InsnList other) {
        list.add(other);
        return this;
    }

    public InsnBuilder iconst(int value) {
        if (value >= -1 && value <= 5) {
            list.add(new InsnNode(Opcodes.ICONST_0 + value));
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            list.add(new IntInsnNode(Opcodes.BIPUSH, value));
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            list.add(new IntInsnNode(Opcodes.SIPUSH, value));
        } else {
            list.add(new LdcInsnNode(value));
        }
        return this;
    }

    public InsnBuilder lconst(long value) {
        if (value == 0L) {
            list.add(new InsnNode(Opcodes.LCONST_0));
        } else if (value == 1L) {
            list.add(new InsnNode(Opcodes.LCONST_1));
        } else {
            list.add(new LdcInsnNode(value));
        }
        return this;
    }

    public InsnBuilder newInstance(String type) {
        list.add(new TypeInsnNode(Opcodes.NEW, type));
        list.add(new InsnNode(Opcodes.DUP));
        return this;
    }

    public InsnBuilder throwNew(String exceptionType) {
        list.add(new TypeInsnNode(Opcodes.NEW, exceptionType));
        list.add(new InsnNode(Opcodes.DUP));
        list.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, exceptionType, "<init>", "()V"));
        list.add(new InsnNode(Opcodes.ATHROW));
        return this;
    }

    public InsnBuilder invokeStatic(String owner, String name, String desc) {
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, name, desc));
        return this;
    }

    public InsnBuilder invokeVirtual(String owner, String name, String desc) {
        list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, name, desc));
        return this;
    }

    public InsnBuilder invokeSpecial(String owner, String name, String desc) {
        list.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, name, desc));
        return this;
    }

    public InsnBuilder getStatic(String owner, String name, String desc) {
        list.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, name, desc));
        return this;
    }

    public InsnBuilder putStatic(String owner, String name, String desc) {
        list.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, name, desc));
        return this;
    }

    public InsnBuilder checkcast(String type) {
        list.add(new TypeInsnNode(Opcodes.CHECKCAST, type));
        return this;
    }

    public InsnBuilder pop() {
        list.add(new InsnNode(Opcodes.POP));
        return this;
    }

    public InsnBuilder pop2() {
        list.add(new InsnNode(Opcodes.POP2));
        return this;
    }

    public InsnBuilder dup() {
        list.add(new InsnNode(Opcodes.DUP));
        return this;
    }

    public InsnBuilder swap() {
        list.add(new InsnNode(Opcodes.SWAP));
        return this;
    }

    public InsnBuilder aconst_null() {
        list.add(new InsnNode(Opcodes.ACONST_NULL));
        return this;
    }

    public InsnBuilder areturn() {
        list.add(new InsnNode(Opcodes.ARETURN));
        return this;
    }

    public InsnBuilder ireturn() {
        list.add(new InsnNode(Opcodes.IRETURN));
        return this;
    }

    public InsnBuilder vreturn() {
        list.add(new InsnNode(Opcodes.RETURN));
        return this;
    }
}
