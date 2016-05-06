/*
 * Created on Mar 22, 2013
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import xfer.download.DownloadManagerPeerListener;
import xfer.download.DownloadManagerState;
import xfer.download.DownloadManagerStats;
import connect.peer.PEPeer;
import connect.peer.PEPeerManager;
import torrentlib.AERunnable;
import torrentlib.AsyncDispatcher;
import torrentlib.Debug;
import torrentlib.SystemTime;
import plugins.download.Download;
import pluginsimpl.local.PluginCoreUtils;

import controller.networkmanager.LimitedRateGroup;
import torrentlib.data.tag.Tag;
import torrentlib.data.tag.TagDownload;
import torrentlib.data.tag.TagFeature;
import torrentlib.data.tag.TagFeatureExecOnAssign;
import torrentlib.data.tag.TagFeatureProperties;
import torrentlib.data.tag.TagFeatureRateLimit;
import torrentlib.data.tag.TagFeatureRunState;
import torrentlib.data.tag.TagListener;
import torrentlib.data.tag.TagType;
import torrentlib.data.tag.Taggable;
import xfer.download.DownloadManager;

public class 
TagDownloadWithState
	extends TagWithState
	implements TagDownload
{
	private int upload_rate_limit;
	private int download_rate_limit;
	
	private int	upload_rate		= -1;
	private int	download_rate	= -1;
	
	private int	aggregate_sr;
	
	private long session_up;
	private long session_down;
	
	private long last_rate_update;
	
	private Object	UPLOAD_PRIORITY_ADDED_KEY = new Object();
	private int		upload_priority;
	private int		min_share_ratio;
	private int		max_share_ratio;
	private int		max_aggregate_share_ratio;
	
	private boolean	supports_xcode;
	private boolean	supports_file_location;
	
	private Object	rate_lock = new Object();
	
	private LimitedRateGroup upload_limiter = 
		new LimitedRateGroup()
		{
			public String 
			getName() 
			{
				String str = "tag_up: " + getTagName( true );
				
				if ( upload_rate_limit < 0 ){
					
					str += ": disabled";
				}
				
				return( str );
			}
			
			public int 
			getRateLimitBytesPerSecond()
			{
				int	res = upload_rate_limit;
				
				if ( res < 0 ){
					
					res = 0;	// disabled upload handled by per-peer limits
				}
				
				return( res );
			}
			public boolean 
			isDisabled() 
			{
				return( upload_rate_limit < 0 );
			}
			public void
			updateBytesUsed(
				int	used )
			{
				session_up += used;
			}
		};

	private LimitedRateGroup download_limiter = 
		new LimitedRateGroup()
		{
			public String 
			getName() 
			{
				String str = "tag_down: " + getTagName( true );
				
				if ( download_rate_limit < 0 ){
					
					str += ": disabled";
				}
				
				return( str );
			}
			
			public int 
			getRateLimitBytesPerSecond()
			{
				int	res = download_rate_limit;
				
				if ( res < 0 ){
					
					res = 0;	// disabled upload handled by per-peer limits
				}
				
				return( res );
			}
	
			public boolean 
			isDisabled() 
			{
				return( download_rate_limit < 0 );
			}
			
			public void
			updateBytesUsed(
					int	used )
			{
				session_down += used;
			}
		}; 
		
	private boolean	do_rates;
	private boolean	do_up;
	private boolean	do_down;
	private boolean	do_bytes;
	
	private int		run_states;
	
	private static AsyncDispatcher rs_async = new AsyncDispatcher(2000);

	private TagProperty[] tag_properties = 
		new TagProperty[]{ 
			createTagProperty( TagFeatureProperties.PR_TRACKERS, TagFeatureProperties.PT_STRING_LIST ),
			createTagProperty( TagFeatureProperties.PR_UNTAGGED, TagFeatureProperties.PT_BOOLEAN ),
			createTagProperty( TagFeatureProperties.PR_TRACKER_TEMPLATES, TagFeatureProperties.PT_STRING_LIST ),
			createTagProperty( TagFeatureProperties.PR_CONSTRAINT, TagFeatureProperties.PT_STRING_LIST ) 
		};
	
	public
	TagDownloadWithState(
		TagTypeBase		tt,
		int				tag_id,
		String			name,
		boolean			do_rates,
		boolean			do_up,
		boolean			do_down,
		boolean			do_bytes,
		int				run_states )
	{
		super( tt, tag_id, name );
		
		init( do_rates, do_up, do_down, do_bytes, run_states );
	}
	
	protected
	TagDownloadWithState(
		TagTypeBase		tt,
		int				tag_id,
		Map				details,
		boolean			do_rates,
		boolean			do_up,
		boolean			do_down,
		boolean			do_bytes,
		int				run_states )
	{
		super( tt, tag_id, details );
		
		init( do_rates, do_up, do_down, do_bytes, run_states );
	}
	
	private void
	init(
		boolean		_do_rates,
		boolean		_do_up,
		boolean		_do_down,
		boolean		_do_bytes,
		int			_run_states )
	{
		do_rates	= _do_rates;
		do_up		= _do_up;
		do_down		= _do_down;
		do_bytes	= _do_bytes;
		
		run_states	= _run_states;
		
		if ( do_up ){
			
			setRateLimit(readLongAttribute( AT_RATELIMIT_UP, 0L ).intValue(), true );
		}
		
		if ( do_down ){
			
			setRateLimit(readLongAttribute( AT_RATELIMIT_DOWN, 0L ).intValue(), false );
		}
		
		upload_priority		= readLongAttribute( AT_RATELIMIT_UP_PRI, 0L ).intValue();
		
		min_share_ratio		= readLongAttribute( AT_RATELIMIT_MIN_SR, 0L ).intValue();
		max_share_ratio		= readLongAttribute( AT_RATELIMIT_MAX_SR, 0L ).intValue();
		
		max_aggregate_share_ratio		= readLongAttribute( AT_RATELIMIT_MAX_AGGREGATE_SR, 0L ).intValue();

		addTagListener(new TagListener()
			{
				public void
				taggableAdded(
					Tag			tag,
					Taggable	tagged )
				{
					DownloadManager manager = (DownloadManager)tagged;
					
					setRateLimit( manager, true );
					
					if ( upload_priority > 0 ){
														
						manager.updateAutoUploadPriority( UPLOAD_PRIORITY_ADDED_KEY, true );
					}
					
					if ( min_share_ratio > 0 ){
						
						updateMinShareRatio( manager, min_share_ratio );
					}
					
					if ( max_share_ratio > 0 ){
						
						updateMaxShareRatio( manager, max_share_ratio );
					}
				}
				
				public void 
				taggableSync(
					Tag 		tag ) 
				{
				}
				
				public void
				taggableRemoved(
					Tag			tag,
					Taggable	tagged )
				{
					DownloadManager manager = (DownloadManager)tagged;
					
					setRateLimit( manager, false );
					
					if ( upload_priority > 0 ){
						
						manager.updateAutoUploadPriority( UPLOAD_PRIORITY_ADDED_KEY, false );
					}
					
					if ( min_share_ratio > 0 ){
						
						updateMinShareRatio( manager, 0 );
					}
					
					if ( max_share_ratio > 0 ){
						
						updateMaxShareRatio( manager, 0 );
					}
				}
				
				private void
				updateMinShareRatio(
					DownloadManager	manager,
					int				sr )
				{
					List<Tag> dm_tags = getTagType().getTagsForTaggable( manager );
					
					for ( Tag t: dm_tags ){
						
						if ( t == TagDownloadWithState.this ){
							
							continue;
						}
						
						if ( t instanceof TagFeatureRateLimit ){
							
							int o_sr = ((TagFeatureRateLimit)t).getTagMinShareRatio();
							
							if ( o_sr > sr ){
								
								sr = o_sr;
							}
						}
					}
					
					manager.getDownloadState().setIntParameter( DownloadManagerState.PARAM_MIN_SHARE_RATIO, sr );
				}
				
				private void
				updateMaxShareRatio(
					DownloadManager	manager,
					int				sr )
				{
					List<Tag> dm_tags = getTagType().getTagsForTaggable( manager );
					
					for ( Tag t: dm_tags ){
						
						if ( t == TagDownloadWithState.this ){
							
							continue;
						}
						
						if ( t instanceof TagFeatureRateLimit ){
							
							int o_sr = ((TagFeatureRateLimit)t).getTagMaxShareRatio();
							
							if ( o_sr > sr ){
								
								sr = o_sr;
							}
						}
					}
					
					manager.getDownloadState().setIntParameter( DownloadManagerState.PARAM_MAX_SHARE_RATIO, sr );
				}
			},
			true );
	}
	
	@Override
	public void 
	removeTag() 
	{
		for ( DownloadManager dm: getTaggedDownloads()){
			
			setRateLimit( dm, false );
			
			if ( upload_priority > 0 ){
				
				dm.updateAutoUploadPriority( UPLOAD_PRIORITY_ADDED_KEY, false );
			}
		}
		
		super.removeTag();
	}
	
	@Override
	public void
	addTaggable(
		Taggable	t )
	{
		if ( t instanceof DownloadManager ){
			
			final DownloadManager dm = (DownloadManager)t;
			
			if ( dm.isDestroyed()){
				
				// There's a race condition when stopping and removing a torrent that isn't easy to avoid in terms
				// of a download being added to the 'stopped' tag and concurrently removed. 
				// There will be a subseqent 'downloadRemoved' action triggered that should tidy up any
				// inconsistency left due to this
				
				//Debug.out( "Invalid Taggable added - download is destroyed: " + dm.getDisplayName());
				
			}else{
			
				super.addTaggable( t );
				
				int actions = getSupportedActions();
				
				if ( actions != TagFeatureExecOnAssign.ACTION_NONE ){
					
					if ( isActionEnabled( TagFeatureExecOnAssign.ACTION_START )){
												
						int	dm_state = dm.getState();
							
						if ( 	dm_state == DownloadManager.STATE_STOPPED ||
								dm_state == DownloadManager.STATE_ERROR ){		    		
					    	
							rs_async.dispatch(
								new AERunnable()
								{
									public void
									runSupport()
									{
										dm.setStateQueued();
									}
								});
						}

					}else if ( isActionEnabled( TagFeatureExecOnAssign.ACTION_STOP )){
						
						int	dm_state = dm.getState();
							
						if ( 	dm_state != DownloadManager.STATE_STOPPED &&
								dm_state != DownloadManager.STATE_STOPPING &&
								dm_state != DownloadManager.STATE_ERROR ){
							
							rs_async.dispatch(new AERunnable()
								{
									public void
									runSupport()
									{
										dm.stopIt(DownloadManager.STATE_STOPPED, false, false );
									}
								});
						}
					}
					
					if ( isActionEnabled( TagFeatureExecOnAssign.ACTION_FORCE_START )){
					
						rs_async.dispatch(
							new AERunnable()
							{
								public void
								runSupport()
								{
									dm.setForceStart( true);
								}
							});
						
					}else if ( isActionEnabled( TagFeatureExecOnAssign.ACTION_NOT_FORCE_START )){
						
						rs_async.dispatch(
							new AERunnable()
							{
								public void
								runSupport()
								{
									dm.setForceStart( false );
								}
							});
					}
					
					if ( isActionEnabled( TagFeatureExecOnAssign.ACTION_SCRIPT )){
						
						final String script = getActionScript();
						
						if ( script.length() > 0 ){
							
							rs_async.dispatch(
								new AERunnable()
								{
									public void
									runSupport()
									{
										TagManagerImpl.getSingleton().evalScript( 
											TagDownloadWithState.this, 
											script, 
											dm,
											"execAssign" );
									}
								});
						}
					}
					
				}
			}
		}else{
			
			Debug.out( "Invalid Taggable added: " + t );
		}
	}
	
	public int 
	getTaggableTypes() 
	{
		return( Taggable.TT_DOWNLOAD );
	}
	
	public Set<DownloadManager> 
	getTaggedDownloads() 
	{
		return((Set<DownloadManager>)(Object)getTagged());
	}

	private DownloadManagerPeerListener 
		peer_listener =
			new DownloadManagerPeerListener()
			{
				public void
				peerManagerWillBeAdded(
					PEPeerManager	manager )
				{					
				}
		
				public void
				peerManagerAdded(
					PEPeerManager	manager )
				{					
				}
				
				public void
				peerManagerRemoved(
					PEPeerManager	manager )
				{					
				}
				
				public void
				peerAdded(
					PEPeer 			peer )
				{	
					synchronized( rate_lock ){
						
						if ( upload_rate_limit < 0 ){
							
							peer.setUploadDisabled( this, true );
						}
						
						if ( download_rate_limit < 0 ){
							
							peer.setDownloadDisabled( this, true );
						}
					}
				}
					
				public void
				peerRemoved(
					PEPeer			peer )
				{					
				}
			};
			
	private void
	setRateLimit(
		DownloadManager	manager,
		boolean			added )
	{
		synchronized( rate_lock ){
			
			if ( added ){
				
				if ( manager.getUserData( rate_lock ) == null ){
				
					manager.setUserData( rate_lock, "" );
					
					manager.addPeerListener( peer_listener, true );
					
					manager.addRateLimiter( upload_limiter, true );
					
					manager.addRateLimiter( download_limiter, false );
				}
			}else{
				
				if ( manager.getUserData( rate_lock ) != null ){
					
					manager.setUserData( rate_lock, null );
					
					manager.removeRateLimiter( upload_limiter, true );
					
					manager.removeRateLimiter( download_limiter, false );
					
					manager.removePeerListener( peer_listener );
					
					PEPeerManager pm = manager.getPeerManager();
					
					if ( pm != null ){
						
						List<PEPeer> peers = pm.getPeers();
						
						if ( upload_rate_limit < 0 || download_rate_limit < 0 ){
							
							for ( PEPeer peer: peers ){
									
								if ( upload_rate_limit < 0 ){
									
									peer.setUploadDisabled( peer_listener, false );
								}
								
								if ( download_rate_limit < 0 ){
									
									peer.setDownloadDisabled( peer_listener, false );
								}
							}
						}
					}
				}
			}
		}
	}
	
	private void
	setRateLimit(
		int			limit,
		boolean		is_up )
	{
		if ( limit < 0 ){
			
			limit = -1;
		}
		
		synchronized( rate_lock ){
			
			if ( is_up ){
				
				if ( limit == upload_rate_limit ){
					
					return;
				}
				
				if ( limit < 0 || upload_rate_limit < 0 ){
					
					Set<DownloadManager> downloads = getTaggedDownloads();
					
					for ( DownloadManager dm: downloads ){
						
						PEPeerManager pm = dm.getPeerManager();
						
						if ( pm != null ){
							
							List<PEPeer> peers = pm.getPeers();
							
							for ( PEPeer peer: peers ){
								
								peer.setUploadDisabled( peer_listener, limit < 0 );
							}
						}
					}
				}
					
				upload_rate_limit = limit;
				
			}else{
								
				if ( limit == download_rate_limit ){
					
					return;
				}
				
				if ( limit < 0 || download_rate_limit < 0 ){
					
					Set<DownloadManager> downloads = getTaggedDownloads();
					
					for ( DownloadManager dm: downloads ){
						
						PEPeerManager pm = dm.getPeerManager();
						
						if ( pm != null ){
							
							List<PEPeer> peers = pm.getPeers();
							
							for ( PEPeer peer: peers ){
								
								peer.setDownloadDisabled( peer_listener, limit < 0 );
							}
						}
					}
				}
				
				download_rate_limit = limit;
			}
		}
	}
	
	public boolean
	supportsTagRates()
	{
		return( do_rates );
	}
	
	public boolean
	supportsTagUploadLimit()
	{
		return( do_up );
	}

	public boolean
	supportsTagDownloadLimit()
	{
		return( do_down );
	}

	public int
	getTagUploadLimit()
	{
		return( upload_rate_limit );
	}

	public void
	setTagUploadLimit(
		int		bps )
	{
		if ( upload_rate_limit == bps ){
			
			return;
		}
		
		if ( !do_up ){
			
			Debug.out( "Not supported" );
			
			return;
		}
		
		setRateLimit( bps, true );
		
		writeLongAttribute( AT_RATELIMIT_UP, upload_rate_limit );

		getTagType().fireChanged( this );
	}
	
	public int
	getTagCurrentUploadRate()
	{
		updateStuff();
		
		return( upload_rate );
	}
	
	public int
	getTagDownloadLimit()
	{
		return( download_rate_limit );
	}
	
	public void
	setTagDownloadLimit(
		int		bps )
	{
		if ( download_rate_limit == bps ){
			
			return;
		}
		
		if ( !do_down ){
			
			Debug.out( "Not supported" );
			
			return;
		}
		
		setRateLimit( bps, false );
				
		writeLongAttribute( AT_RATELIMIT_DOWN, download_rate_limit );

		getTagType().fireChanged( this );
	}
	
	public int
	getTagCurrentDownloadRate()
	{
		updateStuff();
		
		return( download_rate );
	}
	
	public int
	getTagUploadPriority()
	{
		return( upload_priority );
	}
	
	@Override
	protected long[]
	getTagSessionUploadTotalCurrent()
	{	
		if ( do_bytes && do_up ){
					
			return( new long[]{ session_up });
			
		}else{
			
			return( null );
		}
	}
	
	@Override
	protected long[]
	getTagSessionDownloadTotalCurrent()
	{	
		if ( do_bytes && do_down ){
		
			return( new long[]{ session_down });

		}else{
			
			return( null );
		}
	}
	
	public void
	setTagUploadPriority(
		int		priority )
	{
		if ( priority < 0 ){
			
			priority = 0;
		}
		
		if ( priority == upload_priority ){
			
			return;
		}
		
		int	old_up = upload_priority;
		
		upload_priority	= priority;
		
		writeLongAttribute( AT_RATELIMIT_UP_PRI, priority );
		
		if ( old_up == 0 || priority == 0 ){
			
			Set<DownloadManager> dms = getTaggedDownloads();
			
			for ( DownloadManager dm: dms ){
					
				dm.updateAutoUploadPriority( UPLOAD_PRIORITY_ADDED_KEY, priority>0 );
			}
		}

		getTagType().fireChanged( this );
	}
	
	public int
	getTagMinShareRatio()
	{
		return( min_share_ratio );
	}
	
	public void
	setTagMinShareRatio(
		int		sr )
	{
		if ( sr < 0 ){
			
			sr = 0;
		}
		
		if ( sr == min_share_ratio ){
			
			return;
		}
				
		min_share_ratio	= sr;
		
		writeLongAttribute( AT_RATELIMIT_MIN_SR, sr );
					
		Set<DownloadManager> dms = getTaggedDownloads();
			
		for ( DownloadManager dm: dms ){
				
			List<Tag> dm_tags = getTagType().getTagsForTaggable( dm );
			
			for ( Tag t: dm_tags ){
				
				if ( t == this ){
					
					continue;
				}
				
				if ( t instanceof TagFeatureRateLimit ){
					
					int o_sr = ((TagFeatureRateLimit)t).getTagMinShareRatio();
					
					if ( o_sr > sr ){
						
						sr = o_sr;
					}
				}
			}
			
			dm.getDownloadState().setIntParameter( DownloadManagerState.PARAM_MIN_SHARE_RATIO, sr );
		}

		getTagType().fireChanged( this );
	}
	
	public int
	getTagMaxShareRatio()
	{
		return( max_share_ratio );
	}
	
	public void
	setTagMaxShareRatio(
		int		sr )
	{
		if ( sr < 0 ){
			
			sr = 0;
		}
		
		if ( sr == max_share_ratio ){
			
			return;
		}
				
		max_share_ratio	= sr;
		
		writeLongAttribute( AT_RATELIMIT_MAX_SR, sr );
					
		Set<DownloadManager> dms = getTaggedDownloads();
			
		for ( DownloadManager dm: dms ){
				
			List<Tag> dm_tags = getTagType().getTagsForTaggable( dm );
			
			for ( Tag t: dm_tags ){
				
				if ( t == this ){
					
					continue;
				}
				
				if ( t instanceof TagFeatureRateLimit ){
					
					int o_sr = ((TagFeatureRateLimit)t).getTagMaxShareRatio();
					
					if ( o_sr > sr ){
						
						sr = o_sr;
					}
				}
			}
			
			dm.getDownloadState().setIntParameter( DownloadManagerState.PARAM_MAX_SHARE_RATIO, sr );
		}

		getTagType().fireChanged( this );
	}
	
	public int
	getTagAggregateShareRatio()
	{
		updateStuff();
		
		return( aggregate_sr );
	}
	
	public int
	getTagMaxAggregateShareRatio()
	{
		return( max_aggregate_share_ratio );
	}
	
	public void
	setTagMaxAggregateShareRatio(
		int		sr )
	{
		if ( sr < 0 ){
			
			sr = 0;
		}
		
		if ( sr == max_aggregate_share_ratio ){
			
			return;
		}
				
		max_aggregate_share_ratio	= sr;
		
		writeLongAttribute( AT_RATELIMIT_MAX_AGGREGATE_SR, sr );
				
		getTagType().fireChanged( this );
		
		checkAggregateShareRatio();
	}
	
	private void
	updateStuff()
	{
		long	now = SystemTime.getCurrentTime();
		
		if ( now - last_rate_update > 2500 ){ 
			
			int	new_up		= 0;
			int new_down	= 0;
			
			long new_agg_up		= 0;
			long new_agg_down	= 0;
			
			
			Set<DownloadManager> dms = getTaggedDownloads();
			
			if ( dms.size() == 0 ){
				
				new_up		= -1;
				new_down	= -1;

			}else{
				
				new_up		= 0;
				new_down	= 0;
				
				for ( DownloadManager dm: dms ){
		
					DownloadManagerStats stats = dm.getStats();
					
					new_up 		+= stats.getDataSendRate() + stats.getProtocolSendRate();
					new_down 	+= stats.getDataReceiveRate() + stats.getProtocolReceiveRate();
					
					long downloaded	= stats.getTotalGoodDataBytesReceived();
					long uploaded	= stats.getTotalDataBytesSent();
			        
					if ( downloaded > 0 ){
					  
						new_agg_down += downloaded;
					}
					
					if ( uploaded > 0 ){
						  
						new_agg_up += uploaded;
					}
					
				}
			}
			
			upload_rate			= new_up;
			download_rate		= new_down;
			
			aggregate_sr = new_agg_down<=0?0:(int) ((1000 * new_agg_up) / new_agg_down);
			
			
			last_rate_update 	= now;
		}
	}
	
	private void
	checkAggregateShareRatio()
	{
		if ( max_aggregate_share_ratio > 0 ){
			
			updateStuff();
			
			if ( aggregate_sr >= max_aggregate_share_ratio ){
				
				performOperation( TagFeatureRunState.RSC_STOP );
				
			}else{
				
				performOperation( TagFeatureRunState.RSC_START );
			}
		}
	}
	
	protected void
	sync()
	{
		checkAggregateShareRatio();
		
		super.sync();
	}
	
	public int
	getRunStateCapabilities()
	{
		return( run_states );
	}
	
	public boolean
	hasRunStateCapability(
		int		capability )
	{
		return((run_states & capability ) != 0 );
	}
	
	public boolean[]
   	getPerformableOperations(
      	int[]	ops )
   	{
   		boolean[] result = new boolean[ ops.length];
   		
		Set<DownloadManager> dms = getTaggedDownloads();

		for ( DownloadManager dm: dms ){
			
			int	dm_state = dm.getState();
			
			for ( int i=0;i<ops.length;i++){
				
				if ( result[i]){
					
					continue;
				}
				
				int	op = ops[i];
				
				if (( op & TagFeatureRunState.RSC_START ) != 0 ){
					
					if ( 	dm_state == DownloadManager.STATE_STOPPED ||
							dm_state == DownloadManager.STATE_ERROR ){
						
						result[i] = true;
					}
				}
				
				if (( op & TagFeatureRunState.RSC_STOP ) != 0 ){
					
					if ( 	dm_state != DownloadManager.STATE_STOPPED &&
							dm_state != DownloadManager.STATE_STOPPING &&
							dm_state != DownloadManager.STATE_ERROR ){
						
						result[i] = true;
					}
				}
				
				if (( op & TagFeatureRunState.RSC_PAUSE ) != 0 ){
					
					if ( 	dm_state != DownloadManager.STATE_STOPPED &&
							dm_state != DownloadManager.STATE_STOPPING &&
							dm_state != DownloadManager.STATE_ERROR ){
						
						if ( !dm.isPaused()){
						
							result[i] = true;
						}
					}
				}
				
				if (( op & TagFeatureRunState.RSC_RESUME ) != 0 ){

					if ( dm.isPaused()){
						
						result[i] = true;
					}
				}
			}
		}
		
		return( result );
   	}
	
	public void
	performOperation(
		int		op )
	{
		Set<DownloadManager> dms = getTaggedDownloads();

		for ( final DownloadManager dm: dms ){
			
			int	dm_state = dm.getState();

			if ( op == TagFeatureRunState.RSC_START ){
				
				if ( 	dm_state == DownloadManager.STATE_STOPPED ||
						dm_state == DownloadManager.STATE_ERROR ){		    		
		    	
					rs_async.dispatch(
						new AERunnable()
						{
							public void
							runSupport()
							{
								dm.setStateQueued();
							}
						});
				}
			}else if ( op == TagFeatureRunState.RSC_STOP ){
				
				if ( 	dm_state != DownloadManager.STATE_STOPPED &&
						dm_state != DownloadManager.STATE_STOPPING &&
						dm_state != DownloadManager.STATE_ERROR ){
					
					rs_async.dispatch(new AERunnable()
						{
							public void
							runSupport()
							{
								dm.stopIt(DownloadManager.STATE_STOPPED, false, false );
							}
						});
				}
			}else if ( op == TagFeatureRunState.RSC_PAUSE ){
				
				if ( 	dm_state != DownloadManager.STATE_STOPPED &&
						dm_state != DownloadManager.STATE_STOPPING &&
						dm_state != DownloadManager.STATE_ERROR ){
					
					rs_async.dispatch(
						new AERunnable()
						{
							public void
							runSupport()
							{
								dm.pause();
							}
						});
				}
			}else if ( op == TagFeatureRunState.RSC_RESUME ){

				if ( dm.isPaused()){
					
					rs_async.dispatch(
						new AERunnable()
						{
							public void
							runSupport()
							{
								dm.resume();
							}
						});
				}
			}
		}
	}
	
	public int
	getSupportedActions()
	{
		if ( getTagType().getTagType() == TagType.TT_DOWNLOAD_MANUAL ){
			
			return( TagFeatureExecOnAssign.ACTION_START | 
					TagFeatureExecOnAssign.ACTION_FORCE_START | 
					TagFeatureExecOnAssign.ACTION_NOT_FORCE_START | 
					TagFeatureExecOnAssign.ACTION_STOP |
					TagFeatureExecOnAssign.ACTION_SCRIPT );
			
		}else if ( getTagType().getTagType() == TagType.TT_DOWNLOAD_STATE ){
				
			return( TagFeatureExecOnAssign.ACTION_SCRIPT );
			
		}else{
			
			return( TagFeatureExecOnAssign.ACTION_NONE );
		}
	}
	
	protected void
	setSupportsTagTranscode(
		boolean	sup )
	{
		supports_xcode = sup;	
	}
	
	public boolean
	supportsTagTranscode()
	{
		return( supports_xcode );
	}

	public String[]
	getTagTranscodeTarget()
	{
		String temp = readStringAttribute( AT_XCODE_TARGET, null );
		
		if ( temp == null ){
			
			return( null );
		}
		
		String[] bits = temp.split( "\n" );
		
		if ( bits.length != 2 ){
			
			return( null );
		}
		
		return( bits );
	}

	public void
	setTagTranscodeTarget(
		String		uid,
		String		name )
	{
		writeStringAttribute( AT_XCODE_TARGET, uid==null?null:(uid + "\n" + name ));
		
		getTagType().fireChanged( this );
		
		getManager().featureChanged( this, TagFeature.TF_XCODE );
	} 
	
	protected void
	setSupportsFileLocation(
		boolean		sup )
	{
		supports_file_location = sup;
	}
	
	@Override
	public boolean
	supportsTagInitialSaveFolder()
	{
		return( supports_file_location );
	}
	
	@Override
	public boolean
	supportsTagMoveOnComplete()
	{
		return( supports_file_location );
	}
	
	@Override
	public boolean
	supportsTagCopyOnComplete()
	{
		return( supports_file_location );
	}
	
	@Override
	public TagProperty[]
	getSupportedProperties()
	{
		return( getTagType().isTagTypeAuto()?new TagProperty[0]:tag_properties );
	}
	
	@Override
	public boolean 
	isTagAuto() 
	{
		TagProperty[]	props = getSupportedProperties();
		
		for ( TagProperty prop: props ){
			
			String name = prop.getName( false );
			
			if ( name.equals( TagFeatureProperties.PR_TRACKER_TEMPLATES )){
				
				continue;
			}
			
			int	type =  prop.getType();
			
			if ( type == TagFeatureProperties.PT_BOOLEAN ){
				
				Boolean b = prop.getBoolean();
				
				if ( b != null && b ){
					
					return( true );
				}
			}else if ( type == TagFeatureProperties.PT_LONG ){
					
				Long l = prop.getLong();
					
				if ( l != null && l != Long.MIN_VALUE ){
						
					return( true );
				}
			}else if ( type == TagFeatureProperties.PT_STRING_LIST ){
				
				String[] val = prop.getStringList();
				
				if ( val != null && val.length > 0 ){
					
					return( true );
				}
			}
		}
		
		return( false );
	}
	
	@Override
	public int
	getMaximumTaggables()
	{
		if ( getTagType().getTagType() != TagType.TT_DOWNLOAD_MANUAL ){
			
			return( -1 );
		}
		
		return( super.getMaximumTaggables());
	}
	
	@Override
	protected void
	checkMaximumTaggables()
	{
		if ( getTagType().getTagType() != TagType.TT_DOWNLOAD_MANUAL ){
			
			return;
		}

		int max = getMaximumTaggables();
		
		if ( max <= 0 ){
			
			return;
		}
				
		int removal_strategy = getRemovalStrategy();
		
		if ( removal_strategy == RS_NONE ){
			
			return;
		}
					
		if ( getTaggedCount() > max ){
			
			Set<DownloadManager> dms = getTaggedDownloads();
			
			List<DownloadManager>	sorted_dms = new ArrayList<DownloadManager>( dms );
			
				// oldest first
			
			Collections.sort(sorted_dms,
				new Comparator<DownloadManager>()
				{
					public int 
					compare(
						DownloadManager dm1,
						DownloadManager dm2) 
					{
						long t1 = dm1.getDownloadState().getLongParameter( DownloadManagerState.PARAM_DOWNLOAD_ADDED_TIME );
						long t2 = dm2.getDownloadState().getLongParameter( DownloadManagerState.PARAM_DOWNLOAD_ADDED_TIME );
						
						if ( t1 < t2 ){
							
							return( -1 );
							
						}else if ( t1 > t2 ){
							
							return( 1 );
							
						}else{
							
							return( dm1.getInternalName().compareTo( dm2.getInternalName()));
						}
					}
				});
			
			Iterator<DownloadManager> it = sorted_dms.iterator();
				
			while( it.hasNext() && sorted_dms.size() > max ){
				
				DownloadManager dm = it.next();
				
				it.remove();
				
				try{
					if ( removal_strategy == RS_ARCHIVE ){
						
						Download download = PluginCoreUtils.wrap( dm );
						
						if ( download.canStubbify()){
							
								// have to remove from tag otherwise when it is restored it will no doubt get re-archived!
							
							removeTaggable( dm );
							
							download.stubbify();
						}
					}else if ( removal_strategy == RS_REMOVE_FROM_LIBRARY ){
		
							dm.getGlobalManager().removeDownloadManager( dm, false, false );
							
						}else if ( removal_strategy == RS_DELETE_FROM_COMPUTER ){
						
						boolean reallyDeleteData =  !dm.getDownloadState().getFlag(	Download.FLAG_DO_NOT_DELETE_DATA_ON_REMOVE );
	
						dm.getGlobalManager().removeDownloadManager( dm, true, reallyDeleteData);
						
					}
				
				}catch( Throwable e ){
					
					Debug.out( e );
				}
			}
		}
	}
}
