package dev.eatgrapes.chlorine.transformers;

import dev.eatgrapes.chlorine.transformers.impl.ClassNameTransformer;
import dev.eatgrapes.chlorine.transformers.impl.FieldNameTransformer;
import dev.eatgrapes.chlorine.transformers.impl.FlowTransformer;
import dev.eatgrapes.chlorine.transformers.impl.MethodNameTransformer;
import dev.eatgrapes.chlorine.transformers.impl.NumberObfuscationTransformer;
import dev.eatgrapes.chlorine.transformers.impl.ReflectionTransformer;
import dev.eatgrapes.chlorine.transformers.impl.StringEncryptionTransformer;
import java.util.ArrayList;
import java.util.List;

public class Transformers {
    private final List<Transformer> transformers = new ArrayList<>();

    public Transformers() {
        register(new ClassNameTransformer());
        register(new MethodNameTransformer());
        register(new FieldNameTransformer());
        register(new NumberObfuscationTransformer());
        register(new StringEncryptionTransformer());
        register(new FlowTransformer());
        register(new ReflectionTransformer());
    }

    public void register(Transformer transformer) {
        this.transformers.add(transformer);
    }

    public List<Transformer> getTransformers() {
        return transformers;
    }
    
    public Transformer get(String name) {
        for(Transformer t : transformers) {
            if(t.getName().equalsIgnoreCase(name)) return t;
        }
        return null;
    }
}
