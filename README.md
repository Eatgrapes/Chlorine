# Chlorine

A Java bytecode obfuscator built with ASM.

## Features
- **Renaming**: Renames classes, methods, and fields.
- **Flow Obfuscation**: Implements control flow flattening.
- **Number Obfuscation**: Obfuscates integer and long constants using recursive arithmetic and bitwise operations.
- **Reflection Obfuscation**Use Java Apis to obfuscate.

## TODO
- *String Obfuscation*
- etc.

## Usage
Build the project using Maven and run the resulting JAR:
```bash
mvn package
```
```bash
java -jar chlorine-1.0.0.jar -i <input.jar> -o <output.jar>
```

### Command Line Arguments:
- `-i, --input <path>`: Path to the input JAR or Class file (Required).
- `-o, --output <path>`: Path for the obfuscated output JAR.
- `-t, --transformers <list>`: Comma-separated list of transformers to execute (e.g., `ClassRename,FlowObfuscation`). Defaults to all.
- `-k, --keep <list>`: Comma-separated list of classes or packages to exclude from transformation.
- `-h, --help`: Show help

### Available Transformers:
- `ClassRename`
- `MethodRename`
- `FieldRename`
- `FlowObfuscation`
- `NumberObfuscation`
- `Reflection`
