package org.devtcg.five.activity;

import org.devtcg.five.Constants;
import org.devtcg.five.R;
import org.devtcg.five.service.MetaService;
import org.devtcg.five.widget.ServerPreference;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.PreferenceActivity;
import android.view.Menu;
import android.view.MenuItem;

public class Settings extends PreferenceActivity implements OnSharedPreferenceChangeListener
{
	private static final String KEY_SERVER = "server";
	private static final String KEY_AUTOSYNC = "autosync";

	private ServerPreference mServerPref;
	private ListPreference mAutosyncPref;

	public static void show(Context context)
	{
		context.startActivity(new Intent(context, Settings.class));
	}

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		addPreferencesFromResource(R.xml.settings);

		getPreferenceScreen().getSharedPreferences()
				.registerOnSharedPreferenceChangeListener(this);

		mServerPref = (ServerPreference)findPreference(KEY_SERVER);
		mAutosyncPref = (ListPreference)findPreference(KEY_AUTOSYNC);

		mServerPref.init();
	}



	@Override
	protected void onResume()
	{
		super.onResume();

		getPreferenceScreen().getSharedPreferences()
				.registerOnSharedPreferenceChangeListener(this);

		mAutosyncPref.setEnabled(mServerPref.isEmpty() == false);
		updateSummaries();
	}

	@Override
	protected void onPause()
	{
		super.onPause();

		getPreferenceScreen().getSharedPreferences()
				.unregisterOnSharedPreferenceChangeListener(this);
	}

	@Override
	protected void onDestroy()
	{
		mServerPref.cleanup();
		super.onDestroy();
	}

	public void updateSummaries()
	{
		mAutosyncPref.setSummary(mAutosyncPref.getEntry());
	}

	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key)
	{
		if (key.equals(KEY_AUTOSYNC))
		{
			MetaService.rescheduleAutoSync(this, Long.parseLong(mAutosyncPref.getValue()));
			updateSummaries();
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu)
	{
		getMenuInflater().inflate(R.menu.settings, menu);
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu)
	{
		boolean isSyncing = MetaService.isSyncing();
		menu.findItem(R.id.start_sync).setVisible(!isSyncing);
		menu.findItem(R.id.stop_sync).setVisible(isSyncing);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		switch (item.getItemId())
		{
			case R.id.start_sync:
				startService(new Intent(Constants.ACTION_START_SYNC, null,
						this, MetaService.class));
				return true;

			case R.id.stop_sync:
				startService(new Intent(Constants.ACTION_STOP_SYNC, null,
						this, MetaService.class));
				return true;

			default:
				return false;
		}
	}
}
