package com.lnreader.app;

import java.util.ArrayList;
import java.util.List;

/** Page boundaries are line boundaries; no line may be skipped between pages. */
public final class Pagination {
    private Pagination() {}
    public static List<Integer> starts(int[] tops, int[] bottoms, int height) {
        if (tops.length != bottoms.length || height <= 0) throw new IllegalArgumentException("Invalid page geometry");
        List<Integer> pages = new ArrayList<>();
        int line = 0;
        while (line < tops.length) {
            pages.add(line);
            int first = line++;
            while (line < tops.length && bottoms[line] - tops[first] <= height) line++;
        }
        return pages;
    }
}
