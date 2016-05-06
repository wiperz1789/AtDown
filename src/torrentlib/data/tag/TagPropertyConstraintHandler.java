/*
 * Created on Sep 4, 2013
 * Created by Paul Gardner
 * 
 * Copyright 2013 Azureus Software, Inc.  All rights reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */


package torrentlib.data.tag;

import java.util.*;
import java.util.regex.Pattern;

import xfer.download.DownloadManagerState;
import torrentlib.data.torrent.TOTorrent;
import torrentlib.AENetworkClassifier;
import torrentlib.AERunnable;
import torrentlib.AsyncDispatcher;
import torrentlib.Debug;
import torrentlib.SimpleTimer;
import torrentlib.SystemTime;
import torrentlib.TimerEvent;
import torrentlib.TimerEventPerformer;
import torrentlib.TimerEventPeriodic;
import plugins.download.Download;
import plugins.utils.ScriptProvider;
import pluginsimpl.PluginUtils;
import pluginsimpl.local.PluginCoreUtils;

import torrentlib.AzureusCoreFactory;
import torrentlib.AzureusCoreRunningListener;
import torrentlib.data.tag.Tag;
import torrentlib.data.tag.TagFeatureProperties;
import torrentlib.data.tag.TagFeatureProperties.TagProperty;
import torrentlib.data.tag.TagFeatureProperties.TagPropertyListener;
import torrentlib.data.tag.TagListener;
import torrentlib.data.tag.TagType;
import torrentlib.data.tag.TagTypeListener;
import torrentlib.data.tag.Taggable;
import torrentlib.data.tag.TaggableLifecycleAdapter;
import xfer.download.DownloadManager;
import torrentlib.TorrentEngineCore;

