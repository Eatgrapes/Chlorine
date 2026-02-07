package dev.eatgrapes.chlorine.utils;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

public class AsmUtils {

    public static boolean isAbstract(MethodNode mn) {
        return (mn.access & Opcodes.ACC_ABSTRACT) != 0;
    }

    public static boolean isNative(MethodNode mn) {
        return (mn.access & Opcodes.ACC_NATIVE) != 0;
    }

    public static boolean isInterface(ClassNode cn) {
        return (cn.access & Opcodes.ACC_INTERFACE) != 0;
    }

    public static boolean isModuleInfo(ClassNode cn) {
        return cn.name.equals("module-info") || cn.name.endsWith("/module-info") ||
               (cn.access & Opcodes.ACC_MODULE) != 0;
    }

    public static boolean isStatic(MethodNode mn) {
        return (mn.access & Opcodes.ACC_STATIC) != 0;
    }

    public static boolean isStatic(FieldNode fn) {
        return (fn.access & Opcodes.ACC_STATIC) != 0;
    }

    public static boolean isConstructor(MethodNode mn) {
        return mn.name.equals("<init>") || mn.name.equals("<clinit>");
    }

    public static boolean isPrimitiveDesc(String desc) {
        return desc.equals("I") || desc.equals("J") || desc.equals("F") || desc.equals("D") ||
               desc.equals("Z") || desc.equals("B") || desc.equals("C") || desc.equals("S") || desc.equals("V");
    }

    public static String[] parseParamTypes(String desc) {
        List<String> params = new ArrayList<>();
        int i = 1;
        while (desc.charAt(i) != ')') {
            int start = i;
            while (desc.charAt(i) == '[') i++;
            if (desc.charAt(i) == 'L') {
                int end = desc.indexOf(';', i);
                params.add(desc.substring(start, end + 1));
                i = end + 1;
            } else {
                params.add(desc.substring(start, i + 1));
                i++;
            }
        }
        return params.toArray(new String[0]);
    }

    public static String parseReturnType(String desc) {
        int i = desc.indexOf(')') + 1;
        return desc.substring(i);
    }

    public static int getParamCount(String desc) {
        return parseParamTypes(desc).length;
    }

    public static int getParamSlots(String desc) {
        int slots = 0;
        for (String param : parseParamTypes(desc)) {
            if (param.equals("J") || param.equals("D")) {
                slots += 2;
            } else {
                slots += 1;
            }
        }
        return slots;
    }

    public static List<AbstractInsnNode> findInsns(MethodNode mn, int opcode) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn.getOpcode() == opcode) {
                result.add(insn);
            }
        }
        return result;
    }

    public static List<LabelNode> collectLabels(MethodNode mn) {
        List<LabelNode> labels = new ArrayList<>();
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof LabelNode) {
                labels.add((LabelNode) insn);
            }
        }
        return labels;
    }

    public static Map<LabelNode, LabelNode> createLabelMap(InsnList instructions) {
        Map<LabelNode, LabelNode> map = new HashMap<>();
        for (AbstractInsnNode insn : instructions) {
            if (insn instanceof LabelNode) {
                map.put((LabelNode) insn, new LabelNode());
            }
        }
        return map;
    }

    public static InsnList cloneInsnList(InsnList original) {
        InsnList result = new InsnList();
        Map<LabelNode, LabelNode> labelMap = createLabelMap(original);
        for (AbstractInsnNode insn : original) {
            AbstractInsnNode cloned = insn.clone(labelMap);
            if (cloned != null) {
                result.add(cloned);
            }
        }
        return result;
    }
}
