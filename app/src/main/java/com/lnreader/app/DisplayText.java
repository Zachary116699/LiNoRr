package com.lnreader.app;
import java.util.Arrays;
/** Display-only whitespace cleanup; stored text and progress offsets stay intact. */
final class DisplayText {
    final String text; final int[] offsets; final boolean startsParagraph;
    DisplayText(String source, int start, int end) {
        startsParagraph = start == 0 || source.charAt(start - 1) == '\n'; StringBuilder result = new StringBuilder(end - start); int[] mapping = new int[end - start + 1]; int used = 0;
        for (int cursor = start; cursor < end;) {
            int lineEnd = source.indexOf('\n', cursor); if (lineEnd < 0 || lineEnd > end) lineEnd = end; boolean blank = true;
            for (int i = cursor; i < lineEnd; i++) if (!Character.isWhitespace(source.charAt(i)) && source.charAt(i) != '\u3000') { blank = false; break; }
            if (!blank) { int stop = lineEnd < end ? lineEnd + 1 : lineEnd; for (int i = cursor; i < stop; i++) { mapping[used++] = i; result.append(source.charAt(i)); } } cursor = lineEnd + 1;
        }
        mapping[used] = end; offsets = Arrays.copyOf(mapping, used + 1); text = result.toString();
    }
    int sourceOffset(int local) { return offsets[Math.max(0, Math.min(offsets.length - 1, local))]; }
    int displayOffset(int sourceOffset) { int i = Arrays.binarySearch(offsets, sourceOffset); return i >= 0 ? i : Math.min(text.length(), -i - 1); }
}