public class 
TagPropertyConstraintHandler 
	implements TagTypeListener
{
	private final TorrentEngineCore		azureus_core;
	private final TagManagerImpl	tag_manager;
		
	private boolean		initialised;
	private boolean 	initial_assignment_complete;
	
	private Map<Tag,TagConstraint>	constrained_tags 	= new HashMap<Tag,TagConstraint>();
	
	private Map<Tag,Map<DownloadManager,Long>>			apply_history 		= new HashMap<Tag, Map<DownloadManager,Long>>();
	
	private AsyncDispatcher	dispatcher = new AsyncDispatcher( "tag:constraints" );
	
	private TimerEventPeriodic		timer;
		
	private
	TagPropertyConstraintHandler()
	{
		azureus_core	= null;
		tag_manager		= null;
	}

	protected
	TagPropertyConstraintHandler(
		TorrentEngineCore		_core,
		TagManagerImpl	_tm )
	{
		azureus_core	= _core;
		tag_manager		= _tm;
		
		tag_manager.addTaggableLifecycleListener(Taggable.TT_DOWNLOAD,
			new TaggableLifecycleAdapter()
			{
				public void
				initialised(
					List<Taggable>	current_taggables )
				{
					try{
						TagType tt = tag_manager.getTagType( TagType.TT_DOWNLOAD_MANUAL );
						
						tt.addTagTypeListener( TagPropertyConstraintHandler.this, true );

					}finally{
						
						AzureusCoreFactory.addCoreRunningListener(new AzureusCoreRunningListener()
							{	
								public void 
								azureusCoreRunning(
									TorrentEngineCore core )
								{
									synchronized( constrained_tags ){
																				
										initialised = true;

										apply( core.getGlobalManager().getDownloadManagers(), true );
									}
								}
							});
					}
				}
				
				public void
				taggableCreated(
					Taggable		taggable )
				{
					apply((DownloadManager)taggable, null, false );
				}
			});
	}
	
	public void
	tagTypeChanged(
		TagType		tag_type )
	{
	}
	
	public void
	tagAdded(
		Tag			tag )
	{
		TagFeatureProperties tfp = (TagFeatureProperties)tag;
		
		TagProperty prop = tfp.getProperty( TagFeatureProperties.PR_CONSTRAINT );
		
		if ( prop != null ){
			
			prop.addListener(
				new TagPropertyListener() 
				{
					public void
					propertyChanged(
						TagProperty		property )
					{		
						handleProperty( property );
					}
					
					public void
					propertySync(
						TagProperty		property )
					{	
					}
				});
			
			handleProperty( prop );
		}
		
		tag.addTagListener(new TagListener() 
			{	
				public void 
				taggableSync(
					Tag tag ) 
				{
				}
				
				public void 
				taggableRemoved(
					Tag 		tag, 
					Taggable 	tagged ) 
				{
					apply((DownloadManager)tagged, tag, true );
				}
				
				public void 
				taggableAdded(
					Tag 		tag,
					Taggable 	tagged ) 
				{
					apply((DownloadManager)tagged, tag, true );
				}
			}, false );
	}
	
	public void
	tagChanged(
		Tag			tag )
	{
	}
	
	private void
	checkTimer()
	{
		if ( constrained_tags.size() > 0 ){
			
			if ( timer == null ){
				
				timer = 
					SimpleTimer.addPeriodicEvent(
						"tag:constraint:timer",
						30*1000,
						new TimerEventPerformer() {
							
							public void 
							perform(
								TimerEvent event) 
							{
								apply_history.clear();
								
								apply();
							}
						});
			}
			
		}else if ( timer != null ){
			
			timer.cancel();
			
			timer = null;
			
			apply_history.clear();
		}
	}
	
	public void
	tagRemoved(
		Tag			tag )
	{
		synchronized( constrained_tags ){
			
			if ( constrained_tags.containsKey( tag )){
				
				constrained_tags.remove( tag );
				
				checkTimer();
			}
		}
	}
	
	private void
	handleProperty(
		TagProperty		property )
	{
		Tag	tag = property.getTag();
				
		synchronized( constrained_tags ){
		
			String[] temp = property.getStringList();
			
			String constraint = temp == null || temp.length < 1?"":temp[0].trim();
						
			if ( constraint.length() == 0 ){
				
				if ( constrained_tags.containsKey( tag )){
					
					constrained_tags.remove( tag );
				}
			}else{
				
				TagConstraint con = constrained_tags.get( tag );
				
				if ( con != null && con.getConstraint().equals( constraint )){
					
					return;
				}
									
				Set<Taggable> existing = tag.getTagged();
					
				for ( Taggable e: existing ){
						
					tag.removeTaggable( e );
				}
			
				con = new TagConstraint( this, tag, constraint );
				
				constrained_tags.put( tag, con );
								
				if ( initialised ){
				
					apply( con );
				}
			}
			
			checkTimer();
		}
	}
	
	private void
	apply(
		final DownloadManager				dm,
		Tag									related_tag,
		boolean								auto )
	{
		if ( dm.isDestroyed()){
			
			return;
		}
		
		synchronized( constrained_tags ){
			
			if ( constrained_tags.size() == 0 || !initialised ){
				
				return;
			}
			
			if ( auto && !initial_assignment_complete ){
				
				return;
			}
		}
				
		dispatcher.dispatch(
			new AERunnable() 
			{
				public void 
				runSupport() 
				{
					List<TagConstraint>	cons;
					
					synchronized( constrained_tags ){
					
						cons = new ArrayList<TagConstraint>( constrained_tags.values());
					}
					
					for ( TagConstraint con: cons ){
							
						con.apply( dm );
					}
				}
			});
	}
	
	private void
	apply(
		final List<DownloadManager>		dms,
		final boolean					initial_assignment )
	{
		synchronized( constrained_tags ){
			
			if ( constrained_tags.size() == 0 || !initialised ){
				
				return;
			}
		}
		
		dispatcher.dispatch(
			new AERunnable() 
			{
				public void 
				runSupport() 
				{
					List<TagConstraint>	cons;
					
					synchronized( constrained_tags ){
					
						cons = new ArrayList<TagConstraint>( constrained_tags.values());
					}
						
						// set up initial constraint tagged state without following implications
					
					for ( TagConstraint con: cons ){
						
						con.apply( dms );
					}
						
					if ( initial_assignment ){
						
						synchronized( constrained_tags ){
						
							initial_assignment_complete = true;
						}
					
							// go over them one more time to pick up consequential constraints
						
						for ( TagConstraint con: cons ){
							
							con.apply( dms );
						}
					}
				}
			});
	}
	
	private void
	apply(
		final TagConstraint		constraint )
	{
		synchronized( constrained_tags ){
			
			if ( !initialised ){
				
				return;
			}
		}
		
		dispatcher.dispatch(new AERunnable() 
			{
				public void 
				runSupport() 
				{
					List<DownloadManager> dms = azureus_core.getGlobalManager().getDownloadManagers();

					constraint.apply( dms );
				}
			});
	}
	
	private void
	apply()
	{
		synchronized( constrained_tags ){
			
			if ( constrained_tags.size() == 0 || !initialised ){
				
				return;
			}
		}
		
		dispatcher.dispatch(new AERunnable() 
			{
				public void 
				runSupport() 
				{
					List<DownloadManager> dms = azureus_core.getGlobalManager().getDownloadManagers();
					
					List<TagConstraint>	cons;
					
					synchronized( constrained_tags ){
					
						cons = new ArrayList<TagConstraint>( constrained_tags.values());
					}
					
					for ( TagConstraint con: cons ){
						
						con.apply( dms );
					}
				}
			});
	}
	
	private TagConstraint.ConstraintExpr
	compileConstraint(
		String		expr )
	{
		return( new TagConstraint( this, null, expr ).expr );
	}
	
	private static class
	TagConstraint
	{
		private TagPropertyConstraintHandler	handler;
		private Tag								tag;
		private String							constraint;
		
		private ConstraintExpr	expr;
		
		private
		TagConstraint(
			TagPropertyConstraintHandler	_handler,
			Tag								_tag,
			String							_constraint )
		{
			handler		= _handler;
			tag			= _tag;
			constraint	= _constraint;
		
			try{
				expr = compileStart( constraint, new HashMap<String,ConstraintExpr>());
				
			}catch( Throwable e ){
				
				Debug.out( "Invalid constraint: " + constraint + " - " + Debug.getNestedExceptionMessage( e ));
			}
		}
		
		private ConstraintExpr
		compileStart(
			String						str,
			Map<String,ConstraintExpr>	context )
		{		
			str = str.trim();
			
			if ( str.equalsIgnoreCase( "true" )){
				
				return( new ConstraintExprTrue());
			}
			
			char[] chars = str.toCharArray();
				
			boolean	in_quote 	= false;
				
			int	level 			= 0;
			int	bracket_start 	= 0;
			
			StringBuffer result = new StringBuffer( str.length());
			
			for ( int i=0;i<chars.length;i++){
					
				char c = chars[i];
																	
				if ( c == '"' ){
	
					if ( i == 0 || chars[i-1] != '\\' ){
						
						in_quote = !in_quote;
					}
				}
				
				if ( !in_quote ){
					
					if ( c == '(' ){
						
						level++;
						
						if ( level == 1 ){
							
							bracket_start = i+1;
						}
					}else if ( c == ')' ){
						
						level--;
						
						if ( level == 0 ){
						
							String bracket_text = new String( chars, bracket_start, i-bracket_start ).trim();
							
							if ( result.length() > 0 && Character.isLetterOrDigit( result.charAt( result.length()-1 ))){
								
									// function call
								
								String key = "{" + context.size() + "}";
								
								context.put( key, new ConstraintExprParams( bracket_text ));
																
								result.append( "(" ).append( key ).append( ")" );
								
							}else{
								
								ConstraintExpr sub_expr = compileStart( bracket_text, context );
								
								String key = "{" + context.size() + "}";
								
								context.put(key, sub_expr );
								
								result.append( key );
							}
						}
					}else if ( level == 0 ){
						
						if ( !Character.isWhitespace( c )){
						
							result.append( c );
						}
					}
				}else if ( level == 0 ){
						
					result.append( c );
					
				}
			}
			
			if ( level != 0 ){
				
				throw( new RuntimeException( "Unmatched '(' in \"" + str + "\"" ));
			}
			
			if ( in_quote ){
				
				throw( new RuntimeException( "Unmatched '\"' in \"" + str + "\"" ));
			}
			
			return( compileBasic( result.toString(), context ));
		}
		
		private ConstraintExpr
		compileBasic(
			String						str,
			Map<String,ConstraintExpr>	context )
		{	
			if ( str.startsWith( "{" )){
				
				return( context.get( str ));
				
			}else if ( str.contains( "||" )){
				
				String[] bits = str.split( "\\|\\|" );
				
				return( new ConstraintExprOr( compile( bits, context )));
				
			}else if ( str.contains( "&&" )){
				
				String[] bits = str.split( "&&" );
				
				return( new ConstraintExprAnd( compile( bits, context )));
				
			}else if ( str.contains( "^" )){
				
				String[] bits = str.split( "\\^" );
				
				return( new ConstraintExprXor( compile( bits, context )));
				
			}else if ( str.startsWith( "!" )){
				
				return( new ConstraintExprNot( compileBasic( str.substring(1).trim(), context )));
				
			}else{
				
				int	pos = str.indexOf( '(' );
				
				if ( pos > 0 && str.endsWith( ")" )){
					
					String func = str.substring( 0, pos );
					
					String key = str.substring( pos+1, str.length() - 1 ).trim();
					
					ConstraintExprParams params = (ConstraintExprParams)context.get( key );
										
					return( new ConstraintExprFunction( func, params ));

				}else{
					
					throw( new RuntimeException( "Unsupported construct: " + str ));
				}
			}
		}
		
		private ConstraintExpr[]
		compile(
			String[]					bits,
			Map<String,ConstraintExpr>	context )
		{
			ConstraintExpr[] res = new ConstraintExpr[ bits.length ];
			
			for ( int i=0; i<bits.length;i++){
				
				res[i] = compileBasic( bits[i].trim(), context );
			}
			
			return( res );
		}
		
		private Tag
		getTag()
		{
			return( tag );
		}
		
		private String
		getConstraint()
		{
			return( constraint );
		}
		
		private void
		apply(
			DownloadManager			dm )
		{
			if ( dm.isDestroyed() || !dm.isPersistent()){
				
				return;
			}

			if ( expr == null ){
				
				return;
			}
			
			Set<Taggable>	existing = tag.getTagged();
						
			if ( testConstraint( dm )){
				
				if ( !existing.contains( dm )){
					
					if( canAddTaggable( dm )){
					
						tag.addTaggable( dm );
					}
				}
			}else{
				
				if ( existing.contains( dm )){
					
					tag.removeTaggable( dm );
				}
			}
		}
		
		private void
		apply(
			List<DownloadManager>	dms )
		{
			if ( expr == null ){
				
				return;
			}

			Set<Taggable>	existing = tag.getTagged();
			
			for ( DownloadManager dm: dms ){
			
				if ( dm.isDestroyed() || !dm.isPersistent()){
					
					continue;
				}
				
				if ( testConstraint( dm )){
					
					if ( !existing.contains( dm )){
						
						if ( canAddTaggable( dm )){
						
							tag.addTaggable( dm );
						}
					}
				}else{
					
					if ( existing.contains( dm )){
						
						tag.removeTaggable( dm );
					}
				}
			}
		}
		

		
		private boolean
		canAddTaggable(
			DownloadManager		dm )
		{
			long	now = SystemTime.getMonotonousTime();
				
			Map<DownloadManager,Long> recent_dms = handler.apply_history.get( tag );
				
			if ( recent_dms != null ){
					
				Long time = recent_dms.get( dm );
					
				if ( time != null && now - time < 1000 ){
					
					System.out.println( "Not applying constraint as too recently actioned: " + dm.getDisplayName() + "/" + tag.getTagName( true ));

					return( false );
				}
			}
			
			if ( recent_dms == null ){
					
				recent_dms = new HashMap<DownloadManager,Long>();
					
				handler.apply_history.put( tag, recent_dms );
			}
				
			recent_dms.put( dm, now );
			
			return( true );
		}
		
		private boolean
		testConstraint(
			DownloadManager	dm )
		{
			List<Tag> dm_tags = handler.tag_manager.getTagsForTaggable( dm );
			
			return( expr.eval( dm, dm_tags ));
		}
	
		private interface
		ConstraintExpr
		{
			public boolean
			eval(
				DownloadManager		dm,
				List<Tag>			tags );
			
			public String
			getString();
		}
		
		private class
		ConstraintExprTrue
			implements ConstraintExpr
		{
			public boolean
			eval(
				DownloadManager		dm,
				List<Tag>			tags )
			{
				return( true );
			}
			
			public String
			getString()
			{
				return( "true" );
			}
		}
		
		private class
		ConstraintExprParams
			implements  ConstraintExpr
		{
			private String	value;
			
			private
			ConstraintExprParams(
				String	_value )
			{
				value = _value.trim();
			}
			
			public boolean
			eval(
				DownloadManager		dm,
				List<Tag>			tags )
			{
				return( false );
			}
			
			public Object[]
			getValues()
			{
				if ( value.length() == 0 ){
					
					return( new String[0]);
					
				}else if ( !value.contains( "," )){
				
					return( new Object[]{ value });
					
				}else{
					
					char[]	chars = value.toCharArray();
					
					boolean in_quote = false;
					
					List<String>	params = new ArrayList<String>(16);
					
					StringBuffer current_param = new StringBuffer( value.length());
					
					for (int i=0;i<chars.length;i++){
					
						char c = chars[i];
						
						if ( c == '"' ){
							
							if ( i == 0 || chars[i-1] != '\\' ){
								
								in_quote = !in_quote;
							}
						}
						
						if ( c == ',' && !in_quote ){
							
							params.add( current_param.toString());
							
							current_param.setLength( 0 );
							
						}else{
							
							if ( in_quote || !Character.isWhitespace( c )){
							
								current_param.append( c );
							}
						}
					}
					
					params.add( current_param.toString());
					
					return( params.toArray( new Object[ params.size()]));
				}
			}
			
			public String
			getString()
			{
				return( value );
			}
		}
		
		private class
		ConstraintExprNot
			implements  ConstraintExpr
		{
			private	ConstraintExpr expr;
			
			private
			ConstraintExprNot(
				ConstraintExpr	e )
			{
				expr = e;
			}
			
			public boolean
			eval(
				DownloadManager		dm,
				List<Tag>			tags )		
			{
				return( !expr.eval( dm, tags ));
			}
			
			public String
			getString()
			{
				return( "!(" + expr.getString() + ")");
			}
		}
		
		private class
		ConstraintExprOr
			implements  ConstraintExpr
		{
			private ConstraintExpr[]	exprs;
			
			private
			ConstraintExprOr(
				ConstraintExpr[]	_exprs )
			{
				exprs = _exprs;
			}
			
			public boolean
			eval(
				DownloadManager		dm,
				List<Tag>			tags )		
			{
				for ( ConstraintExpr expr: exprs ){
					
					if ( expr.eval( dm, tags )){
						
						return( true );
					}
				}
				
				return( false );
			}
			
			public String
			getString()
			{
				String res = "";
				
				for ( int i=0;i<exprs.length;i++){
					
					res += (i==0?"":"||") + exprs[i].getString();
				}
				
				return( "(" + res + ")" );
			}
		}
		
		private class
		ConstraintExprAnd
			implements  ConstraintExpr
		{
			private ConstraintExpr[]	exprs;
			
			private
			ConstraintExprAnd(
				ConstraintExpr[]	_exprs )
			{
				exprs = _exprs;
			}
			
			public boolean
			eval(
				DownloadManager		dm,
				List<Tag>			tags )
			{
				for ( ConstraintExpr expr: exprs ){
					
					if ( !expr.eval( dm, tags )){
						
						return( false );
					}
				}
				
				return( true );
			}
			
			public String
			getString()
			{
				String res = "";
				
				for ( int i=0;i<exprs.length;i++){
					
					res += (i==0?"":"&&") + exprs[i].getString();
				}
				
				return( "(" + res + ")" );
			}
		}
		
		private class
		ConstraintExprXor
			implements  ConstraintExpr
		{
			private ConstraintExpr[]	exprs;
			
			private
			ConstraintExprXor(
				ConstraintExpr[]	_exprs )
			{
				exprs = _exprs;
				
				if ( exprs.length < 2 ){
					
					throw( new RuntimeException( "Two or more arguments required for ^" ));
				}
			}
			
			public boolean
			eval(
				DownloadManager		dm,
				List<Tag>			tags )
			{
				boolean res = exprs[0].eval( dm, tags );
				
				for ( int i=1;i<exprs.length;i++){
					
					res = res ^ exprs[i].eval( dm, tags );
				}
				
				return( res );
			}
			
			public String
			getString()
			{
				String res = "";
				
				for ( int i=0;i<exprs.length;i++){
					
					res += (i==0?"":"^") + exprs[i].getString();
				}
				
				return( "(" + res + ")" );
			}
		}
		
		private static final int FT_HAS_TAG		= 1;
		private static final int FT_IS_PRIVATE	= 2;
		
		private static final int FT_GE			= 3;
		private static final int FT_GT			= 4;
		private static final int FT_LE			= 5;
		private static final int FT_LT			= 6;
		private static final int FT_EQ			= 7;
		private static final int FT_NEQ			= 8;
		
		private static final int FT_CONTAINS	= 9;
		private static final int FT_MATCHES		= 10;
		
		private static final int FT_HAS_NET			= 11;
		private static final int FT_IS_COMPLETE		= 12;
		private static final int FT_CAN_ARCHIVE		= 13;
		private static final int FT_IS_FORCE_START	= 14;
	
		private static final int FT_JAVASCRIPT		= 15;
	
		
		private static Map<String,Integer>	keyword_map = new HashMap<String, Integer>();
		
		private static final int	KW_SHARE_RATIO		= 0;
		private static final int	KW_AGE 				= 1;
		private static final int	KW_PERCENT 			= 2;
		private static final int	KW_DOWNLOADING_FOR 	= 3;
		private static final int	KW_SEEDING_FOR 		= 4;
		private static final int	KW_SWARM_MERGE 		= 5;
		
		static{
			keyword_map.put( "shareratio", KW_SHARE_RATIO );
			keyword_map.put( "share_ratio", KW_SHARE_RATIO );
			keyword_map.put( "age", KW_AGE );
			keyword_map.put( "percent", KW_PERCENT );
			keyword_map.put( "downloadingfor", KW_DOWNLOADING_FOR );
			keyword_map.put( "downloading_for", KW_DOWNLOADING_FOR );
			keyword_map.put( "seedingfor", KW_SEEDING_FOR );
			keyword_map.put( "seeding_for", KW_SEEDING_FOR );
			keyword_map.put( "swarmmergebytes", KW_SWARM_MERGE );
			keyword_map.put( "swarm_merge_bytes", KW_SWARM_MERGE );
		}
		
		private class
		ConstraintExprFunction
			implements  ConstraintExpr
		{
			
			private	final String 				func_name;
			private final ConstraintExprParams	params_expr;
			private final Object[]				params;
			
			private final int	fn_type;
			
			private
			ConstraintExprFunction(
				String 					_func_name,
				ConstraintExprParams	_params )
			{
				func_name	= _func_name;
				params_expr	= _params;
				
				params		= _params.getValues();
				
				boolean	params_ok = false;
				
				if ( func_name.equals( "hasTag" )){
					
					fn_type = FT_HAS_TAG;
					
					params_ok = params.length == 1 && getStringLiteral( params, 0 );
					
				}else if ( func_name.equals( "hasNet" )){
						
					fn_type = FT_HAS_NET;
						
					params_ok = params.length == 1 && getStringLiteral( params, 0 );
					
					if ( params_ok ){
						
						params[0] = AENetworkClassifier.internalise((String)params[0]);
						
						params_ok = params[0] != null;
					}
				}else if ( func_name.equals( "isPrivate" )){
	
					fn_type = FT_IS_PRIVATE;
	
					params_ok = params.length == 0;
					
				}else if ( func_name.equals( "isForceStart" )){
	
					fn_type = FT_IS_FORCE_START;
	
					params_ok = params.length == 0;
					
				}else if ( func_name.equals( "isComplete" )){
	
					fn_type = FT_IS_COMPLETE;
	
					params_ok = params.length == 0;
					
				}else if ( func_name.equals( "canArchive" )){
	
					fn_type = FT_CAN_ARCHIVE;
	
					params_ok = params.length == 0;
	
				}else if ( func_name.equals( "isGE" )){
					
					fn_type = FT_GE;
					
					params_ok = params.length == 2;
					
				}else if ( func_name.equals( "isGT" )){
					
					fn_type = FT_GT;
					
					params_ok = params.length == 2;
					
				}else if ( func_name.equals( "isLE" )){
					
					fn_type = FT_LE;
					
					params_ok = params.length == 2;
					
				}else if ( func_name.equals( "isLT" )){
					
					fn_type = FT_LT;
					
					params_ok = params.length == 2;
					
				}else if ( func_name.equals( "isEQ" )){
					
					fn_type = FT_EQ;
					
					params_ok = params.length == 2;
					
				}else if ( func_name.equals( "isNEQ" )){
					
					fn_type = FT_NEQ;
					
					params_ok = params.length == 2;
				
				}else if ( func_name.equals( "contains" )){
					
					fn_type = FT_CONTAINS;
						
					params_ok = params.length == 2;
	
				}else if ( func_name.equals( "matches" )){
					
					fn_type = FT_MATCHES;
						
					params_ok = params.length == 2 && getStringLiteral( params, 1 );
	
				}else if ( func_name.equals( "javascript" )){
	
					fn_type = FT_JAVASCRIPT;
					
					params_ok = params.length == 1 && getStringLiteral( params, 0 );
	
				}else{
					
					throw( new RuntimeException( "Unsupported function '" + func_name + "'" ));
				}
				
				if ( !params_ok ){
					
					throw( new RuntimeException( "Invalid parameters for function '" + func_name + "': " + params_expr.getString()));
	
				}
			}
		
			public boolean
			eval(
				DownloadManager		dm,
				List<Tag>			tags )
			{
				switch( fn_type ){
					case FT_HAS_TAG:{
					
						String tag_name = (String)params[0];
						
						for ( Tag t: tags ){
							
							if ( t.getTagName( true ).equals( tag_name )){
								
								return( true );
							}
						}
						
						return( false );
					}
					case FT_HAS_NET:{
						
						String net_name = (String)params[0];
						
						if ( net_name != null ){
							
							String[] nets = dm.getDownloadState().getNetworks();
							
							if ( nets != null ){
								
								for ( String net: nets ){
									
									if ( net == net_name ){
										
										return( true );
									}
								}
							}
						}
						
						return( false );
					}
					case FT_IS_PRIVATE:{
					
						TOTorrent t = dm.getTorrent();
					
						return( t != null && t.getPrivate());
					}
					case FT_IS_FORCE_START:{
						
						return( dm.isForceStart());
					}
	
					case FT_IS_COMPLETE:{
						
						return( dm.isDownloadComplete( false ));
					}
					case FT_CAN_ARCHIVE:{
						
						Download dl = PluginCoreUtils.wrap( dm );
						
						return( dl != null && dl.canStubbify());
					}
					case FT_GE:
					case FT_GT:
					case FT_LE:
					case FT_LT:
					case FT_EQ:
					case FT_NEQ:{
									
						Number n1 = getNumeric( dm, params, 0 );
						Number n2 = getNumeric( dm, params, 1 );
					
						switch( fn_type ){
						
							case FT_GE:
								return( n1.doubleValue() >= n2.doubleValue());
							case FT_GT:
								return( n1.doubleValue() > n2.doubleValue());
							case FT_LE:
								return( n1.doubleValue() <= n2.doubleValue());
							case FT_LT:
								return( n1.doubleValue() < n2.doubleValue());
							case FT_EQ:
								return( n1.doubleValue() == n2.doubleValue());
							case FT_NEQ:
								return( n1.doubleValue() != n2.doubleValue());
						}
						
						return( false );
					}
					case FT_CONTAINS:{
						
						String	s1 = getString( dm, params, 0 );
						String	s2 = getString( dm, params, 1 );
						
						return( s1.contains( s2 ));
					}
					case FT_MATCHES:{
						
						String	s1 = getString( dm, params, 0 );
						
						if ( params[1] == null ){
							
							return( false );
							
						}else if ( params[1] instanceof Pattern ){
							
							return(((Pattern)params[1]).matcher( s1 ).find());
							
						}else{
							
							try{
								Pattern p = Pattern.compile((String)params[1], Pattern.CASE_INSENSITIVE );
								
								params[1] = p;
								
								return( p.matcher( s1 ).find());
								
							}catch( Throwable e ){
								
								Debug.out( "Invalid constraint pattern: " + params[1] );
								
								params[1] = null;
							}
						}
						
						return( false );
					}
					case FT_JAVASCRIPT:{
												
						Object result =
							handler.tag_manager.evalScript( 
								tag, 
								"javascript( " + (String)params[0] + ")", 
								dm,
								"inTag" );
						
						if ( result instanceof Boolean ){
							
							return((Boolean)result);
						}
						
						return( false );
					}
				}
				
				return( false );
			}
			
			private boolean
			getStringLiteral(
				Object[]	args,
				int			index )
			{
				Object _arg = args[index];
				
				if ( _arg instanceof String ){
					
					String arg = (String)_arg;
				
					if ( arg.startsWith( "\"" ) && arg.endsWith( "\"" )){
						
						args[index] = arg.substring( 1, arg.length() - 1 );
						
						return( true );
					}
				}
					
				return( false );
			}
			
			private String
			getString(
				DownloadManager		dm,
				Object[]			args,
				int					index )
			{
				String str = (String)args[index];
				
				if ( str.startsWith( "\"" ) && str.endsWith( "\"" )){
					
					return( str.substring( 1, str.length() - 1 ));
					
				}else if ( str.equals( "name" )){
					
					return( dm.getDisplayName());
					
				}else{
					
					Debug.out( "Invalid constraint string: " + str );
					
					String result = "\"\"";
					
					args[index] = result;
					
					return( result );
				}
			}
	
			private Number
			getNumeric(
				DownloadManager		dm,
				Object[]			args,
				int					index )
			{
				Object arg = args[index];
				
				if ( arg instanceof Number ){
					
					return((Number)arg);
				}
				
				String str = (String)arg;
				
				Number result = 0;
				
				try{
					if ( Character.isDigit( str.charAt(0))){
					
						if ( str.contains( "." )){
							
							result = Float.parseFloat( str );
							
						}else{
							
							result = Long.parseLong( str );
						}
						
						return( result );
					}else{
						
						Integer kw = keyword_map.get( str.toLowerCase( Locale.US ));
						
						if ( kw == null ){
							
							Debug.out( "Invalid constraint keyword: " + str );
							
							return( result );
						}
						
						switch( kw ){
							case KW_SHARE_RATIO:
								result = null;	// don't cache this!
								
								int sr = dm.getStats().getShareRatio();
								
								if ( sr == -1 ){
									
									return( Integer.MAX_VALUE );
									
								}else{
									
									return( new Float( sr/1000.0f ));
								}
							case KW_PERCENT:
							
								result = null;	// don't cache this!
								
									// 0->1000
								
								int percent = dm.getStats().getPercentDoneExcludingDND();
			
								return( new Float( percent/10.0f ));
							
							case KW_AGE:
							
								result = null;	// don't cache this!
									
								long added = dm.getDownloadState().getLongParameter( DownloadManagerState.PARAM_DOWNLOAD_ADDED_TIME );
			
								if ( added <= 0 ){
									
									return( 0 );
								}
								
								return(( SystemTime.getCurrentTime() - added )/1000 );		// secs
							
							case KW_DOWNLOADING_FOR:
							
								result = null;	// don't cache this!
								
								return( dm.getStats().getSecondsDownloading());
							
							case KW_SEEDING_FOR:
							
								result = null;	// don't cache this!
								
								return( dm.getStats().getSecondsOnlySeeding());
							
							case KW_SWARM_MERGE:
								
								result = null;	// don't cache this!
								
								return( dm.getDownloadState().getLongAttribute( DownloadManagerState.AT_MERGED_DATA ));
								
							default:
							
								Debug.out( "Invalid constraint keyword: " + str );
							
								return( result );
						}
					}
				}catch( Throwable e){
					
					Debug.out( "Invalid constraint numeric: " + str );
	
					return( result );
					
				}finally{
					
					if ( result != null ){
						
							// cache literal results 
						
						args[index] = result;
					}
				}
			}
			
			public String
			getString()
			{
				return( func_name + "(" + params_expr.getString() + ")" );
			}
		}
	}
	
	public static void
	main(
		String[]	args )
	{
		TagPropertyConstraintHandler handler = new TagPropertyConstraintHandler();
		
		//System.out.println( handler.compileConstraint( "!(hasTag(\"bil\") && (hasTag( \"fred\" ))) || hasTag(\"toot\")" ).getString());
		System.out.println( handler.compileConstraint( "isGE( shareratio, 1.5)" ).getString());
	}
}
