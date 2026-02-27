package dev.eatgrapes.chlorine.transformers.impl;

import dev.eatgrapes.chlorine.transformers.Transformer;
import dev.eatgrapes.chlorine.utils.AsmUtils;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

public class FlowTransformer extends Transformer {
    private static final int MIN_INSTRUCTION_COUNT = 12;
    private static final int EXCEPTION_STAGE_COUNT = 3;
    private static final int MIN_JUNK_CASES = 2;
    private static final int MAX_JUNK_CASES = 3;
    private static final int MIN_DISPATCH_BUDGET = 8;
    private static final int MAX_DISPATCH_BUDGET = 14;

    private final Random random = new Random();

    @Override
    public String getName() { return "FlowObfuscation"; }

    @Override
    public void transform(Map<String, ClassNode> classes, Map<String, String> manifest, Set<String> keeps) {
        for (ClassNode cn : classes.values()) {
            if (shouldKeep(cn.name, keeps)) continue;
            if (AsmUtils.isInterface(cn) || AsmUtils.isModuleInfo(cn)) continue;

            for (MethodNode mn : cn.methods) {
                if (!shouldTransformMethod(mn)) continue;
                if (!shouldApplyByMethodSize(mn)) continue;
                applyFlattening(mn);
            }
        }
    }

    private boolean shouldTransformMethod(MethodNode mn) {
        if (mn.instructions == null || mn.instructions.size() < MIN_INSTRUCTION_COUNT) return false;
        if (AsmUtils.isAbstract(mn) || AsmUtils.isNative(mn)) return false;
        if (AsmUtils.isConstructor(mn)) return false;
        if ((mn.access & Opcodes.ACC_SYNTHETIC) != 0) return false;

        for (AbstractInsnNode insn : mn.instructions) {
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.JSR || opcode == Opcodes.RET) {
                return false;
            }
        }

