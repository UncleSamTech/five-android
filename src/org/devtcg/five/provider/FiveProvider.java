/*
 * Copyright (C) 2008 Josh Guilfoyle <jasta@devtcg.org>
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 2, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 */

package org.devtcg.five.provider;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.devtcg.five.Constants;
import org.devtcg.five.provider.AbstractTableMerger.SyncableColumns;
import org.devtcg.five.provider.util.AlbumMerger;
import org.devtcg.five.provider.util.ArtistMerger;
import org.devtcg.five.provider.util.PlaylistMerger;
import org.devtcg.five.provider.util.PlaylistSongMerger;
import org.devtcg.five.provider.util.SongItem;
import org.devtcg.five.provider.util.SongMerger;
import org.devtcg.five.provider.util.SourceItem;
import org.devtcg.five.util.FileUtils;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.DatabaseUtils.InsertHelper;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.database.sqlite.SQLiteStatement;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

public class FiveProvider extends AbstractSyncProvider
{
	private static final String TAG = "FiveProvider";

	/**
	 * Used by the sync provider instance to determine which source we are
	 * synchronizing.
	 */
	public SourceItem mSource;

	DatabaseHelper mHelper;
	private static final String DATABASE_NAME = "five.db";
	private static final int DATABASE_VERSION = 36;

	private static final UriMatcher sUriMatcher;
	private static final HashMap<String, String> sArtistsMap;
	private static final HashMap<String, String> sAlbumsMap;
	private static final HashMap<String, String> sSongsMap;

	private InsertHelper mArtistInserter;
	private InsertHelper mAlbumInserter;
	private InsertHelper mSongInserter;
	private InsertHelper mDeletedArtistInserter;
	private InsertHelper mDeletedAlbumInserter;
	private InsertHelper mDeletedSongInserter;
	private InsertHelper mDeletedPlaylistInserter;
	private InsertHelper mDeletedPlaylistSongInserter;

	private static enum URIPatternIds
	{
		SOURCES, SOURCE,
		ARTISTS, ARTIST, ARTIST_PHOTO, DELETED_ARTIST,
		ALBUMS, ALBUMS_BY_ARTIST, ALBUMS_WITH_ARTIST, ALBUMS_COMPLETE, ALBUM,
		  ALBUM_ARTWORK, ALBUM_ARTWORK_BIG, DELETED_ALBUM,
		SONGS, SONGS_BY_ALBUM, SONGS_BY_ARTIST, SONGS_BY_ARTIST_ON_ALBUM, SONG,
		  DELETED_SONG,
		PLAYLISTS, PLAYLIST, SONGS_IN_PLAYLIST, SONG_IN_PLAYLIST,
		  PLAYLIST_SONG, PLAYLIST_SONGS, DELETED_PLAYLIST, DELETED_PLAYLIST_SONG,
		CACHE, CACHE_ITEMS_BY_SOURCE,
		ADJUST_COUNTS,
		;

		public static URIPatternIds get(int ordinal)
		{
			return values()[ordinal];
		}
	}

	private class DatabaseHelper extends SQLiteOpenHelper
	{
		public DatabaseHelper(Context ctx, String databaseName)
		{
			super(ctx, databaseName, null, DATABASE_VERSION);
		}

		@Override
		public void onCreate(SQLiteDatabase db)
		{
			db.execSQL(Five.Sources.SQL.CREATE);

			execStatements(db, Five.Music.Artists.SQL.CREATE);
			execStatements(db, Five.Music.Albums.SQL.CREATE);
			execStatements(db, Five.Music.Songs.SQL.CREATE);
			execStatements(db, Five.Music.Playlists.SQL.CREATE);
			execStatements(db, Five.Music.PlaylistSongs.SQL.CREATE);

			if (isTemporary() == false)
			{
				execStatements(db, Five.Music.Albums.SQL.INDEX);
				execStatements(db, Five.Music.Songs.SQL.INDEX);
				execStatements(db, Five.Music.PlaylistSongs.SQL.INDEX);
			}
		}

		private void createDeletedTable(SQLiteDatabase db, String deletedTable)
		{
			db.execSQL("CREATE TABLE " + deletedTable + " (" +
					SyncableColumns._ID + " INTEGER PRIMARY KEY, " +
					SyncableColumns._SYNC_ID + " INTEGER, " +
					SyncableColumns._SYNC_TIME + " BIGINT " +
					")");
		}

		private void execStatements(SQLiteDatabase db, String[] statements)
		{
			for (int i = 0; i < statements.length; i++)
				db.execSQL(statements[i]);
		}

		private void onDrop(SQLiteDatabase db)
		{
			db.execSQL(Five.Sources.SQL.DROP);

			execStatements(db, Five.Music.Artists.SQL.DROP);
			execStatements(db, Five.Music.Albums.SQL.DROP);
			execStatements(db, Five.Music.Songs.SQL.DROP);
			execStatements(db, Five.Music.Playlists.SQL.DROP);
			execStatements(db, Five.Music.PlaylistSongs.SQL.DROP);
		}

		@Override
		public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion)
		{
			if (oldVersion == 17 && newVersion == 18)
			{
				Log.w(TAG, "Attempting to upgrade to " + newVersion);
				execStatements(db, Five.Music.Artists.SQL.INDEX);
				execStatements(db, Five.Music.Albums.SQL.INDEX);
				execStatements(db, Five.Music.Songs.SQL.INDEX);
			}
			else
			{
				Log.w(TAG, "Version too old, wiping out database contents...");
				onDrop(db);
				onCreate(db);
			}
		}

