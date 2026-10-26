package com.lnreader.app;

import android.app.Activity;
import android.os.Bundle;

/** Guarded native regression entry point; the shipped app contains no test provider or runner. */
public final class StorageChecks extends GestureChecks {
    private Bundle arguments;
    @Override public void onCreate(Bundle args){arguments=args;super.onCreate(args);}
    @Override public void onStart(){
        if("true".equals(arguments.getString("gesture"))){super.onStart();return;}
        Bundle output=new Bundle();
        try{
            if(!"LNReader_QA".equals(arguments.getString("guard")))throw new AssertionError("Dedicated QA emulator guard required");
            if(arguments.containsKey("migrationPrepare")){output.putString("stream",MigrationChecks.prepare(this,arguments.getString("migrationPrepare")));finish(Activity.RESULT_OK,output);return;}
            if("true".equals(arguments.getString("migrationRecover"))){output.putString("stream",MigrationChecks.recover(this));finish(Activity.RESULT_OK,output);return;}
            if("true".equals(arguments.getString("galleryCheck"))){output.putString("stream",GalleryChecks.run(this));finish(Activity.RESULT_OK,output);return;}
            output.putString("stream","true".equals(arguments.getString("scale"))?LargeLibraryChecks.run(this,"true".equals(arguments.getString("resume"))):FunctionalChecks.run(this));
            finish(Activity.RESULT_OK,output);
        }catch(Throwable error){output.putString("stream","FAIL: "+android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,output);}
    }
}
