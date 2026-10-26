package com.lnreader.app;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Test APK only: distinct tiny TXT streams without creating 30,000 source files. */
public final class ScaleTextProvider extends ContentProvider {
    private final ExecutorService writers=Executors.newFixedThreadPool(2);
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){return "text/plain";}
    @Override public Cursor query(Uri uri,String[] p,String s,String[] a,String order){return new MatrixCursor(new String[]{"_display_name","_size"});}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        try{ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();byte[] text=("第一章 测试 "+uri.getLastPathSegment()+"\n这是一份独立的导入测试正文。\n").getBytes(StandardCharsets.UTF_8);
            writers.execute(()->{try(OutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){out.write(text);}catch(IOException ignored){}});return pipe[0];
        }catch(IOException e){throw new FileNotFoundException(e.getMessage());}
    }
}
