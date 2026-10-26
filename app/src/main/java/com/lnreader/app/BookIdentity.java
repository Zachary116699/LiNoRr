package com.lnreader.app;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** File names and paths never affect identity or generated cover colors. */
final class BookIdentity {
    static final class Fingerprint{final String hash;final long size;Fingerprint(String hash,long size){this.hash=hash;this.size=size;}}
    static String fingerprint(InputStream input,long expected,long limit)throws Exception{
        return scan(input,expected,limit).hash;
    }
    static Fingerprint scan(InputStream input,long expected,long limit)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");for(int shift=56;shift>=0;shift-=8)digest.update((byte)(expected>>>shift));byte[] buffer=new byte[32768];long total=0;int n;
        while((n=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("指纹任务已取消");if((total+=n)>limit)throw new IOException("文件超出大小限制");digest.update(buffer,0,n);}return new Fingerprint(total==expected?hex(digest.digest()):"",total);
    }
    static String fingerprint(byte[] bytes) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long length=bytes.length;
        for(int shift=56;shift>=0;shift-=8)digest.update((byte)(length>>>shift));digest.update(bytes);
        return hex(digest.digest());
    }
    static String fingerprint(List<File> files) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[32768];
        for (File file : files) {
            long length = file.length(); for (int shift = 56; shift >= 0; shift -= 8) digest.update((byte) (length >>> shift));
            try (InputStream input = new FileInputStream(file)) { int n; while ((n = input.read(buffer)) >= 0) {if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("指纹任务已取消");digest.update(buffer, 0, n);} }
        }
        return hex(digest.digest());
    }
    static String hex(byte[] bytes){char[] out=new char[bytes.length*2],digits="0123456789abcdef".toCharArray();for(int i=0;i<bytes.length;i++){out[i*2]=digits[(bytes[i]&255)>>>4];out[i*2+1]=digits[bytes[i]&15];}return new String(out);}
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
