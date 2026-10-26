package com.lnreader.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

final class GalleryChecks {
    private static void check(boolean v,String s){if(!v)throw new AssertionError(s);}
    private static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void until(java.util.function.BooleanSupplier ready)throws Exception{long end=SystemClock.elapsedRealtime()+45000;while(!ready.getAsBoolean()){if(SystemClock.elapsedRealtime()>end)throw new AssertionError("Gallery wait timeout");Thread.sleep(30);}}
    static String run(Instrumentation t)throws Exception{
        Context c=t.getTargetContext();NativeActivity a=(NativeActivity)t.startActivitySync(new Intent(c,NativeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));until(()->{try{return get(a,"lib")!=null;}catch(Exception e){return false;}});
        List<Uri> images=new ArrayList<>();String album="LiNoRr_QA_Paging_"+System.currentTimeMillis();Bitmap pixel=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);pixel.eraseColor(Color.BLUE);ByteArrayOutputStream jpg=new ByteArrayOutputStream();pixel.compress(Bitmap.CompressFormat.JPEG,70,jpg);pixel.recycle();MediaGallery[] gallery={null};Dialog[] dialog={null};
        try{
            for(int i=0;i<205;i++){ContentValues row=new ContentValues();row.put(MediaStore.Images.Media.DISPLAY_NAME,"paging-"+i+".jpg");row.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");row.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/"+album);row.put(MediaStore.Images.Media.IS_PENDING,1);Uri image=c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,row);check(image!=null,"Image insert");images.add(image);try(OutputStream out=c.getContentResolver().openOutputStream(image)){out.write(jpg.toByteArray());}row.clear();row.put(MediaStore.Images.Media.IS_PENDING,0);c.getContentResolver().update(image,row,null,null);}
            t.runOnMainSync(()->{try{gallery[0]=new MediaGallery(a,0xff303b32,0xfff7f6f2,false,()->{},uri->{});Method sheet=NativeActivity.class.getDeclaredMethod("sheet",String.class,LinearLayout.class);sheet.setAccessible(true);dialog[0]=(Dialog)sheet.invoke(a,"相册回归",gallery[0]);dialog[0].setOnDismissListener(d->gallery[0].close());}catch(Exception e){throw new RuntimeException(e);}});
            MediaGallery view=gallery[0];Spinner spinner=(Spinner)get(view,"albums");GridView grid=(GridView)get(view,"grid");until(()->spinner.getAdapter() instanceof CursorAdapter);int[] selected={-1};
            t.runOnMainSync(()->{for(int i=0;i<spinner.getAdapter().getCount();i++){android.database.Cursor row=(android.database.Cursor)spinner.getAdapter().getItem(i);if(row.getString(2).equals(album)){selected[0]=i;break;}}check(selected[0]>=0,"Album absent from on-disk index");spinner.setSelection(selected[0]);});
            until(()->{try{return !(Boolean)get(view,"loading")&&grid.getAdapter().getCount()==200&&((String)get(view,"bucket"))!=null;}catch(Exception e){return false;}});
            check(((List<?>)get(view,"visible")).size()==200,"Photo metadata exceeded page bound");long first=grid.getAdapter().getItemId(0);t.runOnMainSync(()->{try{((Button)get(view,"next")).performClick();}catch(Exception e){throw new RuntimeException(e);}});
            until(()->{try{return !(Boolean)get(view,"loading")&&(Integer)get(view,"page")==1;}catch(Exception e){return false;}});check(grid.getAdapter().getCount()==5,"Second photo page lost entries");check(first>grid.getAdapter().getItemId(0),"Photo pages repeat");
            t.runOnMainSync(()->{try{((Button)get(view,"previous")).performClick();}catch(Exception e){throw new RuntimeException(e);}});until(()->{try{return !(Boolean)get(view,"loading")&&(Integer)get(view,"page")==0;}catch(Exception e){return false;}});check(grid.getAdapter().getItemId(0)==first,"Previous page changed identity");
            File index=(File)get(view,"indexFile");t.runOnMainSync(()->dialog[0].dismiss());until(()->!index.exists());check((Boolean)get(view,"closed"),"Gallery workers not closed");
            return "PASS: disk-backed album cursor, 200-row photo pages, next/previous navigation, stable identity and index/worker cleanup\n";
        }finally{if(dialog[0]!=null)t.runOnMainSync(()->dialog[0].dismiss());for(Uri image:images)c.getContentResolver().delete(image,null,null);}
    }
}
