package jp.juggler.subwaytooter.table;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import jp.juggler.subwaytooter.App1;
import jp.juggler.subwaytooter.util.LogCategory;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;

import java.util.Locale;

public class AcctColor {
	
	private static final LogCategory log = new LogCategory( "AcctColor" );
	
	private static final String table = "acct_color";
	private static final String COL_TIME_SAVE = "time_save";
	private static final String COL_ACCT = "ac"; //@who@host ascii文字の大文字小文字は(sqliteにより)同一視される
	private static final String COL_COLOR_FG = "cf"; // 未設定なら0、それ以外は色
	private static final String COL_COLOR_BG = "cb"; // 未設定なら0、それ以外は色
	private static final String COL_NICKNAME = "nick"; // 未設定ならnullか空文字列
	
	public static void onDBCreate( SQLiteDatabase db ){
		log.d( "onDBCreate!" );
		db.execSQL(
			"create table if not exists " + table
				+ "(_id INTEGER PRIMARY KEY"
				+ "," + COL_TIME_SAVE + " integer not null"
				+ "," + COL_ACCT + " text not null"
				+ "," + COL_COLOR_FG + " integer"
				+ "," + COL_COLOR_BG + " integer"
				+ "," + COL_NICKNAME + " text "
				+ ")"
		);
		db.execSQL(
			"create unique index if not exists " + table + "_acct on " + table + "(" + COL_ACCT + ")"
		);
		db.execSQL(
			"create index if not exists " + table + "_time on " + table + "(" + COL_TIME_SAVE + ")"
		);
	}
	
	public static void onDBUpgrade( SQLiteDatabase db, int oldVersion, int newVersion ){
		if( oldVersion < 9 && newVersion >= 9 ){
			onDBCreate( db );
		}
	}
	
	public String acct;
	public int color_fg;
	public int color_bg;
	public String nickname;
	
	private AcctColor(){
	}
	
	public AcctColor( String acct, String nickname, int color_fg, int color_bg ){
		this.acct = acct;
		this.nickname = nickname;
		this.color_fg = color_fg;
		this.color_bg = color_bg;
	}
	
	public void save( long now ){
		try{
			ContentValues cv = new ContentValues();
			cv.put( COL_TIME_SAVE, now );
			cv.put( COL_ACCT, acct.toLowerCase( Locale.ENGLISH ) );
			cv.put( COL_COLOR_FG, color_fg );
			cv.put( COL_COLOR_BG, color_bg );
			cv.put( COL_NICKNAME, nickname == null ? "" : nickname );
			App1.getDB().replace( table, null, cv );
			
		}catch( Throwable ex ){
			ex.printStackTrace();
			log.e( ex, "save failed." );
		}
	}
	
	private static final String load_where = COL_ACCT + "=?";
	
	private static final ThreadLocal< String[] > load_where_arg = new ThreadLocal< String[] >() {
		@Override protected String[] initialValue(){
			return new String[ 1 ];
		}
	};
	
	@Nullable public static AcctColor load( @NonNull String acct ){
		try{
			String[] where_arg = load_where_arg.get();
			where_arg[ 0 ] = acct.toLowerCase( Locale.ENGLISH );
			Cursor cursor = App1.getDB().query( table, null, load_where, where_arg, null, null, null );
			if( cursor != null ){
				try{
					if( cursor.moveToNext() ){
						AcctColor dst = new AcctColor();
						int idx;
						
						idx = cursor.getColumnIndex( COL_ACCT );
						dst.acct = cursor.getString( idx );
						
						idx = cursor.getColumnIndex( COL_COLOR_FG );
						dst.color_fg = cursor.isNull( idx ) ? 0 : cursor.getInt( idx );
						
						idx = cursor.getColumnIndex( COL_COLOR_BG );
						dst.color_bg = cursor.isNull( idx ) ? 0 : cursor.getInt( idx );
						
						idx = cursor.getColumnIndex( COL_NICKNAME );
						dst.nickname = cursor.isNull( idx ) ? null : cursor.getString( idx );
						
						return dst;
					}
				}finally{
					cursor.close();
				}
			}
		}catch( Throwable ex ){
			ex.printStackTrace();
			log.e( ex, "load failed." );
		}
		return null;
	}
	
	public static String getNickname( String acct ){
		AcctColor ac = load( acct );
		return ac != null && ! TextUtils.isEmpty( ac.nickname ) ? ac.nickname : acct;
	}
	
	public static boolean hasNickname( AcctColor ac ){
		return ac != null && !TextUtils.isEmpty( ac.nickname );
	}
	
	public static boolean hasColorForeground( AcctColor ac ){
		return ac != null && ac.color_fg != 0;
	}
	
	public static boolean hasColorBackground( AcctColor ac ){
		return ac != null && ac.color_bg != 0;
	}
}