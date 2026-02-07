package dev.eatgrapes.chlorine.transformers.impl;

import dev.eatgrapes.chlorine.transformers.Transformer;
import dev.eatgrapes.chlorine.utils.AsmUtils;
import dev.eatgrapes.chlorine.utils.InsnBuilder;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

public class ReflectionTransformer extends Transformer {
    private final Random random = new Random();
    private Set<String> currentKeeps;

    @Override
    public String getName() { return "Reflection"; }

    @Override
    public void transform(Map<String, ClassNode> classes, Map<String, String> manifest, Set<String> keeps) {
        this.currentKeeps = keeps;
        List<ClassNode> classList = new ArrayList<>(classes.values());
        for (ClassNode cn : classList) {
            if (shouldKeep(cn.name, keeps)) continue;
            if (AsmUtils.isInterface(cn)) continue;
            if (AsmUtils.isModuleInfo(cn)) continue;

            for (MethodNode mn : cn.methods) {
                if (AsmUtils.isAbstract(mn) || AsmUtils.isNative(mn)) continue;
                transformMethod(cn, mn, classes);
            }
        }
    }

    private void transformMethod(ClassNode owner, MethodNode mn, Map<String, ClassNode> classes) {
        ListIterator<AbstractInsnNode> it = mn.instructions.iterator();
        while (it.hasNext()) {
            AbstractInsnNode insn = it.next();

            if (insn instanceof MethodInsnNode) {
                MethodInsnNode min = (MethodInsnNode) insn;
                if (shouldTransformMethodCall(min)) {
                    InsnList replacement = createReflectiveMethodCall(min);
                    if (replacement != null) {
                        mn.instructions.insertBefore(insn, replacement);
                        it.remove();
                    }
                }
            } else if (insn instanceof FieldInsnNode) {
                FieldInsnNode fin = (FieldInsnNode) insn;
                if (shouldTransformFieldAccess(fin)) {
                    InsnList replacement = createReflectiveFieldAccess(fin);
                    if (replacement != null) {
                        mn.instructions.insertBefore(insn, replacement);
                        it.remove();
                    }
                }
            }
        }
    }

    private boolean shouldTransformMethodCall(MethodInsnNode min) {
        if (min.owner.startsWith("java/lang/reflect/")) return false;
        if (min.name.equals("<init>") || min.name.equals("<clinit>")) return false;
        if (isExternal(min.owner)) return false;
        if (shouldKeep(min.owner, currentKeeps)) return false;
        if (min.getOpcode() == Opcodes.INVOKEINTERFACE) return false;
        if (min.getOpcode() == Opcodes.INVOKESPECIAL) return false;
        return random.nextInt(100) < 50;
    }

    private boolean shouldTransformFieldAccess(FieldInsnNode fin) {
        if (isExternal(fin.owner)) return false;
        if (shouldKeep(fin.owner, currentKeeps)) return false;
        return random.nextInt(100) < 40;
    }

