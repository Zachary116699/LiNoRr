package com.lnreader.app;

import java.util.*;

/** Index-only tree: rows stay virtualized even for very large books. */
final class ChapterTree {
    final int[] parent, depth;
    final boolean[] branch;
    private final List<BookParser.Chapter> chapters;
    ChapterTree(List<BookParser.Chapter> chapters) {
        this.chapters = chapters;
        int n = chapters.size(); parent = new int[n]; depth = new int[n]; branch = new boolean[n];
        Deque<Integer> stack = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            int level = chapters.get(i).level;
            while (!stack.isEmpty() && (chapters.get(stack.peek()).level >= level || chapters.get(stack.peek()).level == 0)) stack.pop();
            parent[i] = stack.isEmpty() ? -1 : stack.peek();
            if (parent[i] >= 0) { depth[i] = depth[parent[i]] + 1; branch[parent[i]] = true; }
            stack.push(i);
        }
    }
    List<Integer> visible(Set<Integer> collapsed, String query) {
        String term = query.trim().toLowerCase(Locale.ROOT);
        boolean[] included = new boolean[parent.length], hidden = new boolean[parent.length];
        if (!term.isEmpty()) for (int i = 0; i < parent.length; i++) {
            if (!chapters.get(i).title.toLowerCase(Locale.ROOT).contains(term)) continue;
            for (int p = i; p >= 0 && !included[p]; p = parent[p]) included[p] = true;
        }
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < parent.length; i++) {
            int p = parent[i]; hidden[i] = p >= 0 && (hidden[p] || collapsed.contains(p));
            if (term.isEmpty() ? !hidden[i] : included[i]) result.add(i);
        }
        return result;
    }
}
