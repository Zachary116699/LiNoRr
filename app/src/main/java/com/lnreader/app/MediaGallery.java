package com.lnreader.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** Photo pages stay bounded; album aggregation is streamed into an on-disk temporary index. */
final class MediaGallery extends LinearLayout implements AutoCloseable {
    interface Pick {void image(Uri uri);}
    private static final int PAGE=200;
    private static final Set<String> activeIndexes=ConcurrentHashMap.newKeySet();
    private static final class Photo {
        final long id;final String name;
        Photo(long id,String name){this.id=id;this.name=name;}
        Uri uri(){return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,id);}
    }
    private final ExecutorService worker=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(24),new ThreadPoolExecutor.DiscardOldestPolicy());
    private final ExecutorService queries=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),new ThreadPoolExecutor.DiscardOldestPolicy());
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final LruCache<Long,Bitmap> thumbnails=new LruCache<Long,Bitmap>(4*1024*1024){@Override protected int sizeOf(Long key,Bitmap b){return b.getAllocationByteCount();}};
    private final List<Photo> visible=new ArrayList<>();
    private final List<Long> anchors=new ArrayList<>();
    private final Spinner albums;private final TextView status,pageLabel;
    private final GridView grid;private final Button previous,next;
    private final Pick pick;private final int ink,background;
    private volatile boolean closed;
    private final CancellationSignal albumSignal=new CancellationSignal();
    private CancellationSignal pageSignal;
    private final Object indexLock=new Object();
    private SQLiteDatabase albumIndex;private Cursor albumCursor;
    private final File indexFile;
    private int generation,page;private String bucket;private boolean loading,loaded,more;
    private final BaseAdapter adapter=new BaseAdapter(){
        public int getCount(){return visible.size();}public Object getItem(int p){return visible.get(p);}public long getItemId(int p){return visible.get(p).id;}
        public View getView(int p,View recycled,ViewGroup parent){Cell cell=recycled instanceof Cell?(Cell)recycled:new Cell();cell.bind(visible.get(p));return cell;}
    };
    MediaGallery(Context context,int ink,int background,boolean partial,Runnable permissions,Pick pick){
        super(context);this.ink=ink;this.background=background;this.pick=pick;setOrientation(VERTICAL);
        indexFile=new File(context.getCacheDir(),"albums-"+UUID.randomUUID()+".sqlite");
        try(java.nio.file.DirectoryStream<java.nio.file.Path> files=java.nio.file.Files.newDirectoryStream(context.getCacheDir().toPath(),"albums-*.sqlite*")){for(java.nio.file.Path file:files){String path=file.toString(),base=path.replaceFirst("(?:-journal|-wal|-shm)$","");if(!activeIndexes.contains(base))java.nio.file.Files.deleteIfExists(file);}}catch(java.io.IOException ignored){}
        activeIndexes.add(indexFile.getPath());
        if(partial){addView(label("当前仅能访问获准照片；允许全部照片后可浏览完整图集。",14));Button grant=control("允许全部照片");grant.setOnClickListener(v->permissions.run());addView(grant);}
        albums=new Spinner(context);albums.setContentDescription("选择相册");addView(albums,new LayoutParams(-1,dp(48)));
        albums.setAdapter(new ArrayAdapter<String>(context,android.R.layout.simple_spinner_dropdown_item,new String[]{"全部图片"}){@Override public View getView(int p,View v,ViewGroup parent){TextView view=(TextView)super.getView(p,v,parent);view.setTextColor(ink);view.setTextSize(17);return view;}});
        status=label("正在读取相册…",14);addView(status);
        grid=new GridView(context);grid.setNumColumns(3);grid.setHorizontalSpacing(dp(4));grid.setVerticalSpacing(dp(4));grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);grid.setContentDescription("相册图片");grid.setAdapter(adapter);addView(grid,new LayoutParams(-1,dp(Math.max(200,getResources().getConfiguration().screenHeightDp/2))));
        LinearLayout pager=new LinearLayout(context);pager.setGravity(Gravity.CENTER_VERTICAL);
        previous=control("上一页");previous.setContentDescription("相册上一页");next=control("下一页");next.setContentDescription("相册下一页");pageLabel=label("1",14);pageLabel.setGravity(Gravity.CENTER);
        pager.addView(previous,new LayoutParams(0,-2,1));pager.addView(pageLabel,new LayoutParams(0,-2,1));pager.addView(next,new LayoutParams(0,-2,1));addView(pager);
        previous.setOnClickListener(v->{if(!loading&&page>0)loadPage(page-1);});next.setOnClickListener(v->{if(!loading&&more)loadPage(page+1);});
        grid.setOnItemClickListener((p,v,position,id)->{if(position<visible.size())pick.image(visible.get(position).uri());});
        anchors.add(Long.MAX_VALUE);
        albums.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> p){}
            public void onItemSelected(AdapterView<?> p,View v,int position,long id){String chosen=null;if(albums.getAdapter() instanceof CursorAdapter){Cursor c=(Cursor)albums.getAdapter().getItem(position);if(c.getLong(0)!=0)chosen=c.getString(1);}if(Objects.equals(chosen,bucket)&&(loading||loaded))return;bucket=chosen;anchors.clear();anchors.add(Long.MAX_VALUE);loadPage(0);}
        });
        loadPage(0);queries.execute(this::readAlbums);
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView label(String value,int size){TextView v=new TextView(getContext());v.setText(value);v.setTextSize(size);v.setTextColor(ink);v.setPadding(0,dp(4),0,dp(4));return v;}
    private Button control(String text){Button b=new Button(getContext());b.setText(text);b.setTextColor(ink);android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();shape.setColor(background);shape.setCornerRadius(dp(8));shape.setStroke(dp(1),(ink&0xffffff)|0x33000000);b.setBackground(shape);return b;}
    private void readAlbums(){
        SQLiteDatabase db=null;Cursor result=null;
        try{
            db=SQLiteDatabase.openOrCreateDatabase(indexFile,null);db.execSQL("CREATE TABLE albums(_id INTEGER PRIMARY KEY AUTOINCREMENT,bucket TEXT UNIQUE,name TEXT,n INTEGER)");
            db.execSQL("INSERT INTO albums(_id,bucket,name,n) VALUES(0,NULL,'全部图片',0)");long total=0;db.beginTransaction();
            try(Cursor rows=getContext().getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,new String[]{"bucket_id","bucket_display_name"},null,null,null,albumSignal);
                SQLiteStatement insert=db.compileStatement("INSERT OR IGNORE INTO albums(bucket,name,n) VALUES(?,?,0)");
                SQLiteStatement increment=db.compileStatement("UPDATE albums SET n=n+1 WHERE bucket=?")){
                while(!closed&&rows!=null&&rows.moveToNext()){String key=rows.isNull(0)?"":rows.getString(0),name=rows.isNull(1)||rows.getString(1).isEmpty()?"其他图片":rows.getString(1);insert.bindString(1,key);insert.bindString(2,name);insert.executeInsert();increment.bindString(1,key);increment.executeUpdateDelete();total++;}
                if(closed)throw new OperationCanceledException();db.execSQL("UPDATE albums SET n=? WHERE _id=0",new Object[]{total});db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            result=db.rawQuery("SELECT _id,bucket,name,n FROM albums ORDER BY (_id=0) DESC,name COLLATE NOCASE,_id",null);result.getCount();
            synchronized(indexLock){if(closed)return;albumIndex=db;albumCursor=result;db=null;result=null;}
            ui.post(this::showAlbums);
        }catch(Exception error){ui.post(()->{if(!closed)status.setText("相册分类读取失败，仍可浏览全部图片。");});}
        finally{if(result!=null)result.close();if(db!=null)db.close();synchronized(indexLock){if(albumIndex==null)deleteIndexFiles();}}
    }
    private void showAlbums(){
        if(closed||albumCursor==null)return;
        albums.setAdapter(new CursorAdapter(getContext(),albumCursor,0){
            public View newView(Context c,Cursor cursor,ViewGroup parent){TextView v=label("",17);v.setPadding(dp(8),dp(10),dp(8),dp(10));return v;}
            public void bindView(View view,Context c,Cursor row){((TextView)view).setText(row.getString(2)+"（"+row.getLong(3)+"）");((TextView)view).setTextColor(ink);}
            @Override public View getDropDownView(int p,View recycled,ViewGroup parent){View v=super.getDropDownView(p,recycled,parent);v.setBackgroundColor(background);return v;}
        });
    }
    private void loadPage(int requested){
        if(closed||requested<0||requested>=anchors.size())return;
        if(pageSignal!=null)pageSignal.cancel();CancellationSignal signal=new CancellationSignal();pageSignal=signal;
        final int token=++generation;final String selected=bucket;final long anchor=anchors.get(requested);
        loading=true;page=requested;visible.clear();adapter.notifyDataSetChanged();grid.setEnabled(false);previous.setEnabled(false);next.setEnabled(false);status.setText("正在读取图片…");
        queries.execute(()->{
            List<Photo> rows=new ArrayList<>();String selection="_id<?";List<String> args=new ArrayList<>();args.add(Long.toString(anchor));
            if(selected!=null){selection+=selected.isEmpty()?" AND (bucket_id IS NULL OR bucket_id='')":" AND bucket_id=?";if(!selected.isEmpty())args.add(selected);}
            Bundle query=new Bundle();query.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,selection);query.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,args.toArray(new String[0]));query.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,"_id DESC");query.putInt(ContentResolver.QUERY_ARG_LIMIT,PAGE+1);
            try(Cursor cursor=getContext().getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,new String[]{"_id","_display_name"},query,signal)){
                while(!closed&&cursor!=null&&rows.size()<PAGE+1&&cursor.moveToNext())rows.add(new Photo(cursor.getLong(0),cursor.getString(1)));
                boolean hasMore=rows.size()>PAGE;if(hasMore)rows.remove(PAGE);
                ui.post(()->{if(closed||token!=generation)return;visible.clear();visible.addAll(rows);more=hasMore;page=requested;loaded=true;loading=false;
                    while(anchors.size()>page+1)anchors.remove(anchors.size()-1);if(more)anchors.add(rows.get(rows.size()-1).id);
                    adapter.notifyDataSetChanged();grid.setEnabled(true);grid.setSelection(0);previous.setEnabled(page>0);next.setEnabled(more);pageLabel.setText(String.valueOf(page+1));status.setText(rows.isEmpty()?"此相册没有可访问的图片":"第 "+(page+1)+" 页 · "+rows.size()+" 张图片");
                });
            }catch(Exception e){ui.post(()->{if(!closed&&token==generation){loading=false;previous.setEnabled(page>0);status.setText("无法读取图片，请检查照片权限后重试。");}});}
        });
    }
    private void deleteIndexFiles(){for(String suffix:new String[]{"","-journal","-wal","-shm"})new File(indexFile.getPath()+suffix).delete();activeIndexes.remove(indexFile.getPath());}
    private final class Cell extends ImageView {
        private Future<?> job; private CancellationSignal cancellation; private long bound = -1;
        Cell() { super(MediaGallery.this.getContext()); setScaleType(ScaleType.CENTER_CROP); }
        @Override protected void onMeasure(int width, int height) { int side = MeasureSpec.getSize(width); super.onMeasure(width, MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)); }
        void bind(Photo photo) {
            if (job != null) job.cancel(true); if (cancellation != null) cancellation.cancel();
            bound = photo.id; setContentDescription("选择图片：" + photo.name); setBackgroundColor(background ^ 0x00101010); setImageDrawable(null);
            Bitmap cached = thumbnails.get(photo.id); if (cached != null) { setImageBitmap(cached); return; }
            CancellationSignal signal = new CancellationSignal(); cancellation = signal;
            if (closed) return;
            job = worker.submit(() -> {
                try {
                    Bitmap bitmap = Build.VERSION.SDK_INT >= 29 ? getContext().getContentResolver().loadThumbnail(photo.uri(), new Size(240, 240), signal)
                        : MediaStore.Images.Thumbnails.getThumbnail(getContext().getContentResolver(), photo.id, MediaStore.Images.Thumbnails.MINI_KIND, null);
                    if (bitmap == null || closed || signal.isCanceled()) return; thumbnails.put(photo.id, bitmap);
                    ui.post(() -> { if (!closed && bound == photo.id && !signal.isCanceled()) setImageBitmap(bitmap); });
                } catch (Exception ignored) { /* A photo can be removed or its grant revoked while browsing. */ }
            });
        }
    }
    @Override public void close(){closed=true;albumSignal.cancel();if(pageSignal!=null)pageSignal.cancel();queries.shutdownNow();worker.shutdownNow();ui.removeCallbacksAndMessages(null);thumbnails.evictAll();synchronized(indexLock){if(albumCursor!=null){albumCursor.close();albumCursor=null;}if(albumIndex!=null){albumIndex.close();albumIndex=null;deleteIndexFiles();}}}
}
