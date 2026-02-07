package dev.eatgrapes.chlorine.utils;

import java.util.concurrent.atomic.AtomicInteger;

public class NameGenerator {
    private static final AtomicInteger globalIndex = new AtomicInteger(0);
    private static final char[] CHARS = "abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final char[] INVISIBLE = {'\u200b', '\u200c', '\u200d', '\u2060', '\u180e'};

    private final String prefix;
    private final AtomicInteger localIndex;
    private final boolean useGlobal;

    public NameGenerator() {
        this("", true);
    }

    public NameGenerator(String prefix) {
        this(prefix, true);
    }

    public NameGenerator(String prefix, boolean useGlobal) {
        this.prefix = prefix;
        this.useGlobal = useGlobal;
        this.localIndex = new AtomicInteger(0);
    }

    public static NameGenerator withPrefix(String prefix) {
        return new NameGenerator(prefix, true);
    }

    public static NameGenerator local() {
        return new NameGenerator("", false);
    }

    public static NameGenerator local(String prefix) {
        return new NameGenerator(prefix, false);
    }

    public String next() {
        int idx = useGlobal ? globalIndex.getAndIncrement() : localIndex.getAndIncrement();
        return prefix + indexToName(idx);
    }

    public String nextClass() {
        return next();
    }

    public String nextMethod() {
        return next();
    }

    public String nextField() {
        return next();
    }

    public String nextInvisible() {
        int idx = useGlobal ? globalIndex.getAndIncrement() : localIndex.getAndIncrement();
        return indexToInvisible(idx);
    }

    private String indexToName(int i) {
        StringBuilder sb = new StringBuilder();
        do {
            sb.append(CHARS[i % CHARS.length]);
            i = i / CHARS.length - 1;
        } while (i >= 0);
        return sb.reverse().toString();
    }

    private String indexToInvisible(int i) {
        StringBuilder sb = new StringBuilder();
        do {
            sb.append(INVISIBLE[i % INVISIBLE.length]);
            i = i / INVISIBLE.length - 1;
        } while (i >= 0);
        return sb.toString();
    }

    public static void reset() {
        globalIndex.set(0);
    }

    public static int currentIndex() {
        return globalIndex.get();
    }
}
