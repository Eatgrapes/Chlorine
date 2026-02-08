package dev.eatgrapes.chlorine.transformers.impl;

import dev.eatgrapes.chlorine.transformers.Transformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

public class FlowTransformer extends Transformer {
    @Override
    public String getName() { return "FlowObfuscation"; }

    @Override
    public void transform(Map<String, ClassNode> classes, Map<String, String> manifest, Set<String> keeps) {
        for (ClassNode cn : classes.values()) {
            if (shouldKeep(cn.name, keeps)) continue;
            
            for (MethodNode mn : cn.methods) {
                if (mn.instructions.size() > 0 && (mn.access & Opcodes.ACC_ABSTRACT) == 0 && (mn.access & Opcodes.ACC_NATIVE) == 0) {
                     if (mn.instructions.size() > 10) {
                         applyFlattening(mn);
                     }
                }
            }
        }
    }

    private void applyFlattening(MethodNode mn) {
        InsnList original = new InsnList();
        original.add(mn.instructions); 
        
        LabelNode loopHead = new LabelNode();
        
        LabelNode caseStart = new LabelNode(); 
        LabelNode caseStep1 = new LabelNode(); 
        LabelNode caseStep2 = new LabelNode(); 
        LabelNode caseReal = new LabelNode();  
        LabelNode defaultLabel = new LabelNode();
        
        LabelNode handlerAE = new LabelNode();
        LabelNode handlerNPE = new LabelNode();
        LabelNode handlerCCE = new LabelNode();
        
        LabelNode tryAE_Start = new LabelNode();
        LabelNode tryAE_End = new LabelNode();
        LabelNode tryNPE_Start = new LabelNode();
        LabelNode tryNPE_End = new LabelNode();
        LabelNode tryCCE_Start = new LabelNode();
        LabelNode tryCCE_End = new LabelNode();
        
        mn.tryCatchBlocks.add(new TryCatchBlockNode(tryAE_Start, tryAE_End, handlerAE, "java/lang/ArithmeticException"));
        mn.tryCatchBlocks.add(new TryCatchBlockNode(tryNPE_Start, tryNPE_End, handlerNPE, "java/lang/NullPointerException"));
        mn.tryCatchBlocks.add(new TryCatchBlockNode(tryCCE_Start, tryCCE_End, handlerCCE, "java/lang/ClassCastException"));
        
        int stateVar = mn.maxLocals;
        mn.maxLocals++; 
        
        mn.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 10));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J"));
        mn.instructions.add(new InsnNode(Opcodes.LCONST_0));
        mn.instructions.add(new InsnNode(Opcodes.LAND));
        mn.instructions.add(new InsnNode(Opcodes.L2I));
        mn.instructions.add(new InsnNode(Opcodes.IADD));
        mn.instructions.add(new VarInsnNode(Opcodes.ISTORE, stateVar));
        mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, loopHead));
        
        mn.instructions.add(loopHead);
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, stateVar));
        
        LabelNode[] keys = new LabelNode[] { caseStart, caseStep1, caseStep2, caseReal };
        int[] values = new int[] { 10, 15, 30, 81 };
        
        mn.instructions.add(new LookupSwitchInsnNode(defaultLabel, values, keys));
        
        mn.instructions.add(caseStart);
        mn.instructions.add(tryAE_Start);
        mn.instructions.add(new InsnNode(Opcodes.ICONST_1));
        mn.instructions.add(new InsnNode(Opcodes.ICONST_0));
        mn.instructions.add(new InsnNode(Opcodes.IDIV)); 
        mn.instructions.add(tryAE_End);
        mn.instructions.add(new InsnNode(Opcodes.POP));
        mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        mn.instructions.add(new InsnNode(Opcodes.ATHROW)); 
        
        mn.instructions.add(caseStep1);
        mn.instructions.add(tryNPE_Start);
        mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        mn.instructions.add(new InsnNode(Opcodes.ATHROW)); 
        mn.instructions.add(tryNPE_End);
        mn.instructions.add(new InsnNode(Opcodes.ATHROW));
        
        mn.instructions.add(caseStep2);
        mn.instructions.add(tryCCE_Start);
        mn.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        mn.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer")); 
        mn.instructions.add(tryCCE_End);
        mn.instructions.add(new InsnNode(Opcodes.POP));
        mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        mn.instructions.add(new InsnNode(Opcodes.ATHROW));
        
        mn.instructions.add(caseReal);
        mn.instructions.add(original);
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        
        mn.instructions.add(handlerAE);
        mn.instructions.add(new InsnNode(Opcodes.POP));
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, stateVar));
        mn.instructions.add(new InsnNode(Opcodes.ICONST_5));
        mn.instructions.add(new InsnNode(Opcodes.IADD));
        mn.instructions.add(new VarInsnNode(Opcodes.ISTORE, stateVar));
        mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, loopHead));
        
        mn.instructions.add(handlerNPE);
        mn.instructions.add(new InsnNode(Opcodes.POP));
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, stateVar));
        mn.instructions.add(new InsnNode(Opcodes.ICONST_2));
        mn.instructions.add(new InsnNode(Opcodes.IMUL));
        mn.instructions.add(new VarInsnNode(Opcodes.ISTORE, stateVar));
        mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, loopHead));
        
        mn.instructions.add(handlerCCE);
        mn.instructions.add(new InsnNode(Opcodes.POP));
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, stateVar));
        mn.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 79));
        mn.instructions.add(new InsnNode(Opcodes.IXOR));
        mn.instructions.add(new VarInsnNode(Opcodes.ISTORE, stateVar));
        mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, loopHead));
        
        mn.instructions.add(defaultLabel);
        mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        mn.instructions.add(new InsnNode(Opcodes.MONITORENTER)); 
        mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        mn.instructions.add(new InsnNode(Opcodes.ATHROW));
    }
}