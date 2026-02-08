package dev.eatgrapes.chlorine.transformers.impl;

import dev.eatgrapes.chlorine.transformers.Transformer;
import dev.eatgrapes.chlorine.utils.AsmUtils;
import dev.eatgrapes.chlorine.utils.InsnBuilder;
import dev.eatgrapes.chlorine.utils.NameGenerator;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class StringEncryptionTransformer extends Transformer {
    private final byte[] keyBytes = new byte[16];
    private final NameGenerator nameGen = new NameGenerator();

    public StringEncryptionTransformer() {
        new Random().nextBytes(keyBytes);
    }

    @Override
    public String getName() {
        return "StringEncryption";
    }

    @Override
    public void transform(Map<String, ClassNode> classes, Map<String, String> manifest, Set<String> keeps) {
        List<ClassNode> candidates = new ArrayList<>();
        for (ClassNode cn : classes.values()) {
            if (!AsmUtils.isInterface(cn) && !AsmUtils.isModuleInfo(cn) && !shouldKeep(cn.name, keeps)) {
                candidates.add(cn);
            }
        }

        if (candidates.isEmpty()) return;

        ClassNode hostClass = candidates.get(new Random().nextInt(candidates.size()));
        String decryptName = nameGen.nextMethod();
        String bootstrapName = nameGen.nextMethod();
        String xorName = nameGen.nextMethod();

        hostClass.methods.add(createXorHelper(xorName));
        hostClass.methods.add(createDecryptMethod(hostClass.name, decryptName, xorName));
        hostClass.methods.add(createBootstrapMethod(hostClass.name, bootstrapName, decryptName));

        for (ClassNode cn : classes.values()) {
            if (shouldKeep(cn.name, keeps)) continue;
            if (AsmUtils.isInterface(cn)) continue;

            for (MethodNode mn : cn.methods) {
                if (cn.name.equals(hostClass.name) && (mn.name.equals(decryptName) || mn.name.equals(bootstrapName) || mn.name.equals(xorName))) continue;
                if (AsmUtils.isAbstract(mn) || AsmUtils.isNative(mn)) continue;

                List<InvokeDynamicInsnNode> toReplace = new ArrayList<>();
                ListIterator<AbstractInsnNode> it = mn.instructions.iterator();
                while (it.hasNext()) {
                    AbstractInsnNode insn = it.next();
                    if (insn instanceof InvokeDynamicInsnNode) {
                        toReplace.add((InvokeDynamicInsnNode) insn);
                    }
                }
                
                for (InvokeDynamicInsnNode indy : toReplace) {
                    InsnList replacement = deoptimizeIndy(mn, indy);
                    if (replacement != null) {
                        mn.instructions.insertBefore(indy, replacement);
                        mn.instructions.remove(indy);
                    }
                }

                it = mn.instructions.iterator();
                while (it.hasNext()) {
                    AbstractInsnNode insn = it.next();
                    if (insn instanceof LdcInsnNode) {
                        LdcInsnNode ldc = (LdcInsnNode) insn;
                        if (ldc.cst instanceof String) {
                            String original = (String) ldc.cst;
                            if (original.length() > 5000) continue;

                            try {
                                String encrypted = encrypt(original);
                                
                                Handle bsmHandle = new Handle(
                                    Opcodes.H_INVOKESTATIC,
                                    hostClass.name,
                                    bootstrapName,
                                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;)Ljava/lang/invoke/CallSite;",
                                    false
                                );

                                mn.instructions.set(insn, new InvokeDynamicInsnNode(
                                    nameGen.next(), 
                                    "()Ljava/lang/String;",
                                    bsmHandle,
                                    encrypted
                                ));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        }
    }

    private InsnList deoptimizeIndy(MethodNode mn, InvokeDynamicInsnNode indy) {
        if (!"makeConcatWithConstants".equals(indy.name) && !"makeConcat".equals(indy.name)) return null;
        if (!indy.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory")) return null;

        String recipe = null;
        Object[] constants = new Object[0];

        if (indy.bsmArgs.length > 0) {
            if (indy.bsmArgs[0] instanceof String) {
                recipe = (String) indy.bsmArgs[0];
                if (indy.bsmArgs.length > 1) {
                    constants = Arrays.copyOfRange(indy.bsmArgs, 1, indy.bsmArgs.length);
                }
            }
        }

        if (recipe == null) {
            Type[] args = Type.getArgumentTypes(indy.desc);
            StringBuilder sb = new StringBuilder();
            for (int i=0; i<args.length; i++) sb.append("\u0001");
            recipe = sb.toString();
        }

        Type[] args = Type.getArgumentTypes(indy.desc);
        int[] locals = new int[args.length];
        
        InsnBuilder b = InsnBuilder.create();
        
        for (int i = args.length - 1; i >= 0; i--) {
            Type t = args[i];
            int local = mn.maxLocals;
            mn.maxLocals += t.getSize();
            locals[i] = local;
            b.var(t.getOpcode(Opcodes.ISTORE), local);
        }

        b.newInstance("java/lang/StringBuilder");
        b.invokeSpecial("java/lang/StringBuilder", "<init>", "()V");

        int argIdx = 0;
        int constIdx = 0;
        StringBuilder literal = new StringBuilder();

        for (int i = 0; i < recipe.length(); i++) {
            char c = recipe.charAt(i);
            if (c == '\u0001') {
                if (literal.length() > 0) {
                    b.ldc(literal.toString());
                    b.invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
                    literal.setLength(0);
                }
                Type t = args[argIdx];
                b.var(t.getOpcode(Opcodes.ILOAD), locals[argIdx]);
                b.invokeVirtual("java/lang/StringBuilder", "append", "(" + t.getDescriptor() + ")Ljava/lang/StringBuilder;");
                argIdx++;
            } else if (c == '\u0002') {
                if (literal.length() > 0) {
                    b.ldc(literal.toString());
                    b.invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
                    literal.setLength(0);
                }
                Object cst = constants[constIdx++];
                b.ldc(cst);
                if (cst instanceof String) {
                    b.invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
                } else {
                    b.invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;");
                }
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            b.ldc(literal.toString());
            b.invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
        }

        b.invokeVirtual("java/lang/StringBuilder", "toString", "()Ljava/lang/String;");
        return b.build();
    }

    private String encrypt(String original) throws Exception {
        byte[] input = original.getBytes(StandardCharsets.UTF_8);
        byte[] iv = new byte[16];
        new Random().nextBytes(iv);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        SecretKeySpec skeySpec = new SecretKeySpec(keyBytes, "AES");

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, skeySpec, ivSpec);

        byte[] encrypted = cipher.doFinal(input);

        for (int i = 0; i < encrypted.length; i++) {
            encrypted[i] = (byte) (encrypted[i] ^ keyBytes[i % keyBytes.length]);
        }

        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

        return Base64.getEncoder().encodeToString(combined);
    }

    private MethodNode createDecryptMethod(String owner, String methodName, String xorName) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, methodName, "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        mn.maxLocals = 20;
        InsnBuilder b = InsnBuilder.create();

        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        mn.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));

        b.label(start);

        pushStringStack(b, "java.util.Base64", owner, xorName);
        b.invokeStatic("java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;");
        pushStringStack(b, "getDecoder", owner, xorName);
        b.iconst(0);
        b.type(Opcodes.ANEWARRAY, "java/lang/Class");
        b.invokeVirtual("java/lang/Class", "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
        b.aconst_null();
        b.iconst(0);
        b.type(Opcodes.ANEWARRAY, "java/lang/Object");
        b.invokeVirtual("java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
        b.var(Opcodes.ASTORE, 1);

        b.var(Opcodes.ALOAD, 1);
        b.invokeVirtual("java/lang/Object", "getClass", "()Ljava/lang/Class;");
        pushStringStack(b, "decode", owner, xorName);
        b.iconst(1);
        b.type(Opcodes.ANEWARRAY, "java/lang/Class");
        b.dup();
        b.iconst(0);
        b.ldc(org.objectweb.asm.Type.getType("Ljava/lang/String;"));
        b.insn(Opcodes.AASTORE);
        b.invokeVirtual("java/lang/Class", "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
        b.var(Opcodes.ALOAD, 1);
        b.iconst(1);
        b.type(Opcodes.ANEWARRAY, "java/lang/Object");
        b.dup();
        b.iconst(0);
        b.var(Opcodes.ALOAD, 0);
        b.insn(Opcodes.AASTORE);
        b.invokeVirtual("java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
        b.checkcast("[B");
        b.var(Opcodes.ASTORE, 2);

        b.bipush(16);
        b.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        b.var(Opcodes.ASTORE, 3);
        b.var(Opcodes.ALOAD, 2);
        b.iconst(0);
        b.var(Opcodes.ALOAD, 3);
        b.iconst(0);
        b.bipush(16);
        b.invokeStatic("java/lang/System", "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V");

        b.var(Opcodes.ALOAD, 2);
        b.insn(Opcodes.ARRAYLENGTH);
        b.bipush(16);
        b.insn(Opcodes.ISUB);
        b.var(Opcodes.ISTORE, 4);
        b.var(Opcodes.ILOAD, 4);
        b.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        b.var(Opcodes.ASTORE, 5);
        b.var(Opcodes.ALOAD, 2);
        b.bipush(16);
        b.var(Opcodes.ALOAD, 5);
        b.iconst(0);
        b.var(Opcodes.ILOAD, 4);
        b.invokeStatic("java/lang/System", "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V");

        b.bipush(16);
        b.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        b.var(Opcodes.ASTORE, 6);
        for (int i = 0; i < 16; i++) {
            b.var(Opcodes.ALOAD, 6);
            b.bipush(i);
            b.bipush(keyBytes[i]);
            b.insn(Opcodes.BASTORE);
        }

        b.iconst(0);
        b.var(Opcodes.ISTORE, 7);
        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        b.label(loopStart);
        b.var(Opcodes.ILOAD, 7);
        b.var(Opcodes.ALOAD, 5);
        b.insn(Opcodes.ARRAYLENGTH);
        b.jump(Opcodes.IF_ICMPGE, loopEnd);
        
        b.var(Opcodes.ALOAD, 5);
        b.var(Opcodes.ILOAD, 7);
        b.var(Opcodes.ALOAD, 5);
        b.var(Opcodes.ILOAD, 7);
        b.insn(Opcodes.BALOAD);
        b.var(Opcodes.ALOAD, 6);
        b.var(Opcodes.ILOAD, 7);
        b.bipush(16);
        b.insn(Opcodes.IREM);
        b.insn(Opcodes.BALOAD);
        b.insn(Opcodes.IXOR);
        b.insn(Opcodes.I2B);
        b.insn(Opcodes.BASTORE);
        
        b.iinc(7, 1);
        b.jump(Opcodes.GOTO, loopStart);
        b.label(loopEnd);

        pushStringStack(b, "javax.crypto.Cipher", owner, xorName);
        b.invokeStatic("java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;");
        b.var(Opcodes.ASTORE, 8);

        b.var(Opcodes.ALOAD, 8);
        pushStringStack(b, "getInstance", owner, xorName);
        b.iconst(1);
        b.type(Opcodes.ANEWARRAY, "java/lang/Class");
        b.dup();
        b.iconst(0);
        b.ldc(org.objectweb.asm.Type.getType("Ljava/lang/String;"));
        b.insn(Opcodes.AASTORE);
        b.invokeVirtual("java/lang/Class", "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
        b.aconst_null();
        b.iconst(1);
        b.type(Opcodes.ANEWARRAY, "java/lang/Object");
        b.dup();
        b.iconst(0);
        pushStringStack(b, "AES/CBC/PKCS5Padding", owner, xorName);
        b.insn(Opcodes.AASTORE);
        b.invokeVirtual("java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
        b.var(Opcodes.ASTORE, 9);

        pushStringStack(b, "javax.crypto.spec.SecretKeySpec", owner, xorName);
        b.invokeStatic("java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;");
        b.var(Opcodes.ASTORE, 10);
        
        b.var(Opcodes.ALOAD, 10);
        b.iconst(2);
        b.type(Opcodes.ANEWARRAY, "java/lang/Class");
        b.dup();
        b.iconst(0);
        b.ldc(org.objectweb.asm.Type.getType("[B"));
        b.insn(Opcodes.AASTORE);
        b.dup();
        b.iconst(1);
        b.ldc(org.objectweb.asm.Type.getType("Ljava/lang/String;"));
        b.insn(Opcodes.AASTORE);
        b.invokeVirtual("java/lang/Class", "getConstructor", "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;");
        b.iconst(2);
        b.type(Opcodes.ANEWARRAY, "java/lang/Object");
        b.dup();
        b.iconst(0);
        b.var(Opcodes.ALOAD, 6);
        b.insn(Opcodes.AASTORE);
        b.dup();
        b.iconst(1);
        pushStringStack(b, "AES", owner, xorName);
        b.insn(Opcodes.AASTORE);
        b.invokeVirtual("java/lang/reflect/Constructor", "newInstance", "([Ljava/lang/Object;)Ljava/lang/Object;");
        b.var(Opcodes.ASTORE, 11);

        pushStringStack(b, "javax.crypto.spec.IvParameterSpec", owner, xorName);
        b.invokeStatic("java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;");
        b.var(Opcodes.ASTORE, 12);
        
        b.var(Opcodes.ALOAD, 12);
        b.iconst(1);
        b.type(Opcodes.ANEWARRAY, "java/lang/Class");
        b.dup();
        b.iconst(0);
        b.ldc(org.objectweb.asm.Type.getType("[B"));
        b.insn(Opcodes.AASTORE);
        b.invokeVirtual("java/lang/Class", "getConstructor", "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;");
        b.iconst(1);
        b.type(Opcodes.ANEWARRAY, "java/lang/Object");
        b.dup();
        b.iconst(0);
        b.var(Opcodes.ALOAD, 3);
        b.insn(Opcodes.AASTORE);
        b.invokeVirtual("java/lang/reflect/Constructor", "newInstance", "([Ljava/lang/Object;)Ljava/lang/Object;");
        b.var(Opcodes.ASTORE, 13);

        b.var(Opcodes.ALOAD, 8);
        pushStringStack(b, "init", owner, xorName);
        b.iconst(3);
        b.type(Opcodes.ANEWARRAY, "java/lang/Class");
        b.dup();
        b.iconst(0);
        b.getStatic("java/lang/Integer", "TYPE", "Ljava/lang/Class;");
        b.insn(Opcodes.AASTORE);
        b.dup();
        b.iconst(1);
        pushStringStack(b, "java.security.Key", owner, xorName);
        b.invokeStatic("java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;");
        b.insn(Opcodes.AASTORE);
        b.dup();
        b.iconst(2);
        pushStringStack(b, "java.security.spec.AlgorithmParameterSpec", owner, xorName);
        b.invokeStatic("java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;");
        b.insn(Opcodes.AASTORE);
        b.invokeVirtual("java/lang/Class", "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
        b.var(Opcodes.ALOAD, 9);
        b.iconst(3);
        b.type(Opcodes.ANEWARRAY, "java/lang/Object");
        b.dup();
        b.iconst(0);
        b.iconst(2);
        b.invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;");
        b.insn(Opcodes.AASTORE);
        b.dup();
        b.iconst(1);
        b.var(Opcodes.ALOAD, 11);
        b.insn(Opcodes.AASTORE);
        b.dup();
        b.iconst(2);
        b.var(Opcodes.ALOAD, 13);
        b.insn(Opcodes.AASTORE);
        b.invokeVirtual("java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");

        b.var(Opcodes.ALOAD, 8);
        pushStringStack(b, "doFinal", owner, xorName);
        b.iconst(1);
        b.type(Opcodes.ANEWARRAY, "java/lang/Class");
        b.dup();
        b.iconst(0);
        b.ldc(org.objectweb.asm.Type.getType("[B"));
        b.insn(Opcodes.AASTORE);
        b.invokeVirtual("java/lang/Class", "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
        b.var(Opcodes.ALOAD, 9);
        b.iconst(1);
        b.type(Opcodes.ANEWARRAY, "java/lang/Object");
        b.dup();
        b.iconst(0);
        b.var(Opcodes.ALOAD, 5);
        b.insn(Opcodes.AASTORE);
        b.invokeVirtual("java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
        b.checkcast("[B");
        b.var(Opcodes.ASTORE, 14);

        b.newInstance("java/lang/String");
        b.var(Opcodes.ALOAD, 14);
        b.getStatic("java/nio/charset/StandardCharsets", "UTF_8", "Ljava/nio/charset/Charset;");
        b.invokeSpecial("java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V");
        b.label(end);
        b.areturn();

        b.label(handler);
        b.dup();
        b.invokeVirtual("java/lang/Throwable", "printStackTrace", "()V");
        b.pop();
        b.aconst_null();
        b.areturn();

        mn.instructions.add(b.build());
        return mn;
    }

    private MethodNode createXorHelper(String methodName) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, methodName, "(Ljava/lang/String;I)Ljava/lang/String;", null, null);
        mn.maxLocals = 10;
        InsnBuilder b = InsnBuilder.create();
        
        b.var(Opcodes.ALOAD, 0);
        b.invokeVirtual("java/lang/String", "toCharArray", "()[C");
        b.var(Opcodes.ASTORE, 2);
        
        b.iconst(0);
        b.var(Opcodes.ISTORE, 3);
        
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        
        b.label(start);
        b.var(Opcodes.ILOAD, 3);
        b.var(Opcodes.ALOAD, 2);
        b.insn(Opcodes.ARRAYLENGTH);
        b.jump(Opcodes.IF_ICMPGE, end);
        
        b.var(Opcodes.ALOAD, 2);
        b.var(Opcodes.ILOAD, 3);
        b.insn(Opcodes.DUP2);
        b.insn(Opcodes.CALOAD);
        b.var(Opcodes.ILOAD, 1);
        b.insn(Opcodes.IXOR);
        b.insn(Opcodes.I2C);
        b.insn(Opcodes.CASTORE);
        
        b.iinc(3, 1);
        b.jump(Opcodes.GOTO, start);
        b.label(end);
        
        b.newInstance("java/lang/String");
        b.var(Opcodes.ALOAD, 2);
        b.invokeSpecial("java/lang/String", "<init>", "([C)V");
        b.areturn();
        
        mn.instructions.add(b.build());
        return mn;
    }

    private MethodNode createBootstrapMethod(String owner, String methodName, String decryptName) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, methodName, 
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;)Ljava/lang/invoke/CallSite;", null, null);
        mn.maxLocals = 10;
        InsnBuilder b = InsnBuilder.create();
        
        b.var(Opcodes.ALOAD, 3);
        b.invokeStatic(owner, decryptName, "(Ljava/lang/String;)Ljava/lang/String;");
        b.var(Opcodes.ASTORE, 4);
        
        b.ldc(org.objectweb.asm.Type.getType("Ljava/lang/String;"));
        b.var(Opcodes.ALOAD, 4);
        b.invokeStatic("java/lang/invoke/MethodHandles", "constant", "(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;");
        b.var(Opcodes.ASTORE, 5);
        
        b.newInstance("java/lang/invoke/ConstantCallSite");
        b.var(Opcodes.ALOAD, 5);
        b.invokeSpecial("java/lang/invoke/ConstantCallSite", "<init>", "(Ljava/lang/invoke/MethodHandle;)V");
        b.areturn();
        
        mn.instructions.add(b.build());
        return mn;
    }

    private void pushStringStack(InsnBuilder b, String s, String owner, String xorName) {
        int key = new Random().nextInt(255);
        char[] chars = s.toCharArray();
        char[] encrypted = new char[chars.length];
        for (int i = 0; i < chars.length; i++) {
            encrypted[i] = (char) (chars[i] ^ key);
        }
        String encryptedString = new String(encrypted);
        
        b.ldc(encryptedString);
        b.ldc(key);
        b.invokeStatic(owner, xorName, "(Ljava/lang/String;I)Ljava/lang/String;");
    }
}