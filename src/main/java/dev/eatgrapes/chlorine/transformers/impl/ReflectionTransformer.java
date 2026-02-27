package dev.eatgrapes.chlorine.transformers.impl;

import dev.eatgrapes.chlorine.transformers.Transformer;
import dev.eatgrapes.chlorine.utils.AsmUtils;
import dev.eatgrapes.chlorine.utils.InsnBuilder;
import dev.eatgrapes.chlorine.utils.NameGenerator;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

public class ReflectionTransformer extends Transformer {
    private static final int METHOD_OBFUSCATION_RATE = 100;
    private static final int FIELD_OBFUSCATION_RATE = 25;
    private static final int CALL_KIND_STATIC = 0;
    private static final int CALL_KIND_VIRTUAL = 1;

    private static final String DECODE_DESC = "(Ljava/lang/String;I)Ljava/lang/String;";
    private static final String SIGNATURE_DESC = "(Ljava/lang/String;[Ljava/lang/Class;Ljava/lang/Class;)I";
    private static final String BOOTSTRAP_DESC =
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
            "Ljava/lang/String;Ljava/lang/String;Ljava/lang/Integer;Ljava/lang/Integer;Ljava/lang/Integer;)" +
            "Ljava/lang/invoke/CallSite;";

    private final Random random = new Random();
    private final NameGenerator indyNameGen = NameGenerator.local("i");
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

            CallSiteContext context = createContext(cn);
            boolean changed = false;
            for (MethodNode mn : cn.methods) {
                if (AsmUtils.isAbstract(mn) || AsmUtils.isNative(mn)) continue;
                if ((mn.access & Opcodes.ACC_SYNTHETIC) != 0) continue;
                if (mn.name.equals(context.bootstrapName) || mn.name.equals(context.decodeName) || mn.name.equals(context.signatureName)) continue;
                if (transformMethod(cn, mn, context)) {
                    changed = true;
                }
            }