    private InsnList createReflectiveMethodCall(MethodInsnNode min) {
        String className = min.owner.replace('/', '.');
        String methodName = min.name;
        String desc = min.desc;

        boolean isStatic = min.getOpcode() == Opcodes.INVOKESTATIC;
        int paramCount = AsmUtils.getParamCount(desc);
        String returnType = AsmUtils.parseReturnType(desc);

        if (paramCount > 0) return null;

        InsnBuilder b = InsnBuilder.create();

        b.add(encryptString(className));
        b.invokeStatic("java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;");

        b.add(encryptString(methodName));

        b.iconst(0);
        b.type(Opcodes.ANEWARRAY, "java/lang/Class");

        b.invokeVirtual("java/lang/Class", "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
        b.dup();
        b.iconst(1);
        b.invokeVirtual("java/lang/reflect/Method", "setAccessible", "(Z)V");

        if (isStatic) {
            b.aconst_null();
        } else {
            b.swap();
        }

        b.iconst(0);
        b.type(Opcodes.ANEWARRAY, "java/lang/Object");

        b.invokeVirtual("java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");

        if (returnType.equals("V")) {
            b.pop();
        } else if (AsmUtils.isPrimitiveDesc(returnType)) {
            b.add(unboxPrimitive(returnType));
        } else {
            String castType = returnType.startsWith("[") ? returnType :
                             (returnType.startsWith("L") ? returnType.substring(1, returnType.length() - 1) : returnType);
            b.checkcast(castType);
        }

        return b.build();
    }

    private InsnList createReflectiveFieldAccess(FieldInsnNode fin) {
        String className = fin.owner.replace('/', '.');
        String fieldName = fin.name;
        String desc = fin.desc;

        boolean isStatic = fin.getOpcode() == Opcodes.GETSTATIC || fin.getOpcode() == Opcodes.PUTSTATIC;
        boolean isGet = fin.getOpcode() == Opcodes.GETFIELD || fin.getOpcode() == Opcodes.GETSTATIC;

        if (!isGet || !isStatic) return null;

        InsnBuilder b = InsnBuilder.create();

        b.add(encryptString(className));
        b.invokeStatic("java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;");

        b.add(encryptString(fieldName));
        b.invokeVirtual("java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;");
        b.dup();
        b.iconst(1);
        b.invokeVirtual("java/lang/reflect/Field", "setAccessible", "(Z)V");

        b.aconst_null();
        b.invokeVirtual("java/lang/reflect/Field", "get", "(Ljava/lang/Object;)Ljava/lang/Object;");

        if (AsmUtils.isPrimitiveDesc(desc)) {
            b.add(unboxPrimitive(desc));
        } else {
            String castType;
            if (desc.startsWith("L") && desc.endsWith(";")) {
                castType = desc.substring(1, desc.length() - 1);
            } else if (desc.startsWith("[")) {
                castType = desc;
            } else {
                castType = desc;
            }
            b.checkcast(castType);
        }

        return b.build();
    }

    private InsnList encryptString(String str) {
        int key = random.nextInt(200) + 1;

        InsnBuilder b = InsnBuilder.create();
        b.newInstance("java/lang/StringBuilder");
        b.invokeSpecial("java/lang/StringBuilder", "<init>", "()V");

        for (int i = 0; i < str.length(); i++) {
            int encrypted = str.charAt(i) ^ key;
            b.ldc(encrypted);
            b.ldc(key);
            b.insn(Opcodes.IXOR);
            b.insn(Opcodes.I2C);
            b.invokeVirtual("java/lang/StringBuilder", "append", "(C)Ljava/lang/StringBuilder;");
        }

        b.invokeVirtual("java/lang/StringBuilder", "toString", "()Ljava/lang/String;");

        return b.build();
    }

    private InsnList unboxPrimitive(String type) {
        InsnBuilder b = InsnBuilder.create();
        switch (type) {
            case "I":
                b.checkcast("java/lang/Integer");
                b.invokeVirtual("java/lang/Integer", "intValue", "()I");
                break;
            case "J":
                b.checkcast("java/lang/Long");
                b.invokeVirtual("java/lang/Long", "longValue", "()J");
                break;
            case "F":
                b.checkcast("java/lang/Float");
                b.invokeVirtual("java/lang/Float", "floatValue", "()F");
                break;
            case "D":
                b.checkcast("java/lang/Double");
                b.invokeVirtual("java/lang/Double", "doubleValue", "()D");
                break;
            case "Z":
                b.checkcast("java/lang/Boolean");
                b.invokeVirtual("java/lang/Boolean", "booleanValue", "()Z");
                break;
            case "B":
                b.checkcast("java/lang/Byte");
                b.invokeVirtual("java/lang/Byte", "byteValue", "()B");
                break;
            case "C":
                b.checkcast("java/lang/Character");
                b.invokeVirtual("java/lang/Character", "charValue", "()C");
                break;
            case "S":
                b.checkcast("java/lang/Short");
                b.invokeVirtual("java/lang/Short", "shortValue", "()S");
                break;
        }
        return b.build();
    }
}