        return true;
    }

    private boolean shouldApplyByMethodSize(MethodNode mn) {
        int size = mn.instructions.size();
        if (size <= 60) return true;
        if (size <= 140) return random.nextInt(100) < 90;
        if (size <= 260) return random.nextInt(100) < 72;
        return random.nextInt(100) < 45;
    }

    private void applyFlattening(MethodNode mn) {
        InsnList original = new InsnList();
        original.add(mn.instructions);

        int stateVar = mn.maxLocals;
        int mixVar = mn.maxLocals + 1;
        int sinkVar = mn.maxLocals + 2;
        int budgetVar = mn.maxLocals + 3;
        int gateResumeVar = mn.maxLocals + 4;
        mn.maxLocals += 5;

        Set<Integer> stableLoadSlots = collectStableLoadSlots(mn);
        List<AbstractInsnNode> snippetPool = collectSnippetPool(original, stableLoadSlots);

        Set<Integer> usedKeys = new HashSet<>();
        int keyStage0 = nextKey(usedKeys);
        int keyStage1 = nextKey(usedKeys);
        int keyStage2 = nextKey(usedKeys);
        int keyBridge = nextKey(usedKeys);
        int keyExit = nextKey(usedKeys);
        int keyReal = nextKey(usedKeys);
        int keyGate = nextKey(usedKeys);
        int gateResumeMask = random.nextInt();
        int gatePredicateMask = (1 << (2 + random.nextInt(3))) - 1;
        Type returnType = Type.getReturnType(mn.desc);

        int junkCaseCount = MIN_JUNK_CASES + random.nextInt(MAX_JUNK_CASES - MIN_JUNK_CASES + 1);
        List<Integer> junkKeys = new ArrayList<>();
        List<LabelNode> junkLabels = new ArrayList<>();
        for (int i = 0; i < junkCaseCount; i++) {
            junkKeys.add(nextKey(usedKeys));
            junkLabels.add(new LabelNode());
        }

        LabelNode loopHead = new LabelNode();
        LabelNode defaultLabel = new LabelNode();

        LabelNode caseStage0 = new LabelNode();
        LabelNode caseStage1 = new LabelNode();
        LabelNode caseStage2 = new LabelNode();
        LabelNode caseBridge = new LabelNode();
        LabelNode caseExitPivot = new LabelNode();
        LabelNode caseReal = new LabelNode();
        LabelNode caseGate = new LabelNode();

        List<ExceptionPattern> patterns = pickExceptionPatterns(EXCEPTION_STAGE_COUNT);

        LabelNode[] tryStarts = new LabelNode[EXCEPTION_STAGE_COUNT];
        LabelNode[] tryEnds = new LabelNode[EXCEPTION_STAGE_COUNT];
        LabelNode[] handlers = new LabelNode[EXCEPTION_STAGE_COUNT];
        for (int i = 0; i < EXCEPTION_STAGE_COUNT; i++) {
            tryStarts[i] = new LabelNode();
            tryEnds[i] = new LabelNode();
            handlers[i] = new LabelNode();
            mn.tryCatchBlocks.add(new TryCatchBlockNode(
                    tryStarts[i],
                    tryEnds[i],
                    handlers[i],
                    patterns.get(i).exceptionInternalName
            ));
        }

        Map<Integer, LabelNode> dispatchCases = new HashMap<>();
        dispatchCases.put(keyStage0, caseStage0);
        dispatchCases.put(keyStage1, caseStage1);
        dispatchCases.put(keyStage2, caseStage2);
        dispatchCases.put(keyBridge, caseBridge);
        dispatchCases.put(keyExit, caseExitPivot);
        dispatchCases.put(keyReal, caseReal);
        dispatchCases.put(keyGate, caseGate);
        for (int i = 0; i < junkCaseCount; i++) {
            dispatchCases.put(junkKeys.get(i), junkLabels.get(i));
        }

        pushInt(mn.instructions, keyStage0);
        mn.instructions.add(new VarInsnNode(Opcodes.ISTORE, stateVar));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
        mn.instructions.add(new InsnNode(Opcodes.L2I));
        mn.instructions.add(new VarInsnNode(Opcodes.ISTORE, mixVar));
        pushInt(mn.instructions, random.nextInt());
        mn.instructions.add(new VarInsnNode(Opcodes.ISTORE, sinkVar));
        pushInt(mn.instructions, keyStage0 ^ gateResumeMask);
        mn.instructions.add(new VarInsnNode(Opcodes.ISTORE, gateResumeVar));

        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, mixVar));
        pushInt(mn.instructions, random.nextInt());
        mn.instructions.add(new InsnNode(Opcodes.IXOR));
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, sinkVar));
        mn.instructions.add(new InsnNode(Opcodes.IADD));
        pushInt(mn.instructions, Integer.MAX_VALUE);
        mn.instructions.add(new InsnNode(Opcodes.IAND));
        pushInt(mn.instructions, MAX_DISPATCH_BUDGET - MIN_DISPATCH_BUDGET + 1);
        mn.instructions.add(new InsnNode(Opcodes.IREM));
        pushInt(mn.instructions, MIN_DISPATCH_BUDGET);
        mn.instructions.add(new InsnNode(Opcodes.IADD));
        mn.instructions.add(new VarInsnNode(Opcodes.ISTORE, budgetVar));
        mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, loopHead));

        mn.instructions.add(loopHead);
        LabelNode dispatchReady = new LabelNode();
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, budgetVar));
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, mixVar));
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, sinkVar));
        mn.instructions.add(new InsnNode(Opcodes.IXOR));
        mn.instructions.add(new InsnNode(Opcodes.ICONST_3));
        mn.instructions.add(new InsnNode(Opcodes.IAND));
        mn.instructions.add(new InsnNode(Opcodes.ICONST_1));
        mn.instructions.add(new InsnNode(Opcodes.IADD));
        mn.instructions.add(new InsnNode(Opcodes.ISUB));
        mn.instructions.add(new VarInsnNode(Opcodes.ISTORE, budgetVar));
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, budgetVar));
        mn.instructions.add(new JumpInsnNode(Opcodes.IFGE, dispatchReady));

        int forcedExitMaskA = random.nextInt();
        int forcedExitMaskB = random.nextInt();
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, mixVar));
        pushInt(mn.instructions, forcedExitMaskA);
        mn.instructions.add(new InsnNode(Opcodes.IXOR));
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, mixVar));
        mn.instructions.add(new InsnNode(Opcodes.IXOR));
        pushInt(mn.instructions, forcedExitMaskB);
        mn.instructions.add(new InsnNode(Opcodes.IXOR));
        pushInt(mn.instructions, keyExit ^ forcedExitMaskA ^ forcedExitMaskB);
        mn.instructions.add(new InsnNode(Opcodes.IXOR));
        mn.instructions.add(new VarInsnNode(Opcodes.ISTORE, stateVar));

        pushInt(mn.instructions, MAX_DISPATCH_BUDGET);
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, mixVar));
        mn.instructions.add(new InsnNode(Opcodes.ICONST_3));
        mn.instructions.add(new InsnNode(Opcodes.IAND));
        mn.instructions.add(new InsnNode(Opcodes.IADD));
        mn.instructions.add(new VarInsnNode(Opcodes.ISTORE, budgetVar));

        mn.instructions.add(dispatchReady);
        LabelNode directDispatch = new LabelNode();
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, budgetVar));
        mn.instructions.add(new InsnNode(Opcodes.ICONST_1));
        mn.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPLE, directDispatch));
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, mixVar));
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, sinkVar));
        mn.instructions.add(new InsnNode(Opcodes.IXOR));
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, budgetVar));
        mn.instructions.add(new InsnNode(Opcodes.IXOR));
        pushInt(mn.instructions, gatePredicateMask);
        mn.instructions.add(new InsnNode(Opcodes.IAND));
        mn.instructions.add(new JumpInsnNode(Opcodes.IFNE, directDispatch));
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, stateVar));
        pushInt(mn.instructions, gateResumeMask);
        mn.instructions.add(new InsnNode(Opcodes.IXOR));
        mn.instructions.add(new VarInsnNode(Opcodes.ISTORE, gateResumeVar));
        pushInt(mn.instructions, keyGate);
        mn.instructions.add(new VarInsnNode(Opcodes.ISTORE, stateVar));
        mn.instructions.add(directDispatch);
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, stateVar));

        appendLookupSwitch(mn.instructions, dispatchCases, defaultLabel);

        emitExceptionStage(
                mn.instructions,
                caseStage0,
                tryStarts[0],
                tryEnds[0],
                patterns.get(0),
                stateVar,
                mixVar,
                sinkVar,
                keyStage0,
                keyStage1,
                loopHead,
                snippetPool
        );
        emitExceptionStage(
                mn.instructions,
                caseStage1,
                tryStarts[1],
                tryEnds[1],
                patterns.get(1),
                stateVar,
                mixVar,
                sinkVar,
                keyStage1,
                keyStage2,
                loopHead,
                snippetPool
        );
        emitExceptionStage(
                mn.instructions,
                caseStage2,
                tryStarts[2],
                tryEnds[2],
                patterns.get(2),
                stateVar,
                mixVar,
                sinkVar,
                keyStage2,
                keyBridge,
                loopHead,
                snippetPool
        );

        int realPlacement = random.nextInt(3);
        if (realPlacement == 0) {
            emitRealCase(mn.instructions, caseReal, original, mixVar, sinkVar, snippetPool);
        }

        emitExceptionHandler(
                mn.instructions,
                handlers[0],
                stateVar,
                mixVar,
                sinkVar,
                keyStage0,
                keyStage1,
                junkKeys.isEmpty() ? null : junkKeys.get(0),
                loopHead,
                snippetPool
        );
        emitExceptionHandler(
                mn.instructions,
                handlers[1],
                stateVar,
                mixVar,
                sinkVar,
                keyStage1,
                keyStage2,
                junkKeys.size() < 2 ? null : junkKeys.get(1),
                loopHead,
                snippetPool
        );
        emitExceptionHandler(
                mn.instructions,
                handlers[2],
                stateVar,
                mixVar,
                sinkVar,
                keyStage2,
                keyBridge,
                junkKeys.size() < 3 ? null : junkKeys.get(2),
                loopHead,
                snippetPool
        );

        if (realPlacement == 1) {
            emitRealCase(mn.instructions, caseReal, original, mixVar, sinkVar, snippetPool);
        }

        for (int i = 0; i < junkCaseCount; i++) {
            int fromKey = junkKeys.get(i);
            int targetA = (i % 2 == 0) ? keyStage1 : keyStage2;
            int targetB = (i == junkCaseCount - 1) ? keyBridge : junkKeys.get(i + 1);

            emitJunkCase(
                    mn.instructions,
                    junkLabels.get(i),
                    stateVar,
                    mixVar,
                    sinkVar,
                    fromKey,
                    targetA,
                    targetB,
                    budgetVar,
                    returnType,
                    loopHead,
                    snippetPool
            );
        }

        mn.instructions.add(caseGate);
        emitCasePayload(mn.instructions, mixVar, sinkVar, snippetPool, true);
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, gateResumeVar));
        pushInt(mn.instructions, gateResumeMask);
        mn.instructions.add(new InsnNode(Opcodes.IXOR));
        mn.instructions.add(new VarInsnNode(Opcodes.ISTORE, stateVar));
        mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, loopHead));

        mn.instructions.add(caseBridge);
        emitCasePayload(mn.instructions, mixVar, sinkVar, snippetPool, true);
        emitStateTransition(mn.instructions, stateVar, keyBridge, keyReal);
        mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, loopHead));

        mn.instructions.add(caseExitPivot);
        emitCasePayload(mn.instructions, mixVar, sinkVar, snippetPool, true);
        pushInt(mn.instructions, MIN_DISPATCH_BUDGET + random.nextInt(MAX_DISPATCH_BUDGET - MIN_DISPATCH_BUDGET + 1));
        mn.instructions.add(new VarInsnNode(Opcodes.ISTORE, budgetVar));
        emitStateTransition(mn.instructions, stateVar, keyExit, keyReal);
        mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, loopHead));

        if (realPlacement == 2) {
            emitRealCase(mn.instructions, caseReal, original, mixVar, sinkVar, snippetPool);
        }

        mn.instructions.add(defaultLabel);
        emitCasePayload(mn.instructions, mixVar, sinkVar, snippetPool, true);
        pushInt(mn.instructions, keyStage0);
        mn.instructions.add(new VarInsnNode(Opcodes.ISTORE, stateVar));
        mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, loopHead));
    }

    private void emitExceptionStage(
            InsnList out,
            LabelNode caseLabel,
            LabelNode tryStart,
            LabelNode tryEnd,
            ExceptionPattern pattern,
            int stateVar,
            int mixVar,
            int sinkVar,
            int fromKey,
            int fallbackKey,
            LabelNode loopHead,
            List<AbstractInsnNode> snippetPool
    ) {
        out.add(caseLabel);
        emitCasePayload(out, mixVar, sinkVar, snippetPool, true);
        out.add(tryStart);
        emitExceptionPattern(out, pattern);
        out.add(tryEnd);
        emitStateTransition(out, stateVar, fromKey, fallbackKey);
        out.add(new JumpInsnNode(Opcodes.GOTO, loopHead));
    }

    private void emitExceptionHandler(
            InsnList out,
            LabelNode handler,
            int stateVar,
            int mixVar,
            int sinkVar,
            int fromKey,
            int targetKey,
            Integer alternateKey,
            LabelNode loopHead,
            List<AbstractInsnNode> snippetPool
    ) {
        out.add(handler);
        out.add(new InsnNode(Opcodes.POP));
        emitCasePayload(out, mixVar, sinkVar, snippetPool, true);

        if (alternateKey != null) {
            LabelNode primaryPath = new LabelNode();
            out.add(new VarInsnNode(Opcodes.ILOAD, mixVar));
            out.add(new VarInsnNode(Opcodes.ILOAD, sinkVar));
            out.add(new InsnNode(Opcodes.IXOR));
            pushInt(out, 1 + random.nextInt(7));
            out.add(new InsnNode(Opcodes.IAND));
            out.add(new JumpInsnNode(Opcodes.IFNE, primaryPath));
            emitStateTransition(out, stateVar, fromKey, alternateKey);
            out.add(new JumpInsnNode(Opcodes.GOTO, loopHead));
            out.add(primaryPath);
        }

        emitStateTransition(out, stateVar, fromKey, targetKey);
        out.add(new JumpInsnNode(Opcodes.GOTO, loopHead));
    }

    private void emitJunkCase(
            InsnList out,
            LabelNode caseLabel,
            int stateVar,
            int mixVar,
            int sinkVar,
            int fromKey,
            int targetA,
            int targetB,
            int budgetVar,
            Type returnType,
            LabelNode loopHead,
            List<AbstractInsnNode> snippetPool
    ) {
        out.add(caseLabel);
        emitCasePayload(out, mixVar, sinkVar, snippetPool, true);

        emitFakeReturnNoise(out, returnType, budgetVar, mixVar, sinkVar);

        LabelNode branchB = new LabelNode();
        out.add(new VarInsnNode(Opcodes.ILOAD, mixVar));
        out.add(new VarInsnNode(Opcodes.ILOAD, sinkVar));
        out.add(new InsnNode(Opcodes.IADD));
        pushInt(out, 1 + random.nextInt(5));
        out.add(new InsnNode(Opcodes.IAND));
        out.add(new JumpInsnNode(Opcodes.IFNE, branchB));
        emitStateTransition(out, stateVar, fromKey, targetA);
        out.add(new JumpInsnNode(Opcodes.GOTO, loopHead));

        out.add(branchB);
        emitStateTransition(out, stateVar, fromKey, targetB);
        out.add(new JumpInsnNode(Opcodes.GOTO, loopHead));
    }

    private void emitRealCase(
            InsnList out,
            LabelNode caseReal,
            InsnList original,
            int mixVar,
            int sinkVar,
            List<AbstractInsnNode> snippetPool
    ) {
        out.add(caseReal);
        emitCasePayload(out, mixVar, sinkVar, snippetPool, false);
        out.add(original);
        out.add(new InsnNode(Opcodes.ACONST_NULL));
        out.add(new InsnNode(Opcodes.ATHROW));
    }

    private void emitFakeReturnNoise(InsnList out, Type returnType, int budgetVar, int mixVar, int sinkVar) {
        LabelNode fakeReturn = new LabelNode();
        LabelNode done = new LabelNode();

        out.add(new VarInsnNode(Opcodes.ILOAD, budgetVar));
        out.add(new VarInsnNode(Opcodes.ILOAD, mixVar));
        out.add(new InsnNode(Opcodes.IXOR));
        out.add(new VarInsnNode(Opcodes.ILOAD, mixVar));
        out.add(new InsnNode(Opcodes.IXOR));
        out.add(new VarInsnNode(Opcodes.ILOAD, sinkVar));
        out.add(new InsnNode(Opcodes.IAND));

        out.add(new VarInsnNode(Opcodes.ILOAD, budgetVar));
        out.add(new VarInsnNode(Opcodes.ILOAD, sinkVar));
        out.add(new InsnNode(Opcodes.IAND));
        out.add(new JumpInsnNode(Opcodes.IF_ICMPNE, fakeReturn));
        out.add(new JumpInsnNode(Opcodes.GOTO, done));

        out.add(fakeReturn);

        switch (returnType.getSort()) {
            case Type.VOID:
                out.add(new InsnNode(Opcodes.RETURN));
                break;
            case Type.BOOLEAN:
            case Type.BYTE:
            case Type.CHAR:
            case Type.SHORT:
            case Type.INT:
                out.add(new InsnNode(Opcodes.ICONST_0));
                out.add(new InsnNode(Opcodes.IRETURN));
                break;
            case Type.FLOAT:
                out.add(new InsnNode(Opcodes.FCONST_0));
                out.add(new InsnNode(Opcodes.FRETURN));
                break;
            case Type.LONG:
                out.add(new InsnNode(Opcodes.LCONST_0));
                out.add(new InsnNode(Opcodes.LRETURN));
                break;
            case Type.DOUBLE:
                out.add(new InsnNode(Opcodes.DCONST_0));
                out.add(new InsnNode(Opcodes.DRETURN));
                break;
            default:
                out.add(new InsnNode(Opcodes.ACONST_NULL));
                out.add(new InsnNode(Opcodes.ARETURN));
                break;
        }

        out.add(done);
    }

    private void emitCasePayload(InsnList out, int mixVar, int sinkVar, List<AbstractInsnNode> snippetPool, boolean allowSnippet) {
        if (allowSnippet && !snippetPool.isEmpty()) {
            int snippetCount = 1 + random.nextInt(Math.min(2, snippetPool.size()));
            emitSnippetPayload(out, snippetPool, snippetCount);
        }

        switch (random.nextInt(4)) {
            case 0:
                out.add(new VarInsnNode(Opcodes.ILOAD, mixVar));
                pushInt(out, random.nextInt());
                out.add(new InsnNode(Opcodes.IXOR));
                out.add(new VarInsnNode(Opcodes.ISTORE, mixVar));

                out.add(new VarInsnNode(Opcodes.ILOAD, sinkVar));
                out.add(new VarInsnNode(Opcodes.ILOAD, mixVar));
                out.add(new InsnNode(Opcodes.IADD));
                out.add(new VarInsnNode(Opcodes.ISTORE, sinkVar));
                break;
            case 1:
                out.add(new VarInsnNode(Opcodes.ILOAD, sinkVar));
                pushInt(out, 3 + random.nextInt(4) * 2);
                out.add(new InsnNode(Opcodes.IMUL));
                out.add(new VarInsnNode(Opcodes.ILOAD, mixVar));
                out.add(new InsnNode(Opcodes.IXOR));
                out.add(new VarInsnNode(Opcodes.ISTORE, sinkVar));
                out.add(new IincInsnNode(mixVar, randomSignedSmall()));
                break;
            case 2:
                out.add(new VarInsnNode(Opcodes.ILOAD, mixVar));
                out.add(new VarInsnNode(Opcodes.ILOAD, sinkVar));
                out.add(new InsnNode(Opcodes.IADD));
                pushInt(out, random.nextInt());
                out.add(new InsnNode(Opcodes.IXOR));
                out.add(new VarInsnNode(Opcodes.ISTORE, mixVar));

                out.add(new VarInsnNode(Opcodes.ILOAD, sinkVar));
                pushInt(out, random.nextInt());
                out.add(new InsnNode(Opcodes.IADD));
                out.add(new VarInsnNode(Opcodes.ILOAD, mixVar));
                out.add(new InsnNode(Opcodes.IXOR));
                out.add(new VarInsnNode(Opcodes.ISTORE, sinkVar));
                break;
            default:
                out.add(new VarInsnNode(Opcodes.ILOAD, mixVar));
                pushInt(out, 1 + random.nextInt(30));
                out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "rotateLeft", "(II)I", false));
                out.add(new VarInsnNode(Opcodes.ISTORE, mixVar));

                out.add(new VarInsnNode(Opcodes.ILOAD, sinkVar));
                out.add(new VarInsnNode(Opcodes.ILOAD, mixVar));
                out.add(new InsnNode(Opcodes.IXOR));
                out.add(new VarInsnNode(Opcodes.ISTORE, sinkVar));
                break;
        }
    }

    private void emitSnippetPayload(InsnList out, List<AbstractInsnNode> snippetPool, int count) {
        for (int i = 0; i < count; i++) {
            AbstractInsnNode source = snippetPool.get(random.nextInt(snippetPool.size()));
            AbstractInsnNode cloned = source.clone(new HashMap<>());
            if (cloned == null) continue;
            out.add(cloned);

            if (stackValueSize(source) == 2) {
                out.add(new InsnNode(Opcodes.POP2));
            } else {
                out.add(new InsnNode(Opcodes.POP));
            }
        }
    }

    private int stackValueSize(AbstractInsnNode insn) {
        if (insn instanceof VarInsnNode varInsn) {
            int opcode = varInsn.getOpcode();
            if (opcode == Opcodes.LLOAD || opcode == Opcodes.DLOAD) {
                return 2;
            }
            return 1;
        }

        if (insn instanceof LdcInsnNode ldcInsn) {
            return (ldcInsn.cst instanceof Long || ldcInsn.cst instanceof Double) ? 2 : 1;
        }

        int opcode = insn.getOpcode();
        if (opcode == Opcodes.LCONST_0 || opcode == Opcodes.LCONST_1 || opcode == Opcodes.DCONST_0 || opcode == Opcodes.DCONST_1) {
            return 2;
        }
        return 1;
    }

    private List<AbstractInsnNode> collectSnippetPool(InsnList original, Set<Integer> stableLoadSlots) {
        List<AbstractInsnNode> snippets = new ArrayList<>();
        for (AbstractInsnNode insn : original) {
            if (isSafeSnippetInsn(insn, stableLoadSlots)) {
                snippets.add(insn);
            }
            if (snippets.size() >= 48) {
                break;
            }
        }
        return snippets;
    }

    private boolean isSafeSnippetInsn(AbstractInsnNode insn, Set<Integer> stableLoadSlots) {
        if (insn instanceof VarInsnNode varInsn) {
            int opcode = varInsn.getOpcode();
            boolean isLoad = opcode == Opcodes.ILOAD || opcode == Opcodes.LLOAD || opcode == Opcodes.FLOAD || opcode == Opcodes.DLOAD || opcode == Opcodes.ALOAD;
            return isLoad && stableLoadSlots.contains(varInsn.var);
        }

        if (insn instanceof IntInsnNode intInsn) {
            return intInsn.getOpcode() == Opcodes.BIPUSH || intInsn.getOpcode() == Opcodes.SIPUSH;
        }

        if (insn instanceof LdcInsnNode ldcInsn) {
            Object cst = ldcInsn.cst;
            return cst instanceof Integer || cst instanceof Float || cst instanceof Long || cst instanceof Double || cst instanceof String;
        }

        if (insn instanceof InsnNode) {
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.ACONST_NULL) return true;
            return opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.DCONST_1;
        }

        return false;
    }

    private Set<Integer> collectStableLoadSlots(MethodNode mn) {
        Set<Integer> stableLoadSlots = new HashSet<>();
        int localIndex = 0;

        if (!AsmUtils.isStatic(mn)) {
            stableLoadSlots.add(0);
            localIndex = 1;
        }

        for (Type argType : Type.getArgumentTypes(mn.desc)) {
            stableLoadSlots.add(localIndex);
            localIndex += argType.getSize();
        }

        return stableLoadSlots;
    }

    private void emitExceptionPattern(InsnList out, ExceptionPattern pattern) {
        int variant = random.nextInt(3);

        switch (pattern.id) {
            case 0:
                if (variant == 0) {
                    out.add(new InsnNode(Opcodes.ICONST_1));
                    out.add(new InsnNode(Opcodes.ICONST_0));
                    out.add(new InsnNode(Opcodes.IDIV));
                    out.add(new InsnNode(Opcodes.POP));
                } else if (variant == 1) {
                    pushInt(out, randomSignedSmall());
                    out.add(new InsnNode(Opcodes.ICONST_0));
                    out.add(new InsnNode(Opcodes.IREM));
                    out.add(new InsnNode(Opcodes.POP));
                } else {
                    pushInt(out, random.nextInt());
                    out.add(new InsnNode(Opcodes.ICONST_0));
                    out.add(new InsnNode(Opcodes.IDIV));
                    out.add(new InsnNode(Opcodes.POP));
                }
                break;
            case 1:
                if (variant == 0) {
                    out.add(new InsnNode(Opcodes.ACONST_NULL));
                    out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false));
                    out.add(new InsnNode(Opcodes.POP));
                } else if (variant == 1) {
                    out.add(new InsnNode(Opcodes.ACONST_NULL));
                    out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false));
                    out.add(new InsnNode(Opcodes.POP));
                } else {
                    out.add(new InsnNode(Opcodes.ACONST_NULL));
                    out.add(new InsnNode(Opcodes.ATHROW));
                }
                break;
            case 2:
                if (variant == 0) {
                    out.add(new TypeInsnNode(Opcodes.NEW, "java/lang/Object"));
                    out.add(new InsnNode(Opcodes.DUP));
                    out.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
                    out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
                    out.add(new InsnNode(Opcodes.POP));
                } else if (variant == 1) {
                    out.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
                    out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
                    out.add(new InsnNode(Opcodes.POP));
                } else {
                    out.add(new InsnNode(Opcodes.ICONST_1));
                    out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
                    out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
                    out.add(new InsnNode(Opcodes.POP));
                }
                break;
            case 3:
                if (variant == 0) {
                    out.add(new InsnNode(Opcodes.ICONST_0));
                    out.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
                    out.add(new InsnNode(Opcodes.ICONST_1));
                    out.add(new InsnNode(Opcodes.IALOAD));
                    out.add(new InsnNode(Opcodes.POP));
                } else if (variant == 1) {
                    out.add(new InsnNode(Opcodes.ICONST_1));
                    out.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
                    out.add(new InsnNode(Opcodes.ICONST_2));
                    out.add(new InsnNode(Opcodes.IALOAD));
                    out.add(new InsnNode(Opcodes.POP));
                } else {
                    out.add(new InsnNode(Opcodes.ICONST_2));
                    out.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
                    out.add(new InsnNode(Opcodes.ICONST_3));
                    out.add(new InsnNode(Opcodes.IALOAD));
                    out.add(new InsnNode(Opcodes.POP));
                }
                break;
            case 4:
                if (variant == 0) {
                    out.add(new InsnNode(Opcodes.ICONST_M1));
                    out.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
                    out.add(new InsnNode(Opcodes.POP));
                } else if (variant == 1) {
                    out.add(new InsnNode(Opcodes.ICONST_M1));
                    out.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
                    out.add(new InsnNode(Opcodes.POP));
                } else {
                    out.add(new InsnNode(Opcodes.ICONST_M1));
                    out.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
                    out.add(new InsnNode(Opcodes.POP));
                }
                break;
            case 5:
                if (variant == 0) {
                    out.add(new TypeInsnNode(Opcodes.NEW, "java/lang/String"));
                    out.add(new InsnNode(Opcodes.DUP));
                    out.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "()V", false));
                    out.add(new InsnNode(Opcodes.ICONST_0));
                    out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false));
                    out.add(new InsnNode(Opcodes.POP));
                } else if (variant == 1) {
                    out.add(new TypeInsnNode(Opcodes.NEW, "java/lang/String"));
                    out.add(new InsnNode(Opcodes.DUP));
                    out.add(new InsnNode(Opcodes.ICONST_1));
                    out.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_CHAR));
                    out.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([C)V", false));
                    out.add(new InsnNode(Opcodes.ICONST_2));
                    out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false));
                    out.add(new InsnNode(Opcodes.POP));
                } else {
                    out.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
                    out.add(new InsnNode(Opcodes.DUP));
                    out.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
                    out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
                    out.add(new InsnNode(Opcodes.ICONST_1));
                    out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false));
                    out.add(new InsnNode(Opcodes.POP));
                }
                break;
            case 6:
                if (variant == 0) {
                    out.add(new TypeInsnNode(Opcodes.NEW, "java/lang/String"));
                    out.add(new InsnNode(Opcodes.DUP));
                    out.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "()V", false));
                    out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "parseInt", "(Ljava/lang/String;)I", false));
                    out.add(new InsnNode(Opcodes.POP));
                } else if (variant == 1) {
                    out.add(new TypeInsnNode(Opcodes.NEW, "java/lang/Object"));
                    out.add(new InsnNode(Opcodes.DUP));
                    out.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
                    out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false));
                    out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "parseInt", "(Ljava/lang/String;)I", false));
                    out.add(new InsnNode(Opcodes.POP));
                } else {
                    out.add(new TypeInsnNode(Opcodes.NEW, "java/lang/String"));
                    out.add(new InsnNode(Opcodes.DUP));
                    out.add(new InsnNode(Opcodes.ICONST_1));
                    out.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_CHAR));
                    out.add(new InsnNode(Opcodes.DUP));
                    out.add(new InsnNode(Opcodes.ICONST_0));
                    out.add(new InsnNode(Opcodes.ICONST_0));
                    out.add(new InsnNode(Opcodes.CASTORE));
                    out.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([C)V", false));
                    out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "parseInt", "(Ljava/lang/String;)I", false));
                    out.add(new InsnNode(Opcodes.POP));
                }
                break;
            default:
                if (variant == 0) {
                    out.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
                    out.add(new InsnNode(Opcodes.DUP));
                    out.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false));
                    out.add(new InsnNode(Opcodes.ATHROW));
                } else if (variant == 1) {
                    out.add(new InsnNode(Opcodes.ICONST_0));
                    out.add(new InsnNode(Opcodes.POP));
                    out.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
                    out.add(new InsnNode(Opcodes.DUP));
                    out.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false));
                    out.add(new InsnNode(Opcodes.ATHROW));
                } else {
                    out.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
                    out.add(new InsnNode(Opcodes.DUP));
                    out.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false));
                    out.add(new InsnNode(Opcodes.DUP));
                    out.add(new InsnNode(Opcodes.POP));
                    out.add(new InsnNode(Opcodes.ATHROW));
                }
                break;
        }
    }

    private List<ExceptionPattern> pickExceptionPatterns(int count) {
        List<ExceptionPattern> available = new ArrayList<>(Arrays.asList(
                new ExceptionPattern(0, "java/lang/ArithmeticException"),
                new ExceptionPattern(1, "java/lang/NullPointerException"),
                new ExceptionPattern(2, "java/lang/ClassCastException"),
                new ExceptionPattern(3, "java/lang/ArrayIndexOutOfBoundsException"),
                new ExceptionPattern(4, "java/lang/NegativeArraySizeException"),
                new ExceptionPattern(5, "java/lang/StringIndexOutOfBoundsException"),
                new ExceptionPattern(6, "java/lang/NumberFormatException"),
                new ExceptionPattern(7, "java/lang/IllegalStateException")
        ));
        Collections.shuffle(available, random);
        return new ArrayList<>(available.subList(0, count));
    }

    private void emitStateTransition(InsnList out, int stateVar, int fromKey, int toKey) {
        switch (random.nextInt(5)) {
            case 0:
                pushInt(out, toKey);
                out.add(new VarInsnNode(Opcodes.ISTORE, stateVar));
                break;
            case 1:
                out.add(new VarInsnNode(Opcodes.ILOAD, stateVar));
                pushInt(out, toKey - fromKey);
                out.add(new InsnNode(Opcodes.IADD));
                out.add(new VarInsnNode(Opcodes.ISTORE, stateVar));
                break;
            case 2:
                out.add(new VarInsnNode(Opcodes.ILOAD, stateVar));
                pushInt(out, fromKey ^ toKey);
                out.add(new InsnNode(Opcodes.IXOR));
                out.add(new VarInsnNode(Opcodes.ISTORE, stateVar));
                break;
            case 3:
                int mul = 3 + random.nextInt(4) * 2;
                int add = toKey - fromKey * mul;
                out.add(new VarInsnNode(Opcodes.ILOAD, stateVar));
                pushInt(out, mul);
                out.add(new InsnNode(Opcodes.IMUL));
                pushInt(out, add);
                out.add(new InsnNode(Opcodes.IADD));
                out.add(new VarInsnNode(Opcodes.ISTORE, stateVar));
                break;
            default:
                int shift = 1 + random.nextInt(30);
                int mask = Integer.rotateLeft(fromKey, shift) ^ toKey;
                out.add(new VarInsnNode(Opcodes.ILOAD, stateVar));
                pushInt(out, shift);
                out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "rotateLeft", "(II)I", false));
                pushInt(out, mask);
                out.add(new InsnNode(Opcodes.IXOR));
                out.add(new VarInsnNode(Opcodes.ISTORE, stateVar));
                break;
        }
    }

    private void appendLookupSwitch(InsnList out, Map<Integer, LabelNode> dispatchCases, LabelNode defaultLabel) {
        List<Map.Entry<Integer, LabelNode>> entries = new ArrayList<>(dispatchCases.entrySet());
        entries.sort(Map.Entry.comparingByKey());

        int[] keys = new int[entries.size()];
        LabelNode[] labels = new LabelNode[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            keys[i] = entries.get(i).getKey();
            labels[i] = entries.get(i).getValue();
        }

        out.add(new LookupSwitchInsnNode(defaultLabel, keys, labels));
    }

    private int nextKey(Set<Integer> used) {
        int key;
        do {
            key = random.nextInt();
        } while (!used.add(key));
        return key;
    }

    private int randomSignedSmall() {
        int value;
        do {
            value = random.nextInt(9) - 4;
        } while (value == 0);
        return value;
    }

    private void pushInt(InsnList out, int value) {
        if (value == -1) {
            out.add(new InsnNode(Opcodes.ICONST_M1));
        } else if (value >= 0 && value <= 5) {
            out.add(new InsnNode(Opcodes.ICONST_0 + value));
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            out.add(new IntInsnNode(Opcodes.BIPUSH, value));
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            out.add(new IntInsnNode(Opcodes.SIPUSH, value));
        } else {
            out.add(new LdcInsnNode(value));
        }
    }

    private static class ExceptionPattern {
        private final int id;
        private final String exceptionInternalName;

        private ExceptionPattern(int id, String exceptionInternalName) {
            this.id = id;
            this.exceptionInternalName = exceptionInternalName;
        }
    }
}
