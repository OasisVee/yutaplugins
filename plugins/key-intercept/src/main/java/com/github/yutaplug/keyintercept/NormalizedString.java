package com.github.yutaplug.keyintercept;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NormalizedString {
    public interface MatchCallback {
        String replace(String match);
    }

    private String str;
    private String nfkdStr;
    private final List<int[]> indices = new ArrayList<>();

    public NormalizedString(String str) {
        this.str = str != null ? str : "";
        rebuild();
    }

    public void rebuild() {
        indices.clear();
        StringBuilder nfkdBuilder = new StringBuilder();
        int postOffset = 0;

        for (int i = 0; i < str.length(); ) {
            int codePoint = str.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            String origChar = str.substring(i, i + charCount);

            String normalized = Normalizer.normalize(origChar, Normalizer.Form.NFKD);
            int postStart = postOffset;
            int postEnd = postStart + normalized.length();
            postOffset = postEnd;

            indices.add(new int[]{i, i + charCount, postStart, postEnd});
            nfkdBuilder.append(normalized);
            i += charCount;
        }

        this.nfkdStr = nfkdBuilder.toString();
    }

    public int[] convert(int postStart, int postEnd) {
        int preStart = -1;
        int preEnd = -1;
        for (int[] idx : indices) {
            int pStart = idx[2];
            int pEnd = idx[3];
            if (preStart == -1 && pStart <= postStart && pEnd > postStart) {
                preStart = idx[0];
            }
            if (pStart < postEnd && pEnd >= postEnd) {
                preEnd = idx[1];
            }
        }
        if (preStart == -1) preStart = 0;
        if (preEnd == -1) preEnd = str.length();
        return new int[]{preStart, preEnd};
    }

    public String replace(Pattern pattern, MatchCallback fn) {
        Matcher matcher = pattern.matcher(nfkdStr);
        while (matcher.find()) {
            int postStart = matcher.start();
            int postEnd = matcher.end();
            int[] pre = convert(postStart, postEnd);
            String matchStr = matcher.group();
            String replacement = fn.replace(matchStr);
            this.str = this.str.substring(0, pre[0]) + replacement + this.str.substring(pre[1]);
            rebuild();
            matcher = pattern.matcher(nfkdStr);
        }
        return this.str;
    }

    public String getString() {
        return this.str;
    }
}