            if (changed) {
                if (cn.version < Opcodes.V1_7) {
                    cn.version = Opcodes.V1_7;
                }
                cn.methods.add(createDecodeMethod(cn.name, context.decodeName, context));
                cn.methods.add(createSignatureMethod(context.signatureName, context));
                cn.methods.add(createBootstrapMethod(cn.name, context.bootstrapName, context.decodeName, context));
            }
        }
    }

    private boolean transformMethod(ClassNode owner, MethodNode mn, CallSiteContext context) {
        boolean changed = false;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; ) {
            AbstractInsnNode next = insn.getNext();

            if (insn instanceof MethodInsnNode) {
                MethodInsnNode min = (MethodInsnNode) insn;
                if (shouldTransformMethodCall(min)) {
                    InvokeDynamicInsnNode replacement = createInvokeDynamicCall(owner, min, context);
                    if (replacement != null) {
                        mn.instructions.set(insn, replacement);
                        changed = true;
                    }
                }
            } else if (insn instanceof FieldInsnNode) {
                FieldInsnNode fin = (FieldInsnNode) insn;
                if (shouldTransformFieldAccess(fin)) {
                    InsnList replacement = createReflectiveFieldAccess(mn, fin);
                    if (replacement != null) {
                        mn.instructions.insertBefore(insn, replacement);
                        mn.instructions.remove(insn);
                        changed = true;
                    }
                }
            }
            insn = next;
        }
        return changed;
    }

    private boolean shouldTransformMethodCall(MethodInsnNode min) {
        boolean forceJdk = isForceJdkMethod(min);
        if (min.owner.startsWith("java/lang/reflect/")) return false;
        if (min.name.equals("<init>") || min.name.equals("<clinit>")) return false;
        if (isExternal(min.owner) && !forceJdk) return false;
        if (shouldKeep(min.owner, currentKeeps)) return false;
        if (min.getOpcode() == Opcodes.INVOKESPECIAL) return false;
        if (shouldKeepMember(min.owner, min.name, currentKeeps)) return false;
        int opcode = min.getOpcode();
        if (opcode != Opcodes.INVOKESTATIC && opcode != Opcodes.INVOKEVIRTUAL && opcode != Opcodes.INVOKEINTERFACE) {
            return false;
        }
        if (forceJdk) return true;
        return random.nextInt(100) < METHOD_OBFUSCATION_RATE;
    }

    private boolean shouldTransformFieldAccess(FieldInsnNode fin) {
        boolean forceJdk = isForceJdkField(fin);
        if (fin.getOpcode() != Opcodes.GETSTATIC) return false;
        if (isExternal(fin.owner) && !forceJdk) return false;
        if (shouldKeep(fin.owner, currentKeeps)) return false;
        if (shouldKeepMember(fin.owner, fin.name, currentKeeps)) return false;
        if (forceJdk) return true;
        return random.nextInt(100) < FIELD_OBFUSCATION_RATE;
    }

    private boolean isForceJdkMethod(MethodInsnNode min) {
        if ("java/lang/System".equals(min.owner) && "nanoTime".equals(min.name) && "()J".equals(min.desc) && min.getOpcode() == Opcodes.INVOKESTATIC) {
            return true;
        }
        if ("java/io/PrintStream".equals(min.owner) && min.getOpcode() == Opcodes.INVOKEVIRTUAL) {
            return "println".equals(min.name) || "print".equals(min.name);
        }
        return false;
    }

    private boolean isForceJdkField(FieldInsnNode fin) {
        return fin.getOpcode() == Opcodes.GETSTATIC
                && "java/lang/System".equals(fin.owner)
                && "out".equals(fin.name)
                && "Ljava/io/PrintStream;".equals(fin.desc);
    }

    private InvokeDynamicInsnNode createInvokeDynamicCall(ClassNode owner, MethodInsnNode min, CallSiteContext context) {
        int opcode = min.getOpcode();
        boolean isStatic = opcode == Opcodes.INVOKESTATIC;
        int kind = isStatic ? CALL_KIND_STATIC : CALL_KIND_VIRTUAL;

        Type original = Type.getMethodType(min.desc);
        Type[] args = original.getArgumentTypes();
        Type ret = original.getReturnType();

        String indyDesc;
        if (isStatic) {
            indyDesc = min.desc;
        } else {
            Type[] indyArgs = new Type[args.length + 1];
            indyArgs[0] = Type.getObjectType(min.owner);
            System.arraycopy(args, 0, indyArgs, 1, args.length);
            indyDesc = Type.getMethodDescriptor(ret, indyArgs);
        }

        int seed = random.nextInt();
        String encodedOwner = encode(min.owner.replace('/', '.'), seed ^ context.ownerSalt, context);
        String encodedName = encode(min.name, seed ^ context.nameSalt, context);
        int signatureToken = computeSignatureToken(min, seed, context);

        Handle bsm = new Handle(
                Opcodes.H_INVOKESTATIC,
                owner.name,
                context.bootstrapName,
                BOOTSTRAP_DESC,
                false
        );

        return new InvokeDynamicInsnNode(
                indyNameGen.nextMethod(),
                indyDesc,
                bsm,
                encodedOwner,
                encodedName,
                Integer.valueOf(kind),
                Integer.valueOf(seed),
                Integer.valueOf(signatureToken)
        );
    }

    private int computeSignatureToken(MethodInsnNode min, int seed, CallSiteContext context) {
        Type mt = Type.getMethodType(min.desc);
        Type[] args = mt.getArgumentTypes();
        int h = min.name.hashCode();
        h = Integer.rotateLeft(h ^ args.length, context.signatureRotateA) + context.signatureAdd;
        for (Type arg : args) {
            h = Integer.rotateLeft(h ^ runtimeTypeName(arg).hashCode(), context.signatureRotateB) + context.signatureMix;
        }
        h = Integer.rotateLeft(h ^ runtimeTypeName(mt.getReturnType()).hashCode(), context.signatureRotateA) + context.signatureAdd;
        return h ^ seed ^ context.signatureSalt;
    }

    private String runtimeTypeName(Type t) {
        switch (t.getSort()) {
            case Type.VOID: return "void";
            case Type.BOOLEAN: return "boolean";
            case Type.CHAR: return "char";
            case Type.BYTE: return "byte";
            case Type.SHORT: return "short";
            case Type.INT: return "int";
            case Type.FLOAT: return "float";
            case Type.LONG: return "long";
            case Type.DOUBLE: return "double";
            case Type.ARRAY: return t.getDescriptor().replace('/', '.');
            case Type.OBJECT: return t.getClassName();
            default: return t.getDescriptor();
        }
    }

    private InsnList createReflectiveFieldAccess(MethodNode mn, FieldInsnNode fin) {
        String className = fin.owner.replace('/', '.');
        String fieldName = fin.name;
        String desc = fin.desc;

        boolean isStatic = fin.getOpcode() == Opcodes.GETSTATIC || fin.getOpcode() == Opcodes.PUTSTATIC;
        boolean isGet = fin.getOpcode() == Opcodes.GETFIELD || fin.getOpcode() == Opcodes.GETSTATIC;

        if (!isGet || !isStatic) return null;

        InsnBuilder b = InsnBuilder.create();
        
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode continueLabel = new LabelNode();
        
        mn.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));

        b.label(start);
        b.ldc(className);
        b.invokeStatic("java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;");

        b.ldc(fieldName);
        b.invokeVirtual("java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;");
        b.dup();
        b.iconst(1);
        b.invokeVirtual("java/lang/reflect/Field", "setAccessible", "(Z)V");

        b.aconst_null();
        b.invokeVirtual("java/lang/reflect/Field", "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
        b.label(end);
        b.jump(Opcodes.GOTO, continueLabel);
        
        b.label(handler);
        b.insn(Opcodes.ATHROW);
        
        b.label(continueLabel);

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

    private MethodNode createDecodeMethod(String owner, String methodName, CallSiteContext context) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                methodName,
                DECODE_DESC,
                null,
                null
        );
        mn.maxLocals = 4;

        LabelNode loop = new LabelNode();
        LabelNode end = new LabelNode();
        InsnBuilder b = InsnBuilder.create();

        b.var(Opcodes.ALOAD, 0);
        b.invokeVirtual("java/lang/String", "toCharArray", "()[C");
        b.var(Opcodes.ASTORE, 2);
        b.iconst(0);
        b.var(Opcodes.ISTORE, 3);

        b.label(loop);
        b.var(Opcodes.ILOAD, 3);
        b.var(Opcodes.ALOAD, 2);
        b.insn(Opcodes.ARRAYLENGTH);
        b.jump(Opcodes.IF_ICMPGE, end);

        b.var(Opcodes.ILOAD, 1);
        b.var(Opcodes.ILOAD, 3);
        b.insn(Opcodes.IXOR);
        b.ldc(context.decodeMix);
        b.insn(Opcodes.IXOR);
        pushInt(b, context.decodeRotate);
        b.invokeStatic("java/lang/Integer", "rotateLeft", "(II)I");
        b.ldc(context.decodeAdd);
        b.insn(Opcodes.IADD);
        b.var(Opcodes.ISTORE, 1);

        b.var(Opcodes.ALOAD, 2);
        b.var(Opcodes.ILOAD, 3);
        b.insn(Opcodes.DUP2);
        b.insn(Opcodes.CALOAD);
        b.var(Opcodes.ILOAD, 1);
        b.ldc(0xffff);
        b.insn(Opcodes.IAND);
        b.insn(Opcodes.IXOR);
        b.insn(Opcodes.I2C);
        b.insn(Opcodes.CASTORE);

        b.iinc(3, 1);
        b.jump(Opcodes.GOTO, loop);

        b.label(end);
        b.newInstance("java/lang/String");
        b.var(Opcodes.ALOAD, 2);
        b.invokeSpecial("java/lang/String", "<init>", "([C)V");
        b.areturn();

        mn.instructions.add(b.build());
        return mn;
    }

    private MethodNode createSignatureMethod(String methodName, CallSiteContext context) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                methodName,
                SIGNATURE_DESC,
                null,
                null
        );
        mn.maxLocals = 6;

        LabelNode loop = new LabelNode();
        LabelNode end = new LabelNode();
        InsnBuilder b = InsnBuilder.create();

        b.var(Opcodes.ALOAD, 0);
        b.invokeVirtual("java/lang/String", "hashCode", "()I");
        b.var(Opcodes.ISTORE, 3);

        b.var(Opcodes.ILOAD, 3);
        b.var(Opcodes.ALOAD, 1);
        b.insn(Opcodes.ARRAYLENGTH);
        b.insn(Opcodes.IXOR);
        pushInt(b, context.signatureRotateA);
        b.invokeStatic("java/lang/Integer", "rotateLeft", "(II)I");
        b.ldc(context.signatureAdd);
        b.insn(Opcodes.IADD);
        b.var(Opcodes.ISTORE, 3);

        b.iconst(0);
        b.var(Opcodes.ISTORE, 4);

        b.label(loop);
        b.var(Opcodes.ILOAD, 4);
        b.var(Opcodes.ALOAD, 1);
        b.insn(Opcodes.ARRAYLENGTH);
        b.jump(Opcodes.IF_ICMPGE, end);

        b.var(Opcodes.ILOAD, 3);
        b.var(Opcodes.ALOAD, 1);
        b.var(Opcodes.ILOAD, 4);
        b.insn(Opcodes.AALOAD);
        b.invokeVirtual("java/lang/Class", "getName", "()Ljava/lang/String;");
        b.invokeVirtual("java/lang/String", "hashCode", "()I");
        b.insn(Opcodes.IXOR);
        pushInt(b, context.signatureRotateB);
        b.invokeStatic("java/lang/Integer", "rotateLeft", "(II)I");
        b.ldc(context.signatureMix);
        b.insn(Opcodes.IADD);
        b.var(Opcodes.ISTORE, 3);

        b.iinc(4, 1);
        b.jump(Opcodes.GOTO, loop);

        b.label(end);
        b.var(Opcodes.ILOAD, 3);
        b.var(Opcodes.ALOAD, 2);
        b.invokeVirtual("java/lang/Class", "getName", "()Ljava/lang/String;");
        b.invokeVirtual("java/lang/String", "hashCode", "()I");
        b.insn(Opcodes.IXOR);
        pushInt(b, context.signatureRotateA);
        b.invokeStatic("java/lang/Integer", "rotateLeft", "(II)I");
        b.ldc(context.signatureAdd);
        b.insn(Opcodes.IADD);
        b.ireturn();

        mn.instructions.add(b.build());
        return mn;
    }

    private MethodNode createBootstrapMethod(String owner, String methodName, String decodeName, CallSiteContext context) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                methodName,
                BOOTSTRAP_DESC,
                null,
                null
        );
        mn.maxLocals = 22;

        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode nonStaticParamAdjust = new LabelNode();
        LabelNode paramAdjustDone = new LabelNode();
        LabelNode loopCheck = new LabelNode();
        LabelNode loopNext = new LabelNode();
        LabelNode staticKindMismatch = new LabelNode();
        LabelNode staticKindDone = new LabelNode();
        LabelNode foundTarget = new LabelNode();
        LabelNode missingTarget = new LabelNode();
        LabelNode fallbackStatic = new LabelNode();
        LabelNode returnCallSite = new LabelNode();
        mn.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));

        InsnBuilder b = InsnBuilder.create();
        b.label(start);

        b.var(Opcodes.ALOAD, 6);
        b.invokeVirtual("java/lang/Integer", "intValue", "()I");
        b.var(Opcodes.ISTORE, 8);

        b.var(Opcodes.ALOAD, 5);
        b.invokeVirtual("java/lang/Integer", "intValue", "()I");
        b.var(Opcodes.ISTORE, 9);

        b.var(Opcodes.ALOAD, 7);
        b.invokeVirtual("java/lang/Integer", "intValue", "()I");
        b.var(Opcodes.ISTORE, 10);

        b.var(Opcodes.ALOAD, 3);
        b.var(Opcodes.ILOAD, 8);
        b.ldc(context.ownerSalt);
        b.insn(Opcodes.IXOR);
        b.invokeStatic(owner, decodeName, DECODE_DESC);
        b.var(Opcodes.ASTORE, 11);

        b.var(Opcodes.ALOAD, 4);
        b.var(Opcodes.ILOAD, 8);
        b.ldc(context.nameSalt);
        b.insn(Opcodes.IXOR);
        b.invokeStatic(owner, decodeName, DECODE_DESC);
        b.var(Opcodes.ASTORE, 12);

        b.var(Opcodes.ALOAD, 11);
        b.invokeStatic("java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;");
        b.var(Opcodes.ASTORE, 13);

        b.var(Opcodes.ALOAD, 13);
        b.invokeVirtual("java/lang/Class", "getDeclaredMethods", "()[Ljava/lang/reflect/Method;");
        b.var(Opcodes.ASTORE, 14);

        b.iconst(0);
        b.var(Opcodes.ISTORE, 15);

        b.var(Opcodes.ALOAD, 2);
        b.invokeVirtual("java/lang/invoke/MethodType", "parameterCount", "()I");
        b.var(Opcodes.ILOAD, 9);
        b.iconst(CALL_KIND_STATIC);
        b.jump(Opcodes.IF_ICMPNE, nonStaticParamAdjust);
        b.iconst(0);
        b.jump(Opcodes.GOTO, paramAdjustDone);
        b.label(nonStaticParamAdjust);
        b.iconst(1);
        b.label(paramAdjustDone);
        b.insn(Opcodes.ISUB);
        b.var(Opcodes.ISTORE, 16);

        b.aconst_null();
        b.var(Opcodes.ASTORE, 17);

        b.label(loopCheck);
        b.var(Opcodes.ILOAD, 15);
        b.var(Opcodes.ALOAD, 14);
        b.insn(Opcodes.ARRAYLENGTH);
        b.jump(Opcodes.IF_ICMPGE, missingTarget);

        b.var(Opcodes.ALOAD, 14);
        b.var(Opcodes.ILOAD, 15);
        b.insn(Opcodes.AALOAD);
        b.var(Opcodes.ASTORE, 18);

        b.var(Opcodes.ALOAD, 18);
        b.invokeVirtual("java/lang/reflect/Method", "getName", "()Ljava/lang/String;");
        b.var(Opcodes.ALOAD, 12);
        b.invokeVirtual("java/lang/String", "equals", "(Ljava/lang/Object;)Z");
        b.jump(Opcodes.IFEQ, loopNext);

        b.var(Opcodes.ALOAD, 18);
        b.invokeVirtual("java/lang/reflect/Method", "getModifiers", "()I");
        b.invokeStatic("java/lang/reflect/Modifier", "isStatic", "(I)Z");
        b.var(Opcodes.ILOAD, 9);
        b.iconst(CALL_KIND_STATIC);
        b.jump(Opcodes.IF_ICMPNE, staticKindMismatch);
        b.iconst(1);
        b.jump(Opcodes.GOTO, staticKindDone);
        b.label(staticKindMismatch);
        b.iconst(0);
        b.label(staticKindDone);
        b.jump(Opcodes.IF_ICMPNE, loopNext);

        b.var(Opcodes.ALOAD, 18);
        b.invokeVirtual("java/lang/reflect/Method", "getParameterCount", "()I");
        b.var(Opcodes.ILOAD, 16);
        b.jump(Opcodes.IF_ICMPNE, loopNext);

        b.var(Opcodes.ALOAD, 18);
        b.invokeVirtual("java/lang/reflect/Method", "getName", "()Ljava/lang/String;");
        b.var(Opcodes.ALOAD, 18);
        b.invokeVirtual("java/lang/reflect/Method", "getParameterTypes", "()[Ljava/lang/Class;");
        b.var(Opcodes.ALOAD, 18);
        b.invokeVirtual("java/lang/reflect/Method", "getReturnType", "()Ljava/lang/Class;");
        b.invokeStatic(owner, context.signatureName, SIGNATURE_DESC);
        b.var(Opcodes.ILOAD, 8);
        b.insn(Opcodes.IXOR);
        b.ldc(context.signatureSalt);
        b.insn(Opcodes.IXOR);
        b.var(Opcodes.ILOAD, 10);
        b.jump(Opcodes.IF_ICMPNE, loopNext);

        b.var(Opcodes.ALOAD, 18);
        b.var(Opcodes.ASTORE, 17);
        b.jump(Opcodes.GOTO, foundTarget);

        b.label(loopNext);
        b.iinc(15, 1);
        b.jump(Opcodes.GOTO, loopCheck);

        b.label(missingTarget);
        b.var(Opcodes.ILOAD, 9);
        b.iconst(CALL_KIND_STATIC);
        b.jump(Opcodes.IF_ICMPEQ, fallbackStatic);
        b.var(Opcodes.ALOAD, 2);
        b.iconst(0);
        b.iconst(1);
        b.invokeVirtual("java/lang/invoke/MethodType", "dropParameterTypes", "(II)Ljava/lang/invoke/MethodType;");
        b.var(Opcodes.ASTORE, 20);
        b.var(Opcodes.ALOAD, 0);
        b.var(Opcodes.ALOAD, 13);
        b.var(Opcodes.ALOAD, 12);
        b.var(Opcodes.ALOAD, 20);
        b.invokeVirtual(
                "java/lang/invoke/MethodHandles$Lookup",
                "findVirtual",
                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"
        );
        b.var(Opcodes.ASTORE, 19);
        b.jump(Opcodes.GOTO, returnCallSite);

        b.label(fallbackStatic);
        b.var(Opcodes.ALOAD, 0);
        b.var(Opcodes.ALOAD, 13);
        b.var(Opcodes.ALOAD, 12);
        b.var(Opcodes.ALOAD, 2);
        b.invokeVirtual(
                "java/lang/invoke/MethodHandles$Lookup",
                "findStatic",
                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"
        );
        b.var(Opcodes.ASTORE, 19);
        b.jump(Opcodes.GOTO, returnCallSite);

        b.label(foundTarget);
        b.var(Opcodes.ALOAD, 17);
        b.iconst(1);
        b.invokeVirtual("java/lang/reflect/Method", "setAccessible", "(Z)V");

        b.var(Opcodes.ALOAD, 0);
        b.var(Opcodes.ALOAD, 17);
        b.invokeVirtual(
                "java/lang/invoke/MethodHandles$Lookup",
                "unreflect",
                "(Ljava/lang/reflect/Method;)Ljava/lang/invoke/MethodHandle;"
        );
        b.var(Opcodes.ASTORE, 19);
        b.jump(Opcodes.GOTO, returnCallSite);

        b.label(returnCallSite);
        b.newInstance("java/lang/invoke/ConstantCallSite");
        b.var(Opcodes.ALOAD, 19);
        b.var(Opcodes.ALOAD, 2);
        b.invokeVirtual("java/lang/invoke/MethodHandle", "asType", "(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
        b.invokeSpecial("java/lang/invoke/ConstantCallSite", "<init>", "(Ljava/lang/invoke/MethodHandle;)V");
        b.areturn();

        b.label(end);
        b.label(handler);
        b.var(Opcodes.ASTORE, 21);
        b.newInstance("java/lang/BootstrapMethodError");
        b.var(Opcodes.ALOAD, 21);
        b.invokeSpecial("java/lang/BootstrapMethodError", "<init>", "(Ljava/lang/Throwable;)V");
        b.insn(Opcodes.ATHROW);

        mn.instructions.add(b.build());
        return mn;
    }

    private String encode(String value, int key, CallSiteContext context) {
        char[] chars = value.toCharArray();
        int k = key;
        for (int i = 0; i < chars.length; i++) {
            k = Integer.rotateLeft(k ^ i ^ context.decodeMix, context.decodeRotate) + context.decodeAdd;
            chars[i] = (char) (chars[i] ^ (k & 0xffff));
        }
        return new String(chars);
    }

    private CallSiteContext createContext(ClassNode cn) {
        String decodeName = nextUniqueMethodName(cn, "d");
        String bootstrapName = nextUniqueMethodName(cn, "b");
        String signatureName = nextUniqueMethodName(cn, "s");
        while (bootstrapName.equals(decodeName)) {
            bootstrapName = nextUniqueMethodName(cn, "b");
        }
        while (signatureName.equals(decodeName) || signatureName.equals(bootstrapName)) {
            signatureName = nextUniqueMethodName(cn, "s");
        }

        int base = scramble(cn.name.hashCode() ^ random.nextInt());
        int decodeMix = nonZero(scramble(base ^ random.nextInt()));
        int decodeAdd = nonZero(scramble(Integer.rotateLeft(base, 11) ^ random.nextInt()));
        int ownerSalt = nonZero(scramble(base ^ 0x6f57a9d1));
        int nameSalt = nonZero(scramble(base ^ 0x13a5b7c9));
        int decodeRotate = 5 + Math.floorMod(base, 23);
        int signatureSalt = nonZero(scramble(base ^ 0x9f31ab47));
        int signatureMix = nonZero(scramble(Integer.rotateRight(base, 7) ^ 0x34c2fd11));
        int signatureAdd = nonZero(scramble(Integer.rotateLeft(base, 19) ^ 0x73a1d5ef));
        int signatureRotateA = 3 + Math.floorMod(base, 19);
        int signatureRotateB = 5 + Math.floorMod(Integer.rotateRight(base, 5), 17);

        return new CallSiteContext(
                bootstrapName,
                decodeName,
                signatureName,
                ownerSalt,
                nameSalt,
                decodeMix,
                decodeAdd,
                decodeRotate,
                signatureSalt,
                signatureMix,
                signatureAdd,
                signatureRotateA,
                signatureRotateB
        );
    }

    private String nextUniqueMethodName(ClassNode cn, String prefix) {
        Set<String> names = new HashSet<>();
        for (MethodNode mn : cn.methods) {
            names.add(mn.name);
        }

        String candidate;
        do {
            candidate = "$" + prefix + "_" + Integer.toHexString(random.nextInt()) + "_" + indyNameGen.nextMethod();
        } while (names.contains(candidate));
        return candidate;
    }

    private int scramble(int value) {
        int v = value;
        v ^= (v >>> 16);
        v *= 0x7feb352d;
        v ^= (v >>> 15);
        v *= 0x846ca68b;
        v ^= (v >>> 16);
        return v;
    }

    private int nonZero(int value) {
        if (value == 0) {
            return random.nextInt() | 1;
        }
        return value;
    }

    private void pushInt(InsnBuilder b, int value) {
        if (value >= -1 && value <= 5) {
            b.iconst(value);
        } else {
            b.ldc(value);
        }
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

    private static class CallSiteContext {
        private final String bootstrapName;
        private final String decodeName;
        private final String signatureName;
        private final int ownerSalt;
        private final int nameSalt;
        private final int decodeMix;
        private final int decodeAdd;
        private final int decodeRotate;
        private final int signatureSalt;
        private final int signatureMix;
        private final int signatureAdd;
        private final int signatureRotateA;
        private final int signatureRotateB;

        private CallSiteContext(
                String bootstrapName,
                String decodeName,
                String signatureName,
                int ownerSalt,
                int nameSalt,
                int decodeMix,
                int decodeAdd,
                int decodeRotate,
                int signatureSalt,
                int signatureMix,
                int signatureAdd,
                int signatureRotateA,
                int signatureRotateB
        ) {
            this.bootstrapName = bootstrapName;
            this.decodeName = decodeName;
            this.signatureName = signatureName;
            this.ownerSalt = ownerSalt;
            this.nameSalt = nameSalt;
            this.decodeMix = decodeMix;
            this.decodeAdd = decodeAdd;
            this.decodeRotate = decodeRotate;
            this.signatureSalt = signatureSalt;
            this.signatureMix = signatureMix;
            this.signatureAdd = signatureAdd;
            this.signatureRotateA = signatureRotateA;
            this.signatureRotateB = signatureRotateB;
        }
    }
}
