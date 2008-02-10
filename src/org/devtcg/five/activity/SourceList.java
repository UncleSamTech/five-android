package org.devtcg.five.activity;

import org.devtcg.five.R;
import org.devtcg.five.provider.Five;
import org.devtcg.five.service.IMetaObserver;
import org.devtcg.five.service.IMetaService;
import org.devtcg.five.service.MetaService;

import android.app.ListActivity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.SimpleCursorAdapter;

public class SourceList extends ListActivity
{
	private static final String TAG = "SourceList";
	
	private static final int MENU_SYNC = Menu.FIRST;
	
	private Cursor mCursor;

	private static final String[] PROJECTION = new String[] {
	  Five.Sources._ID, Five.Sources.NAME,
	  Five.Sources.REVISION };
	
	private Handler mHandler = new Handler();
	private IMetaService mService;

    @Override
    public void onCreate(Bundle icicle)
    {
        super.onCreate(icicle);
        setContentView(R.layout.source_list);

        Intent intent = getIntent();
        if (intent.getData() == null)
        	intent.setData(Five.Sources.CONTENT_URI);

        if (intent.getAction() == null)
        	intent.setAction(Intent.VIEW_ACTION);

        mCursor = managedQuery(intent.getData(), PROJECTION, null, null);
        assert mCursor != null;

        setListAdapter(new SimpleCursorAdapter(this,
        	R.layout.source_list_item,
        	mCursor,
        	new String[] { Five.Sources.NAME, Five.Sources.REVISION },
        	new int[] { R.id.sourceName, R.id.sourceText }
        ));
        
        bindService(new Intent(this, MetaService.class), null, mConnection,
          Context.BIND_AUTO_CREATE);
    }

    private ServiceConnection mConnection = new ServiceConnection()
    {
		public void onServiceConnected(ComponentName name, IBinder service)
		{
			mService = IMetaService.Stub.asInterface(service);
			
			try
			{
				mService.registerObserver(mObserver);
			}
			catch (DeadObjectException e)
			{
				Log.e(TAG, "What the hell happened here?", e);
				mService = null;
			}
		}

		public void onServiceDisconnected(ComponentName name)
		{
			Log.d(TAG, "onServiceDisconnected: Where did it go?  Should we retry?  Hmm.");
			mService = null;
		}
    };
    
    private IMetaObserver.Stub mObserver = new IMetaObserver.Stub()
    {
		public void beginSync()
		{
			Log.d(TAG, "beginSync");
		}

		public void endSync()
		{
			Log.d(TAG, "endSync");
		}
		
		public void beginSource(int sourceId)
		{
			Log.d(TAG, "beginSource: " + sourceId);
		}

		public void endSource(int sourceId)
		{
			Log.d(TAG, "endSource: " + sourceId);
		}

		public void updateProgress(int sourceId, int itemNo, int itemCount)
		{
			Log.d(TAG, "updateProgress: " + sourceId + " (" + itemNo + " / " + itemCount + ")");
		}
    };

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
    	super.onCreateOptionsMenu(menu);
    	
    	menu.add(0, MENU_SYNC, "Synchronize");
    	
    	return true;
    }

    protected void menuSync()
    {
    	Log.i(TAG, "menuSync(), here we go!");

		try
		{
			mService.startSync();
		}
		catch (DeadObjectException e)
		{
			mService = null;
		}	    	
    }
    
    @Override
    public boolean onOptionsItemSelected(Menu.Item item)
    {
    	switch (item.getId())
    	{
    	case MENU_SYNC:
    		menuSync();
    		return true;
    	}
    	
    	return super.onOptionsItemSelected(item);
    }
}