package dev.eatgrapes.chlorine;

import dev.eatgrapes.chlorine.transformers.Transformer;
import dev.eatgrapes.chlorine.transformers.Transformers;
import org.apache.commons.cli.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.*;
import java.util.*;
import java.util.jar.*;

public class Chlorine {
    public static final String PROJECT_NAME = "Chlorine";
    public static final String VERSION = "1.0.0";

    public static void main(String[] args) {
        Options options = new Options();
        options.addOption("i", "input", true, "Input JAR/Class file");
        options.addOption("o", "output", true, "Output JAR file");
        options.addOption("t", "transformers", true, "Transformers to run (comma separated). Default: all");
        options.addOption("k", "keep", true, "Classes/Packages to keep (comma separated)");
        options.addOption("h", "help", false, "Show help");

        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine cmd = parser.parse(options, args);
            if (cmd.hasOption("h") || !cmd.hasOption("i")) {
                new HelpFormatter().printHelp("chlorine", options);
                return;
            }

            File inputFile = new File(cmd.getOptionValue("i"));
            File outputFile = new File(cmd.getOptionValue("o", inputFile.getName().replace(".jar", "-obf.jar")));
            
            Set<String> keeps = new HashSet<>();
            if (cmd.hasOption("k")) {
                Collections.addAll(keeps, cmd.getOptionValue("k").split(","));
            }
            
            Transformers registry = new Transformers();
            List<Transformer> toRun = new ArrayList<>();
            
            if (cmd.hasOption("t")) {
                String[] names = cmd.getOptionValue("t").split(",");
                for (String name : names) {
                    Transformer t = registry.get(name.trim());
                    if (t != null) toRun.add(t);
                    else System.err.println("Warning: Transformer " + name + " not found.");
                }
            } else {
                toRun.addAll(registry.getTransformers());
            }

            process(inputFile, outputFile, toRun, keeps);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void process(File input, File output, List<Transformer> transformers, Set<String> keeps) throws IOException {
        Map<String, ClassNode> classes = new HashMap<>();
        Map<String, byte[]> resources = new HashMap<>();
        Map<String, String> manifestAttr = new HashMap<>();
        
        try (JarFile jar = new JarFile(input)) {
            Manifest mf = jar.getManifest();
            if (mf != null) {
                Attributes mainAttrs = mf.getMainAttributes();
                for (Object key : mainAttrs.keySet()) {
                    manifestAttr.put(key.toString(), mainAttrs.getValue(key.toString()));
                }
            }
            
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                try (InputStream is = jar.getInputStream(entry)) {
                    byte[] data = is.readAllBytes();
                    if (entry.getName().endsWith(".class")) {
                        ClassReader cr = new ClassReader(data);
                        ClassNode cn = new ClassNode();
                        cr.accept(cn, 0);
                        classes.put(cn.name, cn);
                    } else if (!entry.isDirectory()) {
                        resources.put(entry.getName(), data);
                    }
                }
            }
        }

        System.out.println("Loaded " + classes.size() + " classes.");
        for (Transformer t : transformers) {
            System.out.println("Running " + t.getName() + "...");
            t.transform(classes, manifestAttr, keeps);
        }

        Manifest finalManifest = new Manifest();
        finalManifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        for (Map.Entry<String, String> e : manifestAttr.entrySet()) {
             finalManifest.getMainAttributes().put(new Attributes.Name(e.getKey()), e.getValue());
        }

        try (FileOutputStream fos = new FileOutputStream(output)) {
             fos.write((PROJECT_NAME + " " + VERSION + " by dev.eatgrapes\n").getBytes());
             
             try (JarOutputStream jos = new JarOutputStream(fos, finalManifest)) {
                 for (ClassNode cn : classes.values()) {
                     ClassWriter cw = new NonLoadingClassWriter(ClassWriter.COMPUTE_FRAMES, classes);
                     cn.accept(cw);
                     
                     JarEntry entry = new JarEntry(cn.name + ".class");
                     jos.putNextEntry(entry);
                     jos.write(cw.toByteArray());
                     jos.closeEntry();
                 }
                 
                 for (Map.Entry<String, byte[]> res : resources.entrySet()) {
                     if (res.getKey().equalsIgnoreCase("META-INF/MANIFEST.MF")) continue; 
                     JarEntry entry = new JarEntry(res.getKey());
                     jos.putNextEntry(entry);
                     jos.write(res.getValue());
                     jos.closeEntry();
                 }
                 
                 jos.setComment(PROJECT_NAME + " " + VERSION);
             }
        }
        
        System.out.println("Obfuscation complete: " + output.getPath());
    }

    private static class NonLoadingClassWriter extends ClassWriter {
        private final Map<String, ClassNode> classMap;

        public NonLoadingClassWriter(int flags, Map<String, ClassNode> classMap) {
            super(flags);
            this.classMap = classMap;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            if (type1.equals(type2)) return type1;
            if (type1.equals("java/lang/Object")) return type1;
            if (type2.equals("java/lang/Object")) return type2;

            try {
                if (isAssignableFrom(type1, type2)) return type1;
                if (isAssignableFrom(type2, type1)) return type2;
                if (isInterface(type1) || isInterface(type2)) return "java/lang/Object";

                String t1 = type1;
                do {
                    t1 = getSuperClass(t1);
                    if (isAssignableFrom(t1, type2)) return t1;
                } while (!t1.equals("java/lang/Object"));
                
                return "java/lang/Object";
            } catch (Exception e) {
                return "java/lang/Object";
            }
        }

        private boolean isAssignableFrom(String type1, String type2) {
            if (type1.equals("java/lang/Object")) return true;
            if (type1.equals(type2)) return true;
            
            String current = type2;
            while (!current.equals("java/lang/Object")) {
                String superType = getSuperClass(current);
                if (superType.equals(type1)) return true;
                
                List<String> interfaces = getInterfaces(current);
                for (String itf : interfaces) {
                    if (itf.equals(type1)) return true;
                    if (isAssignableFrom(type1, itf)) return true;
                }
                
                current = superType;
            }
            return false;
        }

        private String getSuperClass(String type) {
            if (type.equals("java/lang/Object")) return null;
            if (classMap.containsKey(type)) {
                return classMap.get(type).superName;
            }
            try {
                Class<?> c = Class.forName(type.replace('/', '.'), false, ClassLoader.getSystemClassLoader());
                if (c.getSuperclass() == null) return "java/lang/Object";
                return c.getSuperclass().getName().replace('.', '/');
            } catch (Exception e) {
                return "java/lang/Object";
            }
        }
        
        private boolean isInterface(String type) {
            if (classMap.containsKey(type)) {
                return (classMap.get(type).access & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0;
            }
            try {
                return Class.forName(type.replace('/', '.'), false, ClassLoader.getSystemClassLoader()).isInterface();
            } catch (Exception e) {
                return false;
            }
        }
        
        private List<String> getInterfaces(String type) {
            if (classMap.containsKey(type)) {
                return classMap.get(type).interfaces;
            }
            try {
                Class<?> c = Class.forName(type.replace('/', '.'), false, ClassLoader.getSystemClassLoader());
                List<String> list = new ArrayList<>();
                for (Class<?> i : c.getInterfaces()) {
                    list.add(i.getName().replace('.', '/'));
                }
                return list;
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }
    }
}
