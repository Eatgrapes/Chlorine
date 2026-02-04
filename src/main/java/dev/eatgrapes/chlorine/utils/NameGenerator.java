package dev.eatgrapes.chlorine.utils;

public class NameGenerator {
    private int index = 0;
    private final char[] chars = "abcdefghijklmnopqrstuvwxyz".toCharArray();

    public String next() {
        return toString(index++);
    }

    private String toString(int i) {
        StringBuilder sb = new StringBuilder();
        do {
            sb.append(chars[i % chars.length]);
            i = i / chars.length - 1;
        } while (i >= 0);
        return sb.reverse().toString();
    }
}
