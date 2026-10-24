package com.lnreader.app;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** File names and paths never affect identity or generated cover colors. */
final class BookIdentity {
    static String fingerprint(List<File> files) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[32768];
        for (File file : files) {
            long length = file.length(); for (int shift = 56; shift >= 0; shift -= 8) digest.update((byte) (length >>> shift));
            try (InputStream input = new FileInputStream(file)) { int n; while ((n = input.read(buffer)) >= 0) digest.update(buffer, 0, n); }
        }
        StringBuilder result = new StringBuilder(); for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255)); return result.toString();
    }
    static int coverColor(String hash) {
        // Low saturation is what makes a light color quiet; an RGB floor alone
        // still permits harsh pinks/yellows. Keep L=.81..87 and S=.16..24.
        long seed = hash.hashCode() & 0xffffffffL;
        double hue = (seed % 3600) / 600.0, saturation = .16 + ((seed >>> 9) % 9) / 100.0, light = .81 + ((seed >>> 17) % 7) / 100.0;
        double chroma = (1 - Math.abs(2 * light - 1)) * saturation, x = chroma * (1 - Math.abs(hue % 2 - 1)), m = light - chroma / 2;
        double r = 0, g = 0, b = 0;
        if (hue < 1) { r = chroma; g = x; } else if (hue < 2) { r = x; g = chroma; } else if (hue < 3) { g = chroma; b = x; } else if (hue < 4) { g = x; b = chroma; } else if (hue < 5) { r = x; b = chroma; } else { r = chroma; b = x; }
        return 0xff000000 | (int) Math.round((r + m) * 255) << 16 | (int) Math.round((g + m) * 255) << 8 | (int) Math.round((b + m) * 255);
    }
}