		@Override
		public void onOpen(SQLiteDatabase db)
		{
			mArtistInserter = new InsertHelper(db, Five.Music.Artists.SQL.TABLE);
			mAlbumInserter = new InsertHelper(db, Five.Music.Albums.SQL.TABLE);
			mSongInserter = new InsertHelper(db, Five.Music.Songs.SQL.TABLE);

			mDeletedArtistInserter = new InsertHelper(db, Five.Music.Artists.SQL.DELETED_TABLE);
			mDeletedAlbumInserter = new InsertHelper(db, Five.Music.Albums.SQL.DELETED_TABLE);
			mDeletedSongInserter = new InsertHelper(db, Five.Music.Songs.SQL.DELETED_TABLE);
			mDeletedPlaylistInserter = new InsertHelper(db, Five.Music.Playlists.SQL.DELETED_TABLE);
			mDeletedPlaylistSongInserter = new InsertHelper(db, Five.Music.PlaylistSongs.SQL.DELETED_TABLE);
		}
	}

	private static final AbstractSyncProvider.Creator<FiveProvider> CREATOR =
		new AbstractSyncProvider.Creator<FiveProvider>()
	{
		@Override
		public FiveProvider newInstance()
		{
			return new FiveProvider();
		}
	};

	@Override
	public AbstractSyncAdapter getSyncAdapter()
	{
		return new FiveSyncAdapter(getContext(), this);
	}

	@Override
	public AbstractSyncProvider getSyncInstance()
	{
		String dbName = "_sync-" + mSource.getId();
		File path = getContext().getDatabasePath(dbName);
		FiveProvider provider = CREATOR.getSyncInstance(path);
		provider.mSource = mSource;
		provider.mHelper = provider.new DatabaseHelper(getContext(), dbName);
		return provider;
	}

	@Override
	public boolean onCreate()
	{
		if (isTemporary())
			throw new IllegalStateException("onCreate should not be called on temp providers");

		mHelper = new DatabaseHelper(getContext(), DATABASE_NAME);
		return true;
	}

	@Override
	public void close()
	{
		mHelper.close();
	}

	@Override
	public SQLiteDatabase getDatabase()
	{
		return mHelper.getWritableDatabase();
	}

	@Override
	protected Iterable<? extends AbstractTableMerger> getMergers()
	{
		ArrayList<AbstractTableMerger> list = new ArrayList<AbstractTableMerger>(3);
		list.add(new ArtistMerger(this));
		list.add(new AlbumMerger(this));
		list.add(new SongMerger(this));
		list.add(new PlaylistMerger(this));
		list.add(new PlaylistSongMerger(this));
		return list;
	}

	private static String getSecondToLastPathSegment(Uri uri)
	{
		List<String> segments = uri.getPathSegments();
		int size;

		if ((size = segments.size()) < 2)
			throw new IllegalArgumentException("URI is not long enough to have a second-to-last path");

		return segments.get(size - 2);
	}

	private static List<Long> getNumericPathSegments(Uri uri)
	{
		List<String> segments = uri.getPathSegments();
		ArrayList<Long> numeric = new ArrayList<Long>(3);

		for (String segment: segments)
		{
			try {
				numeric.add(Long.parseLong(segment));
			} catch (NumberFormatException e) {}
		}

		return numeric;
	}

	private static void checkWritePermission()
	{
		if (Binder.getCallingPid() != Process.myPid())
			throw new SecurityException("Write access is not permitted.");
	}

	/*-***********************************************************************/

    private static int stringModeToInt(Uri uri, String mode)
	  throws FileNotFoundException
	{
		int modeBits;
		if ("r".equals(mode))
		{
			modeBits = ParcelFileDescriptor.MODE_READ_ONLY;
		}
		else if ("w".equals(mode) || "wt".equals(mode))
		{
			modeBits = ParcelFileDescriptor.MODE_WRITE_ONLY
					| ParcelFileDescriptor.MODE_CREATE
					| ParcelFileDescriptor.MODE_TRUNCATE;
		}
		else if ("wa".equals(mode))
		{
			modeBits = ParcelFileDescriptor.MODE_WRITE_ONLY
					| ParcelFileDescriptor.MODE_CREATE
					| ParcelFileDescriptor.MODE_APPEND;
		}
		else if ("rw".equals(mode))
		{
			modeBits = ParcelFileDescriptor.MODE_READ_WRITE
					| ParcelFileDescriptor.MODE_CREATE;
		}
		else if ("rwt".equals(mode))
		{
			modeBits = ParcelFileDescriptor.MODE_READ_WRITE
					| ParcelFileDescriptor.MODE_CREATE
					| ParcelFileDescriptor.MODE_TRUNCATE;
		}
		else
		{
			throw new FileNotFoundException("Bad mode for " + uri + ": " + mode);
		}

		return modeBits;
	}

	public static File getArtistPhoto(long id, boolean temporary) throws FileNotFoundException
	{
		FileUtils.mkdirIfNecessary(Constants.sArtistPhotoDir);

		if (temporary == true)
			return new File(Constants.sArtistPhotoDir, id + ".tmp");
		else
			return new File(Constants.sArtistPhotoDir, String.valueOf(id));
	}

	public static File getAlbumArtwork(long id, boolean temporary) throws FileNotFoundException
	{
		return getAlbumArtwork(id, URIPatternIds.ALBUM_ARTWORK, temporary);
	}

	public static File getLargeAlbumArtwork(long id, boolean temporary) throws FileNotFoundException
	{
		return getAlbumArtwork(id, URIPatternIds.ALBUM_ARTWORK_BIG, temporary);
	}

	private static File getAlbumArtwork(long id, URIPatternIds type, boolean temporary)
		throws FileNotFoundException
	{
		FileUtils.mkdirIfNecessary(Constants.sAlbumArtworkDir);

		String filename;

		if (type == URIPatternIds.ALBUM_ARTWORK)
			filename = String.valueOf(id);
		else
			filename = id + "-big";

		if (temporary == true)
			return new File(Constants.sAlbumArtworkDir, filename + ".tmp");
		else
			return new File(Constants.sAlbumArtworkDir, filename);
	}

	@Override
	public ParcelFileDescriptor openFile(Uri uri, String mode)
	  throws FileNotFoundException
	{
		File file;

		URIPatternIds type = URIPatternIds.get(sUriMatcher.match(uri));

		switch (type)
		{
		case ALBUM_ARTWORK:
		case ALBUM_ARTWORK_BIG:
			String albumId = uri.getPathSegments().get(3);
			file = getAlbumArtwork(Long.parseLong(albumId), type, isTemporary());
			return ParcelFileDescriptor.open(file, stringModeToInt(uri, mode));

		case ARTIST_PHOTO:
			String artistId = getSecondToLastPathSegment(uri);
			file = getArtistPhoto(Long.parseLong(artistId), isTemporary());
			return ParcelFileDescriptor.open(file, stringModeToInt(uri, mode));

		default:
			throw new IllegalArgumentException("Unknown URL " + uri);
		}
	}

	@Override
	public Cursor queryInternal(Uri uri, String[] projection, String selection,
			String[] selectionArgs, String sortOrder)
	{
		SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
		String groupBy = null;

		URIPatternIds type = URIPatternIds.get(sUriMatcher.match(uri));

		switch (type)
		{
		case SOURCES:
			qb.setTables(Five.Sources.SQL.TABLE);
			break;

		case SOURCE:
			qb.setTables(Five.Sources.SQL.TABLE);
			qb.appendWhere("_id=" + uri.getLastPathSegment());
			break;

		case PLAYLIST_SONGS:
			qb.setTables(Five.Music.PlaylistSongs.SQL.TABLE);
			break;

		case PLAYLISTS:
			qb.setTables(Five.Music.Playlists.SQL.TABLE);
			break;

		case PLAYLIST:
			qb.setTables(Five.Music.Playlists.SQL.TABLE);
			qb.appendWhere("_id=" + uri.getLastPathSegment());
			break;

		case SONGS_IN_PLAYLIST:
			qb.setTables(Five.Music.PlaylistSongs.SQL.TABLE + " ps " +
			  "LEFT JOIN " + Five.Music.Songs.SQL.TABLE + " s " +
			  "ON s." + Five.Music.Songs._ID + " = ps." + Five.Music.PlaylistSongs.SONG_ID);
			qb.appendWhere("ps.playlist_id=" + getSecondToLastPathSegment(uri));
			qb.setProjectionMap(sSongsMap);

			if (sortOrder == null)
				sortOrder = "ps.position ASC";

			break;

		case SONGS:
			qb.setTables(Five.Music.Songs.SQL.TABLE);
			break;

		case SONG:
			qb.setTables(Five.Music.Songs.SQL.TABLE);
			qb.appendWhere("_id=" + uri.getLastPathSegment());
			break;

		case SONGS_BY_ARTIST:
			qb.setTables(Five.Music.Songs.SQL.TABLE);
			qb.appendWhere("artist_id=" + getSecondToLastPathSegment(uri));

			if (sortOrder == null)
				sortOrder = "title ASC";

			break;

		case SONGS_BY_ALBUM:
		case SONGS_BY_ARTIST_ON_ALBUM:
			qb.setTables(Five.Music.Songs.SQL.TABLE);

			if (type == URIPatternIds.SONGS_BY_ALBUM)
				qb.appendWhere("album_id=" + getSecondToLastPathSegment(uri));
			else /* if (type == URIPatternIds.SONGS_BY_ARTIST_ON_ALBUM) */
			{
				List<Long> segs = getNumericPathSegments(uri);
				qb.appendWhere("artist_id=" + segs.get(0) +
				  " AND album_id=" + segs.get(1));
			}

			if (sortOrder == null)
				sortOrder = "track_num ASC, title ASC";

			break;

		case ARTISTS:
		case ARTIST:
			qb.setTables(Five.Music.Artists.SQL.TABLE);
			if (type == URIPatternIds.ARTIST)
				qb.appendWhere("_id=" + uri.getLastPathSegment());
			qb.setProjectionMap(sArtistsMap);
			break;

		case ALBUM:
		case ALBUMS:
		case ALBUMS_BY_ARTIST:
		case ALBUMS_COMPLETE:
			qb.setTables(Five.Music.Albums.SQL.TABLE + " a " +
			  "LEFT JOIN " + Five.Music.Artists.SQL.TABLE + " artists " +
			  "ON artists." + Five.Music.Artists._ID + " = a." + Five.Music.Albums.ARTIST_ID);

			if (type == URIPatternIds.ALBUM)
				qb.appendWhere("a._id=" + uri.getLastPathSegment());
			else
			{
				if (type == URIPatternIds.ALBUMS_BY_ARTIST)
					qb.appendWhere("a.artist_id=" + getSecondToLastPathSegment(uri));
				else if (type == URIPatternIds.ALBUMS_COMPLETE)
					qb.appendWhere("a.num_songs > 3");
			}

			qb.setProjectionMap(sAlbumsMap);
			break;

		case ALBUMS_WITH_ARTIST:
			qb.setTables(Five.Music.Songs.SQL.TABLE + " s " +
			  "LEFT JOIN " + Five.Music.Albums.SQL.TABLE + " a " +
			  "ON a." + Five.Music.Albums._ID + " = s." + Five.Music.Songs.ALBUM_ID + " " +
			  "LEFT JOIN " + Five.Music.Artists.SQL.TABLE + " artists " +
			  "ON artists." + Five.Music.Artists._ID + " = a." + Five.Music.Albums.ARTIST_ID);

			qb.appendWhere("s.artist_id=" + getSecondToLastPathSegment(uri));

			HashMap<String, String> proj = new HashMap<String, String>(sAlbumsMap);
			proj.put(Five.Music.Albums.NUM_SONGS, "COUNT(*) AS " + Five.Music.Albums.NUM_SONGS);
			qb.setProjectionMap(proj);

			groupBy = "a." + Five.Music.Albums._ID;
			break;

		case DELETED_ARTIST:
			qb.setTables(Five.Music.Artists.SQL.DELETED_TABLE);
			break;

		case DELETED_ALBUM:
			qb.setTables(Five.Music.Albums.SQL.DELETED_TABLE);
			break;

		case DELETED_SONG:
			qb.setTables(Five.Music.Songs.SQL.DELETED_TABLE);
			break;

		case DELETED_PLAYLIST:
			qb.setTables(Five.Music.Playlists.SQL.DELETED_TABLE);
			break;

		case DELETED_PLAYLIST_SONG:
			qb.setTables(Five.Music.PlaylistSongs.SQL.DELETED_TABLE);
			break;

		default:
			throw new IllegalArgumentException("Unknown URI: " + uri);
		}

		SQLiteDatabase db = mHelper.getReadableDatabase();
		Cursor c = qb.query(db, projection, selection, selectionArgs, groupBy, null, sortOrder);
		if (isTemporary() == false)
			c.setNotificationUri(getContext().getContentResolver(), uri);

		return c;
	}

	/*-***********************************************************************/

	private int updateSong(SQLiteDatabase db, Uri uri, URIPatternIds type, ContentValues v,
	  String sel, String[] selArgs)
	{
		String custom;

		switch (type)
		{
			case SONG:
				custom = extendWhere(sel, Five.Music.Songs._ID + '=' + uri.getLastPathSegment());
				break;

			case SONGS:
				custom = sel;
				break;

			default:
				throw new IllegalArgumentException();
		}

		int ret = db.update(Five.Music.Songs.SQL.TABLE, v, custom, selArgs);

		return ret;
	}

	private int updateAlbum(SQLiteDatabase db, Uri uri, URIPatternIds type, ContentValues v,
	  String sel, String[] selArgs)
	{
		String custom;

		custom = extendWhere(sel, Five.Music.Albums._ID + '=' + uri.getLastPathSegment());

		int ret = db.update(Five.Music.Albums.SQL.TABLE, v, custom, selArgs);

		return ret;
	}

	private int updateArtist(SQLiteDatabase db, Uri uri, URIPatternIds type, ContentValues v,
	  String sel, String[] selArgs)
	{
		String custom;

		custom = extendWhere(sel, Five.Music.Artists._ID + '=' + uri.getLastPathSegment());

		int ret = db.update(Five.Music.Artists.SQL.TABLE, v, custom, selArgs);

		return ret;
	}

	private int updatePlaylist(SQLiteDatabase db, Uri uri, URIPatternIds type, ContentValues v,
	  String sel, String[] selArgs)
	{
		String custom;

		custom = extendWhere(sel, Five.Music.Playlists._ID + '=' + uri.getLastPathSegment());

		int ret = db.update(Five.Music.Playlists.SQL.TABLE, v, custom, selArgs);

		return ret;
	}

	private int updateSource(SQLiteDatabase db, Uri uri, URIPatternIds type, ContentValues v,
	  String sel, String[] selArgs)
	{
		String custom;

		custom = extendWhere(sel, Five.Sources._ID + '=' + uri.getLastPathSegment());

		int ret = db.update(Five.Sources.SQL.TABLE, v, custom, selArgs);

		if (isTemporary() == false)
			getContext().getContentResolver().notifyChange(Five.Sources.CONTENT_URI, null);

		return ret;
	}

	private void updateCount(SQLiteDatabase db, String updateSQL,
	  String countsSQL)
	{
		db.beginTransaction();

		SQLiteStatement updateStmt = null;

		try {
			updateStmt = db.compileStatement(updateSQL);

			Cursor counts = db.rawQuery(countsSQL, null);

			try {
				while (counts.moveToNext() == true)
				{
					long _id = counts.getLong(0);
					long count = counts.getLong(1);

					updateStmt.bindLong(1, count);
					updateStmt.bindLong(2, _id);
					updateStmt.execute();
				}
			} finally {
				counts.close();
			}

			db.setTransactionSuccessful();
		} finally {
			if (updateStmt != null)
				updateStmt.close();

			db.endTransaction();
		}
	}

	private int updateCounts(SQLiteDatabase db, Uri uri, URIPatternIds type,
	  ContentValues v, String sel, String[] args)
	{
		db.beginTransaction();

		try {
			updateCount(db, "UPDATE music_artists SET num_songs = ? WHERE _id = ?",
			  "SELECT artist_id, COUNT(*) FROM music_songs GROUP BY artist_id");
			updateCount(db, "UPDATE music_artists SET num_albums = ? WHERE _id = ?",
			  "SELECT artist_id, COUNT(*) FROM (SELECT artist_id FROM music_songs GROUP BY artist_id, album_id) GROUP BY artist_id");
			updateCount(db, "UPDATE music_albums SET num_songs = ? WHERE _id = ?",
			  "SELECT album_id, COUNT(*) FROM music_songs GROUP BY album_id");
			updateCount(db, "UPDATE music_playlists SET num_songs = ? WHERE _id = ?",
			  "SELECT playlist_id, COUNT(*) FROM music_playlist_songs GROUP BY playlist_id");

			db.setTransactionSuccessful();
		} finally {
			db.endTransaction();
		}

		return 1;
	}

	@Override
	public int updateInternal(Uri uri, ContentValues values, String selection,
	  String[] selectionArgs)
	{
		checkWritePermission();

		SQLiteDatabase db = mHelper.getWritableDatabase();

		URIPatternIds type = URIPatternIds.get(sUriMatcher.match(uri));

		switch (type)
		{
		case SONG:
		case SONGS:
			return updateSong(db, uri, type, values, selection, selectionArgs);
		case ALBUM:
			return updateAlbum(db, uri, type, values, selection, selectionArgs);
		case ARTIST:
			return updateArtist(db, uri, type, values, selection, selectionArgs);
		case PLAYLIST:
			return updatePlaylist(db, uri, type, values, selection, selectionArgs);
		case SOURCE:
			return updateSource(db, uri, type, values, selection, selectionArgs);
		case ADJUST_COUNTS:
			return updateCounts(db, uri, type, values, selection, selectionArgs);
		default:
			throw new IllegalArgumentException("Cannot update URI: " + uri);
		}
	}

	/*-***********************************************************************/

	private Uri insertSource(SQLiteDatabase db, Uri uri, URIPatternIds type, ContentValues v)
	{
		if (v.containsKey(Five.Sources.HOST) == false)
			throw new IllegalArgumentException("HOST cannot be NULL");

		if (v.containsKey(Five.Sources.PORT) == false)
			throw new IllegalArgumentException("PORT cannot be NULL");

		if (v.containsKey(Five.Sources.LAST_SYNC_TIME) == false)
			v.put(Five.Sources.LAST_SYNC_TIME, 0);

		long id = db.insert(Five.Sources.SQL.TABLE, Five.Sources.HOST, v);

		if (id == -1)
			return null;

		Uri ret = ContentUris.withAppendedId(Five.Sources.CONTENT_URI, id);

		if (isTemporary() == false)
			getContext().getContentResolver().notifyChange(Five.Sources.CONTENT_URI, null);

		return ret;
	}

	private boolean adjustNameWithPrefix(ContentValues v)
	{
		String name = v.getAsString(Five.Music.Artists.NAME);

		if (name.startsWith("The ") == true)
		{
			v.put(Five.Music.Artists.NAME, name.substring(4));
			v.put(Five.Music.Artists.NAME_PREFIX, "The ");

			return true;
		}

		return false;
	}

	private Uri insertArtist(SQLiteDatabase db, Uri uri, URIPatternIds type, ContentValues v)
	{
		if (v.containsKey(Five.Music.Artists.NAME) == false)
			throw new IllegalArgumentException("NAME cannot be NULL");

		if (v.containsKey(Five.Music.Artists.NUM_ALBUMS) == false)
			v.put(Five.Music.Artists.NUM_ALBUMS, 0);

		if (v.containsKey(Five.Music.Artists.NUM_SONGS) == false)
			v.put(Five.Music.Artists.NUM_SONGS, 0);

		adjustNameWithPrefix(v);

		long id = mArtistInserter.insert(v);

		if (id == -1)
			return null;

		Uri ret = ContentUris.withAppendedId(Five.Music.Artists.CONTENT_URI, id);
		return ret;
	}

	private Uri insertAlbum(SQLiteDatabase db, Uri uri, URIPatternIds type, ContentValues v)
	{
		if (v.containsKey(Five.Music.Albums.NAME) == false)
			throw new IllegalArgumentException("NAME cannot be NULL");

		if (v.containsKey(Five.Music.Albums.ARTIST_ID) == false)
			throw new IllegalArgumentException("ARTIST_ID cannot be NULL");

		if (v.containsKey(Five.Music.Albums.NUM_SONGS) == false)
			v.put(Five.Music.Albums.NUM_SONGS, 0);

		adjustNameWithPrefix(v);

		long id = mAlbumInserter.insert(v);

		if (id == -1)
			return null;

		Uri ret = ContentUris.withAppendedId(Five.Music.Albums.CONTENT_URI, id);

		return ret;
	}

	private Uri insertSong(SQLiteDatabase db, Uri uri, URIPatternIds type, ContentValues v)
	{
		if (v.containsKey(Five.Music.Albums.ARTIST_ID) == false)
			throw new IllegalArgumentException("ARTIST_ID cannot be NULL");

		long id = mSongInserter.insert(v);

		if (id == -1)
			return null;

		Uri ret = ContentUris.withAppendedId(Five.Music.Songs.CONTENT_URI, id);

		return ret;
	}

	private Uri insertPlaylist(SQLiteDatabase db, Uri uri, URIPatternIds type, ContentValues v)
	{
		if (v.containsKey(Five.Music.Playlists.NAME) == false)
			throw new IllegalArgumentException("NAME cannot be NULL");

		if (v.containsKey(Five.Music.Playlists.NUM_SONGS) == false)
			v.put(Five.Music.Playlists.NUM_SONGS, 0);

		long id = db.insert(Five.Music.Playlists.SQL.TABLE,
		  Five.Music.Playlists.NAME, v);

		if (id == -1)
			return null;

		Uri playlistUri = ContentUris
		  .withAppendedId(Five.Music.Playlists.CONTENT_URI, id);

		return playlistUri;
	}

	private Uri insertPlaylistSongs(SQLiteDatabase db, Uri uri, URIPatternIds type, ContentValues v)
	{
		/* TODO: Maybe lack of POSITION means append? */
		if (v.containsKey(Five.Music.PlaylistSongs.POSITION) == false)
			throw new IllegalArgumentException("POSITION cannot be NULL");

		if (v.containsKey(Five.Music.PlaylistSongs.SONG_ID) == false)
			throw new IllegalArgumentException("SONG_ID cannot be NULL");

		if (type == URIPatternIds.SONGS_IN_PLAYLIST)
		{
			v.put(Five.Music.PlaylistSongs.PLAYLIST_ID,
				getSecondToLastPathSegment(uri));
		}

		if (v.containsKey(Five.Music.PlaylistSongs.PLAYLIST_ID) == false)
			throw new IllegalArgumentException("PLAYLIST_ID cannot be NULL");

		/* TODO: Check that the inserted POSITION doesn't require that we
		 * reposition other songs. */
		db.insert(Five.Music.PlaylistSongs.SQL.TABLE,
		  Five.Music.PlaylistSongs.PLAYLIST_ID, v);

		Uri playlistSongUri = uri.buildUpon()
		  .appendEncodedPath(v.getAsString(Five.Music.PlaylistSongs.POSITION))
		  .build();

		return playlistSongUri;
	}

	private Uri insertDeletedItem(SQLiteDatabase db, Uri uri, URIPatternIds type, ContentValues v)
	{
		InsertHelper inserter;

		switch (type) {
			case DELETED_ARTIST: inserter = mDeletedArtistInserter; break;
			case DELETED_ALBUM: inserter = mDeletedAlbumInserter; break;
			case DELETED_SONG: inserter = mDeletedSongInserter; break;
			case DELETED_PLAYLIST: inserter = mDeletedPlaylistInserter; break;
			case DELETED_PLAYLIST_SONG: inserter = mDeletedPlaylistSongInserter; break;
			default:
				throw new IllegalArgumentException("Unknown URI: " + uri);
		}

		long id = inserter.insert(v);

		if (id == -1)
			return null;

		return ContentUris.withAppendedId(uri, id);
	}

	@Override
	public Uri insertInternal(Uri uri, ContentValues values)
	{
		checkWritePermission();

		SQLiteDatabase db = mHelper.getWritableDatabase();

		URIPatternIds type = URIPatternIds.get(sUriMatcher.match(uri));

		switch (type)
		{
		case SOURCES:
			return insertSource(db, uri, type, values);
		case ARTISTS:
			return insertArtist(db, uri, type, values);
		case ALBUMS:
			return insertAlbum(db, uri, type, values);
		case SONGS:
			return insertSong(db, uri, type, values);
		case PLAYLISTS:
			return insertPlaylist(db, uri, type, values);
		case SONGS_IN_PLAYLIST:
		case PLAYLIST_SONGS:
			return insertPlaylistSongs(db, uri, type, values);
		case DELETED_ARTIST:
		case DELETED_ALBUM:
		case DELETED_SONG:
		case DELETED_PLAYLIST:
		case DELETED_PLAYLIST_SONG:
			return insertDeletedItem(db, uri, type, values);
		}

		throw new IllegalArgumentException("Cannot insert URI: " + uri);
	}

	/*-***********************************************************************/

	private static String extendWhere(String old, String[] add)
	{
		StringBuilder ret = new StringBuilder();

		int length = add.length;

		if (length > 0)
		{
			ret.append("(" + add[0] + ")");

			for (int i = 1; i < length; i++)
			{
				ret.append(" AND (");
				ret.append(add[i]);
				ret.append(')');
			}
		}

		if (TextUtils.isEmpty(old) == false)
			ret.append(" AND (").append(old).append(')');

		return ret.toString();
	}

	private static String extendWhere(String old, String add)
	{
		return extendWhere(old, new String[] { add });
	}

	private int deleteSources(SQLiteDatabase db, Uri uri, URIPatternIds type,
	  String selection, String[] selectionArgs)
	{
		String custom;
		int count;

		switch (type)
		{
		case SOURCES:
			custom = selection;
			break;

		case SOURCE:
			StringBuilder where = new StringBuilder();
			where.append(Five.Sources._ID).append('=').append(uri.getLastPathSegment());

			if (TextUtils.isEmpty(selection) == false)
				where.append(" AND (").append(selection).append(')');

			custom = where.toString();
			break;

		default:
			throw new IllegalArgumentException("Cannot delete source URI: " + uri);
		}

		count = db.delete(Five.Sources.SQL.TABLE, custom, selectionArgs);

		if (isTemporary() == false)
			getContext().getContentResolver().notifyChange(Five.Sources.CONTENT_URI, null);

		return count;
	}

	private void assertNoSelection(String selection, String[] selectionArgs)
	{
		if (selection != null || selectionArgs != null)
			throw new IllegalArgumentException();
	}

	private int deleteArtist(SQLiteDatabase db, Uri uri, URIPatternIds type,
			String selection, String[] selectionArgs)
	{
		assertNoSelection(selection, selectionArgs);

		long artistId = ContentUris.parseId(uri);

		int count = db.delete(Five.Music.Artists.SQL.TABLE,
				Five.Music.Artists._ID + " = " + artistId, null);

		try {
			if (count > 0)
				getArtistPhoto(artistId, false).delete();
		} catch (FileNotFoundException e) {
			if (Constants.DEBUG)
				Log.d(TAG, "Unexpected sdcard error: " + e.toString());
		}

		return count;
	}

	private int deleteAlbum(SQLiteDatabase db, Uri uri, URIPatternIds type,
			String selection, String[] selectionArgs)
	{
		assertNoSelection(selection, selectionArgs);

		long albumId = ContentUris.parseId(uri);

		int count = db.delete(Five.Music.Albums.SQL.TABLE,
				Five.Music.Albums._ID + " = " + albumId, null);

		try {
			if (count > 0)
			{
				getAlbumArtwork(albumId, false).delete();
				getLargeAlbumArtwork(albumId, false).delete();
			}
		} catch (FileNotFoundException e) {
			if (Constants.DEBUG)
				Log.d(TAG, "Unexpected sdcard error: " + e.toString());
		}

		return count;
	}

	private int deleteSong(SQLiteDatabase db, Uri uri, URIPatternIds type,
			String selection, String[] selectionArgs)
	{
		assertNoSelection(selection, selectionArgs);

		long songId = ContentUris.parseId(uri);
		String queryForSongId = Five.Music.Songs._ID + " = " + songId;

		SongItem item = SongItem.getInstance(db.query(Five.Music.Songs.SQL.TABLE, null,
				queryForSongId, null, null, null, null));
		String cachePath = null;
		if (item != null)
		{
			try {
				cachePath = item.getCachePath();
			} finally {
				item.close();
			}
		}

		int count = db.delete(Five.Music.Songs.SQL.TABLE, queryForSongId, null);

		if (count > 0)
			new File(cachePath).delete();

		return count;
	}

	private int deletePlaylist(SQLiteDatabase db, Uri uri, URIPatternIds type,
			String selection, String[] selectionArgs)
	{
		assertNoSelection(selection, selectionArgs);
		return db.delete(Five.Music.Playlists.SQL.TABLE,
				Five.Music.Playlists._ID + " = " + ContentUris.parseId(uri), null);
	}

	private int deletePlaylistSong(SQLiteDatabase db, Uri uri, URIPatternIds type,
			String selection, String[] selectionArgs)
	{
		assertNoSelection(selection, selectionArgs);
		return db.delete(Five.Music.PlaylistSongs.SQL.TABLE,
				Five.Music.PlaylistSongs._ID + " = " + ContentUris.parseId(uri), null);
	}

	@Override
	public int deleteInternal(Uri uri, String selection, String[] selectionArgs)
	{
		checkWritePermission();

		SQLiteDatabase db = mHelper.getWritableDatabase();

		URIPatternIds type = URIPatternIds.get(sUriMatcher.match(uri));

		switch (type)
		{
		case SOURCES:
		case SOURCE:
			return deleteSources(db, uri, type, selection, selectionArgs);
		case ARTIST:
			return deleteArtist(db, uri, type, selection, selectionArgs);
		case ALBUM:
			return deleteAlbum(db, uri, type, selection, selectionArgs);
		case SONG:
			return deleteSong(db, uri, type, selection, selectionArgs);
		case PLAYLIST:
			return deletePlaylist(db, uri, type, selection, selectionArgs);
		case PLAYLIST_SONG:
			return deletePlaylistSong(db, uri, type, selection, selectionArgs);
		default:
			throw new IllegalArgumentException("Cannot delete URI: " + uri);
		}
	}

	/*-***********************************************************************/

	@Override
	public String getType(Uri uri)
	{
		switch (URIPatternIds.get(sUriMatcher.match(uri)))
		{
		case SOURCES:
			return Five.Sources.CONTENT_TYPE;
		case SOURCE:
			return Five.Sources.CONTENT_ITEM_TYPE;
		case ARTISTS:
			return Five.Music.Artists.CONTENT_TYPE;
		case ARTIST:
			return Five.Music.Artists.CONTENT_ITEM_TYPE;
		case ALBUMS:
		case ALBUMS_BY_ARTIST:
			return Five.Music.Albums.CONTENT_TYPE;
		case ALBUM:
			return Five.Music.Albums.CONTENT_ITEM_TYPE;
		case SONGS:
		case SONGS_BY_ALBUM:
		case SONGS_BY_ARTIST:
			return Five.Music.Songs.CONTENT_TYPE;
		case SONG:
			return Five.Music.Songs.CONTENT_ITEM_TYPE;
		default:
			throw new IllegalArgumentException("Unknown URI: " + uri);
		}
	}

	/*-***********************************************************************/

	static
	{
		sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

		sUriMatcher.addURI(Five.AUTHORITY, "sources", URIPatternIds.SOURCES.ordinal());
		sUriMatcher.addURI(Five.AUTHORITY, "sources/#", URIPatternIds.SOURCE.ordinal());

		sUriMatcher.addURI(Five.AUTHORITY, "media/music/artists", URIPatternIds.ARTISTS.ordinal());
		sUriMatcher.addURI(Five.AUTHORITY, "media/music/artists/#", URIPatternIds.ARTIST.ordinal());
		sUriMatcher.addURI(Five.AUTHORITY, "media/music/artists/#/albums", URIPatternIds.ALBUMS_WITH_ARTIST.ordinal());
		sUriMatcher.addURI(Five.AUTHORITY, "media/music/artists/#/albums/#/songs", URIPatternIds.SONGS_BY_ARTIST_ON_ALBUM.ordinal());
		sUriMatcher.addURI(Five.AUTHORITY, "media/music/artists/#/songs", URIPatternIds.SONGS_BY_ARTIST.ordinal());
		sUriMatcher.addURI(Five.AUTHORITY, "media/music/artists/#/photo", URIPatternIds.ARTIST_PHOTO.ordinal());
		sUriMatcher.addURI(Five.AUTHORITY, "media/music/artists/deleted", URIPatternIds.DELETED_ARTIST.ordinal());

		sUriMatcher.addURI(Five.AUTHORITY, "media/music/albums", URIPatternIds.ALBUMS.ordinal());
		sUriMatcher.addURI(Five.AUTHORITY, "media/music/albums/complete", URIPatternIds.ALBUMS_COMPLETE.ordinal());
		sUriMatcher.addURI(Five.AUTHORITY, "media/music/albums/#", URIPatternIds.ALBUM.ordinal());
		sUriMatcher.addURI(Five.AUTHORITY, "media/music/albums/#/songs", URIPatternIds.SONGS_BY_ALBUM.ordinal());
		sUriMatcher.addURI(Five.AUTHORITY, "media/music/albums/#/artwork", URIPatternIds.ALBUM_ARTWORK.ordinal());
		sUriMatcher.addURI(Five.AUTHORITY, "media/music/albums/#/artwork/big", URIPatternIds.ALBUM_ARTWORK_BIG.ordinal());
		sUriMatcher.addURI(Five.AUTHORITY, "media/music/albums/deleted", URIPatternIds.DELETED_ALBUM.ordinal());

		sUriMatcher.addURI(Five.AUTHORITY, "media/music/songs", URIPatternIds.SONGS.ordinal());
		sUriMatcher.addURI(Five.AUTHORITY, "media/music/songs/#", URIPatternIds.SONG.ordinal());
		sUriMatcher.addURI(Five.AUTHORITY, "media/music/songs/deleted", URIPatternIds.DELETED_SONG.ordinal());

		sUriMatcher.addURI(Five.AUTHORITY, "media/music/playlists", URIPatternIds.PLAYLISTS.ordinal());
		sUriMatcher.addURI(Five.AUTHORITY, "media/music/playlists/#", URIPatternIds.PLAYLIST.ordinal());
		sUriMatcher.addURI(Five.AUTHORITY, "media/music/playlists/deleted", URIPatternIds.DELETED_PLAYLIST.ordinal());
		sUriMatcher.addURI(Five.AUTHORITY, "media/music/playlists/#/songs", URIPatternIds.SONGS_IN_PLAYLIST.ordinal());
		sUriMatcher.addURI(Five.AUTHORITY, "media/music/playlists/#/song/#", URIPatternIds.SONG_IN_PLAYLIST.ordinal());
		sUriMatcher.addURI(Five.AUTHORITY, "media/music/playlists/songs", URIPatternIds.PLAYLIST_SONGS.ordinal());
		sUriMatcher.addURI(Five.AUTHORITY, "media/music/playlists/songs/#", URIPatternIds.PLAYLIST_SONG.ordinal());
		sUriMatcher.addURI(Five.AUTHORITY, "media/music/playlists/songs/deleted", URIPatternIds.DELETED_PLAYLIST_SONG.ordinal());

		sUriMatcher.addURI(Five.AUTHORITY, "media/music/adjust_counts", URIPatternIds.ADJUST_COUNTS.ordinal());

		sArtistsMap = new HashMap<String, String>();
		sArtistsMap.put(Five.Music.Artists.MBID, Five.Music.Artists.MBID);
		sArtistsMap.put(Five.Music.Artists._ID, Five.Music.Artists._ID);
		sArtistsMap.put(Five.Music.Artists._SYNC_ID, Five.Music.Artists._SYNC_ID);
		sArtistsMap.put(Five.Music.Artists._SYNC_TIME, Five.Music.Artists._SYNC_TIME);
		sArtistsMap.put(Five.Music.Artists.DISCOVERY_DATE, Five.Music.Artists.DISCOVERY_DATE);
		sArtistsMap.put(Five.Music.Artists.GENRE, Five.Music.Artists.GENRE);
		sArtistsMap.put(Five.Music.Artists.NAME, Five.Music.Artists.NAME);
		sArtistsMap.put(Five.Music.Artists.NAME_PREFIX, Five.Music.Artists.NAME_PREFIX);
		sArtistsMap.put(Five.Music.Artists.FULL_NAME, "IFNULL(" + Five.Music.Artists.NAME_PREFIX + ", \"\") || " + Five.Music.Artists.NAME + " AS " + Five.Music.Artists.FULL_NAME);
		sArtistsMap.put(Five.Music.Artists.PHOTO, Five.Music.Artists.PHOTO);
		sArtistsMap.put(Five.Music.Artists.NUM_ALBUMS, Five.Music.Artists.NUM_ALBUMS);
		sArtistsMap.put(Five.Music.Artists.NUM_SONGS, Five.Music.Artists.NUM_SONGS);

		sAlbumsMap = new HashMap<String, String>();
		sAlbumsMap.put(Five.Music.Albums._ID, "a." + Five.Music.Albums._ID + " AS " + Five.Music.Albums._ID);
		sAlbumsMap.put(Five.Music.Albums._SYNC_ID, "a." + Five.Music.Albums._SYNC_ID + " AS " + Five.Music.Albums._SYNC_ID);
		sAlbumsMap.put(Five.Music.Albums._SYNC_TIME, "a." + Five.Music.Albums._SYNC_TIME + " AS " + Five.Music.Albums._SYNC_TIME);
		sAlbumsMap.put(Five.Music.Albums.MBID, "a." + Five.Music.Albums.MBID + " AS " + Five.Music.Albums.MBID);
		sAlbumsMap.put(Five.Music.Albums.ARTIST_ID, "a." + Five.Music.Albums.ARTIST_ID + " AS " + Five.Music.Albums.ARTIST_ID);
		sAlbumsMap.put(Five.Music.Albums.ARTIST, "artists." + Five.Music.Artists.NAME + " AS " + Five.Music.Albums.ARTIST);
		sAlbumsMap.put(Five.Music.Albums.ARTWORK, "a." + Five.Music.Albums.ARTWORK + " AS " + Five.Music.Albums.ARTWORK);
		sAlbumsMap.put(Five.Music.Albums.ARTWORK_BIG, "a." + Five.Music.Albums.ARTWORK_BIG + " AS " + Five.Music.Albums.ARTWORK_BIG);
		sAlbumsMap.put(Five.Music.Albums.DISCOVERY_DATE, "a." + Five.Music.Albums.DISCOVERY_DATE + " AS " + Five.Music.Albums.DISCOVERY_DATE);
		sAlbumsMap.put(Five.Music.Albums.NAME, "a." + Five.Music.Albums.NAME + " AS " + Five.Music.Albums.NAME);
		sAlbumsMap.put(Five.Music.Albums.NAME_PREFIX, "a." + Five.Music.Albums.NAME_PREFIX + " AS " + Five.Music.Albums.NAME_PREFIX);
		sAlbumsMap.put(Five.Music.Albums.FULL_NAME, "IFNULL(a." + Five.Music.Albums.NAME_PREFIX + ", \"\") || a." + Five.Music.Albums.NAME + " AS " + Five.Music.Albums.FULL_NAME);
		sAlbumsMap.put(Five.Music.Albums.RELEASE_DATE, "a." + Five.Music.Albums.RELEASE_DATE + " AS " + Five.Music.Albums.RELEASE_DATE);
		sAlbumsMap.put(Five.Music.Albums.NUM_SONGS, "a." + Five.Music.Albums.NUM_SONGS + " AS " + Five.Music.Albums.NUM_SONGS);

		sSongsMap = new HashMap<String, String>();
		sSongsMap.put(Five.Music.Songs._ID, "s." + Five.Music.Songs._ID + " AS " + Five.Music.Songs._ID);
		sSongsMap.put(Five.Music.Songs._SYNC_ID, "s." + Five.Music.Songs._SYNC_ID + " AS " + Five.Music.Songs._SYNC_ID);
		sSongsMap.put(Five.Music.Songs._SYNC_TIME, "s." + Five.Music.Songs._SYNC_TIME + " AS " + Five.Music.Songs._SYNC_TIME);
		sSongsMap.put(Five.Music.Songs.MBID, "s." + Five.Music.Songs.MBID + " AS " + Five.Music.Songs.MBID);
		sSongsMap.put(Five.Music.Songs.TITLE, "s." + Five.Music.Songs.TITLE + " AS " + Five.Music.Songs.TITLE);
		sSongsMap.put(Five.Music.Songs.ALBUM, "s." + Five.Music.Songs.ALBUM + " AS " + Five.Music.Songs.ALBUM);
		sSongsMap.put(Five.Music.Songs.ALBUM_ID, "s." + Five.Music.Songs.ALBUM_ID + " AS " + Five.Music.Songs.ALBUM_ID);
		sSongsMap.put(Five.Music.Songs.ARTIST, "s." + Five.Music.Songs.ARTIST + " AS " + Five.Music.Songs.ARTIST);
		sSongsMap.put(Five.Music.Songs.ARTIST_ID, "s." + Five.Music.Songs.ARTIST_ID + " AS " + Five.Music.Songs.ARTIST_ID);
		sSongsMap.put(Five.Music.Songs.LENGTH, "s." + Five.Music.Songs.LENGTH + " AS " + Five.Music.Songs.LENGTH);
		sSongsMap.put(Five.Music.Songs.TRACK, "s." + Five.Music.Songs.TRACK + " AS " + Five.Music.Songs.TRACK);
		sSongsMap.put(Five.Music.Songs.SET, "s." + Five.Music.Songs.SET + " AS " + Five.Music.Songs.SET);
		sSongsMap.put(Five.Music.Songs.GENRE, "s." + Five.Music.Songs.GENRE + " AS " + Five.Music.Songs.GENRE);
		sSongsMap.put(Five.Music.Songs.DISCOVERY_DATE, "s." + Five.Music.Songs.DISCOVERY_DATE + " AS " + Five.Music.Songs.DISCOVERY_DATE);
	}
}
