package com.lnreader.app;
import java.util.Arrays;
/** Display-only whitespace cleanup; stored text and progress offsets stay intact. */
final class DisplayText {
    final String text; final int[] offsets; final boolean startsParagraph;
    DisplayText(String source, int start, int end) { this(source, start, end, false, false); }
    DisplayText(String source, int start, int end, boolean indent, boolean heading) {
        startsParagraph = start == 0 || source.charAt(start - 1) == '\n'; StringBuilder result = new StringBuilder(); int[] mapping = new int[Math.max(16, Math.min(24001, end - start + 1))]; int used = 0, line = 0;
        for (int cursor = start; cursor < end;) {
            int lineEnd = source.indexOf('\n', cursor); if (lineEnd < 0 || lineEnd > end) lineEnd = end; boolean blank = true;
            for (int i = cursor; i < lineEnd; i++) if (!Character.isWhitespace(source.charAt(i)) && source.charAt(i) != '\u3000') { blank = false; break; }
            if (!blank) {
                int stop = lineEnd < end ? lineEnd + 1 : lineEnd, needed = used + stop - cursor + 3; if (needed > mapping.length) mapping = Arrays.copyOf(mapping, Math.max(needed, mapping.length * 2));
                if (indent && !(line == 0 && heading) && (line > 0 || startsParagraph) && !Character.isWhitespace(source.charAt(cursor)) && source.charAt(cursor) != '\u3000') { result.append("\u3000\u3000"); mapping[used++] = cursor; mapping[used++] = cursor; }
                for (int i = cursor; i < stop; i++) { mapping[used++] = i; result.append(source.charAt(i)); } line++;
            } cursor = lineEnd + 1;
        }
        mapping[used] = end; offsets = Arrays.copyOf(mapping, used + 1); text = result.toString();
    }
    int sourceOffset(int local) { return offsets[Math.max(0, Math.min(offsets.length - 1, local))]; }
    int displayOffset(int sourceOffset) { int i = Arrays.binarySearch(offsets, sourceOffset); if (i >= 0) { while (i > 0 && offsets[i - 1] == sourceOffset) i--; return i; } return Math.min(text.length(), -i - 1); }
}
