/*
 * File    : ShareHosterPlugin.java
 * Created : 05-Jan-2004
 * By      : parg
 * 
 * Azureus - a Java Bittorrent client
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details ( see the LICENSE file ).
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package plugins.sharing.hoster;

/**
 * @author parg
 *
 */

import plugins.sharing.ShareResource;
import plugins.sharing.ShareException;
import plugins.sharing.ShareManager;
import plugins.sharing.ShareResourceEvent;
import plugins.sharing.ShareResourceListener;
import plugins.sharing.ShareItem;
import plugins.sharing.ShareResourceFile;
import plugins.sharing.ShareResourceDir;
import plugins.sharing.ShareResourceWillBeDeletedListener;
import plugins.sharing.ShareResourceDeletionVetoException;
import plugins.sharing.ShareManagerListener;
import plugins.torrent.Torrent;
import plugins.torrent.TorrentAttribute;
import plugins.logging.LoggerChannel;
import plugins.download.DownloadWillBeAddedListener;
import plugins.download.Download;
import plugins.download.DownloadAttributeListener;
import plugins.download.DownloadManager;
import plugins.download.DownloadStub;
import plugins.download.DownloadException;
import plugins.download.DownloadRemovalVetoException;
import plugins.download.DownloadWillBeRemovedListener;
import plugins.tracker.TrackerTorrent;
import plugins.tracker.TrackerTorrentRemovalVetoException;
import plugins.tracker.TrackerTorrentWillBeRemovedListener;
import plugins.tracker.Tracker;
import torrentlib.util.platform.win32.PluginInterface;
import torrentlib.util.platform.win32.Plugin;
import torrentlib.util.platform.win32.PluginListener;
import torrentlib.util.platform.win32.PluginEvent;
import java.io.File;
import java.util.*;

import plugins.utils.DelayedTask;
import pluginsimpl.local.PluginCoreUtils;
import torrentlib.internat.MessageText;
import torrentlib.AENetworkClassifier;
import torrentlib.Debug;
import torrentlib.util.TorrentUtils;

import torrentlib.data.tag.Tag;
import torrentlib.data.tag.TagManager;
import torrentlib.data.tag.TagManagerFactory;

public class 
ShareHosterPlugin
	implements Plugin, PluginListener, ShareManagerListener
{
	protected PluginInterface	plugin_interface;
	protected LoggerChannel		log;
	protected Tracker			tracker;
	protected ShareManager		share_manager;
	protected DownloadManager	download_manager;

	protected Map				resource_dl_map = new HashMap();
	protected Map				resource_tt_map = new HashMap();
	
	protected Download			download_being_removed;
	protected TrackerTorrent	torrent_being_removed;
			
	public static void
	load(
		PluginInterface		plugin_interface )
	{
		plugin_interface.getPluginProperties().setProperty( "plugin.version", 	"1.0" );
		plugin_interface.getPluginProperties().setProperty( "plugin.name", 		"Share Hoster" );
	}
	
	public void 
	initialize(
		PluginInterface _plugin_interface )
	{
		plugin_interface = _plugin_interface;
		
		log	= plugin_interface.getLogger().getChannel("ShareHosterPlugin");
		
		log.log( LoggerChannel.LT_INFORMATION, "ShareHosterPlugin: initialisation starts");
		
		plugin_interface.addListener( this );
	}
	
	public void
	initializationComplete()
	{
		final DelayedTask dt = plugin_interface.getUtilities().createDelayedTask(new Runnable()
			{
				public void 
				run() 
				{
					initialise();
				}
			});
		
		dt.queue();
	}
	
	protected void
	initialise()
	{
		log.log( LoggerChannel.LT_INFORMATION, "ShareHosterPlugin: initialisation complete");
		
		Thread.currentThread().setPriority( Thread.MIN_PRIORITY );
		
		try{
			tracker	=  plugin_interface.getTracker();
	
			download_manager = plugin_interface.getDownloadManager();
			
			share_manager = plugin_interface.getShareManager();
						
			share_manager.addListener( this );
			
			share_manager.initialise();
			
		}catch( ShareException e ){
			
			Debug.printStackTrace( e );
			
			log.log( e );
			
		}finally{
			
			plugin_interface.getPluginManager().firePluginEvent( PluginEvent.PEV_INITIAL_SHARING_COMPLETE );
		}
	}
	
	public void
	closedownInitiated()
	{
	}
	
	public void
	closedownComplete()
	{
	}
	
	public void
	resourceAdded(
		final ShareResource		resource )
	{
		log.log( LoggerChannel.LT_INFORMATION, "Resource added:".concat(resource.getName()));
		
		try{
			
			resource.addDeletionListener(
				new ShareResourceWillBeDeletedListener()
				{
					public void
					resourceWillBeDeleted(
						ShareResource	resource )
					
						throws ShareResourceDeletionVetoException
					{
						canResourceBeDeleted( resource );
					}
				});
			
			Download	new_download = null;
			
			int	type = resource.getType();
			
			if ( type == ShareResource.ST_FILE ){
				
				ShareResourceFile	file_resource = (ShareResourceFile)resource;
				
				ShareItem	item = file_resource.getItem();
		
				Torrent torrent = item.getTorrent();
				
				Download	download = download_manager.getDownload( torrent );
				
				if ( download == null ){
					
					new_download = addDownload( resource, torrent, item.getTorrentFile(), file_resource.getFile());
				}
			}else if ( type == ShareResource.ST_DIR ){
			
				ShareResourceDir	dir_resource = (ShareResourceDir)resource;
				
				ShareItem	item = dir_resource.getItem();
				
				Torrent torrent = item.getTorrent();
				
				Download	download = download_manager.getDownload( torrent );
				
				if ( download == null ){
					
					new_download = addDownload( resource, torrent, item.getTorrentFile(), dir_resource.getDir());
				}
			}
			
			if ( new_download != null ){

				final Download	f_new_download = new_download;
				
				resource_dl_map.put( resource, new_download );
				
				resource.addChangeListener(
					new ShareResourceListener()
					{
						public void
						shareResourceChanged(
							ShareResource			resource,
							ShareResourceEvent		event )
						{
							if ( event.getType() == ShareResourceEvent.ET_ATTRIBUTE_CHANGED ){
						
								TorrentAttribute	attribute = (TorrentAttribute)event.getData();
								
								// System.out.println( "sh: res -> ds: " + attribute.getName() + "/" + resource.getAttribute( attribute ));
								
								f_new_download.setAttribute(
										attribute,
										resource.getAttribute( attribute ));
							}
						}
					});
					
				TorrentAttribute[]	attributes = resource.getAttributes();
				
				for (int i=0;i<attributes.length;i++){
					
					TorrentAttribute	ta = attributes[i];
								
					new_download.setAttribute( ta,	resource.getAttribute( ta ));
				}
				
				new_download.addAttributeListener(
					new DownloadAttributeListener() {
						public void attributeEventOccurred(Download d, TorrentAttribute attr, int event_type){
							resource.setAttribute(attr, d.getAttribute(attr));
						}
					},
					plugin_interface.getTorrentManager().getAttribute(TorrentAttribute.TA_CATEGORY),
					DownloadAttributeListener.WRITTEN
				);
				
				boolean persistent = resource.isPersistent();
				
				Torrent	dl_torrent = new_download.getTorrent();
				
				if ( dl_torrent != null ){
					
					TrackerTorrent	tt = tracker.host(dl_torrent, persistent );
					
					if ( !persistent ){
						
						tt.addRemovalListener(
								new TrackerTorrentWillBeRemovedListener()
								{
									public void
									torrentWillBeRemoved(
										TrackerTorrent	tt )
									
										throws TrackerTorrentRemovalVetoException
									{
										if ( tt != torrent_being_removed ){
											
											throw( new TrackerTorrentRemovalVetoException(
													MessageText.getString("plugin.sharing.torrent.remove.veto")));
										}
									}								
								});
					}
					
					resource_tt_map.put( resource, tt );
				}
				
				if ( !persistent ){
					
					new_download.addDownloadWillBeRemovedListener(
							new DownloadWillBeRemovedListener()
							{
								public void
								downloadWillBeRemoved(
									Download	dl )
								
									throws DownloadRemovalVetoException
								{
									if ( dl != download_being_removed ){
										
										throw( new DownloadRemovalVetoException(
													MessageText.getString("plugin.sharing.download.remove.veto")));
									}
								}
							});
				}
			}
			
		}catch( Throwable e ){
			
			Debug.printStackTrace( e );
		}
	}
	
	private Download
	addDownload(
		ShareResource		resource,
		final Torrent		torrent,
		File				torrent_file,
		File				data_file )
		
		throws DownloadException
	{
		Map<String,String>	properties  = resource.getProperties();
		
		final List<String>	networks 	= new ArrayList<String>();
		final List<Tag>		tags		= new ArrayList<Tag>();
		
		if ( properties != null ){
			
			String nets = properties.get( ShareManager.PR_NETWORKS );
			
			if ( nets != null ){
				
				String[] bits = nets.split( "," );
								
				for ( String bit: bits ){
			
					bit = AENetworkClassifier.internalise( bit.trim());
					
					if ( bit != null ){
						
						networks.add( bit );
					}
				}
			}
			
			String tags_str = properties.get( ShareManager.PR_TAGS );
			
			if ( tags_str != null ){
				
				String[] bits = tags_str.split( "," );
						
				TagManager tm = TagManagerFactory.getTagManager();
				
				for ( String bit: bits ){
			
					try{
						long tag_uid = Long.parseLong( bit.trim());
						
						Tag tag = tm.lookupTagByUID( tag_uid );
						
						if ( tag != null ){
							
							tags.add( tag );
						}
					}catch( Throwable e ){
						
						Debug.out( e );
					}
				}
			}
		}	
				
		DownloadWillBeAddedListener dwbal = null; 

		if ( networks.size() > 0 ){
			
			dwbal = 
				new DownloadWillBeAddedListener()
				{		
					public void 
					initialised(
						Download download )
					{
						if ( Arrays.equals( download.getTorrentHash(), torrent.getHash())){ 
						
							PluginCoreUtils.unwrap( download ).getDownloadState().setNetworks( networks.toArray( new String[networks.size()]));
						}
					}
				};
		
			download_manager.addDownloadWillBeAddedListener( dwbal );
		}
		
		try{			
			Download download;
			
			if ( resource.isPersistent()){
				
				DownloadStub stub = download_manager.lookupDownloadStub( torrent.getHash());
				
				if ( stub != null ){
				
						// already exists in archive
					
					return( null );
				}
				
				try{
					torrent.setComplete( data_file );
					
				}catch( Throwable e ){
					
					Debug.out( e );
				}
				
				download = download_manager.addDownload( torrent, torrent_file, data_file );

			}else{
				
				download = download_manager.addNonPersistentDownload( torrent, torrent_file, data_file );
			}

			if ( tags.size() > 0 ){
				
				xfer.download.DownloadManager dm = PluginCoreUtils.unwrap( download );
				
				for ( Tag tag: tags ){
					
					tag.addTaggable( dm );
				}
			}
			
			return( download );		
			
		}finally{
			
			if ( dwbal != null ){
				
				download_manager.removeDownloadWillBeAddedListener( dwbal );
			}
		}
	}
	
	protected void
	canResourceBeDeleted(
		ShareResource	resource )
	
		throws ShareResourceDeletionVetoException
	{
		Download	dl = (Download)resource_dl_map.get(resource);
		
		if ( dl != null ){
			
			try{
				download_being_removed	= dl;
				
				dl.canBeRemoved();
				
			}catch( DownloadRemovalVetoException e ){
				
				throw( new ShareResourceDeletionVetoException( e.getMessage()));
				
			}finally{
				
				download_being_removed	= null;
			}
		}
		
		TrackerTorrent	tt = (TrackerTorrent)resource_tt_map.get(resource);
		
		if ( tt != null ){
		
			try{
				torrent_being_removed	= tt;
				
				tt.canBeRemoved();
				
			}catch( TrackerTorrentRemovalVetoException e ){
				
				throw( new ShareResourceDeletionVetoException( e.getMessage()));
				
			}finally{
				
				torrent_being_removed	= null;
			}
		}	
	}
	
	public void
	resourceModified(
		ShareResource		old_resource,
		ShareResource		new_resource )
	{
		log.log( LoggerChannel.LT_INFORMATION, "Resource modified:".concat(old_resource.getName()));
					
		resourceDeleted( old_resource );
			
		resourceAdded( new_resource );
	}
	
	public void
	resourceDeleted(
		ShareResource		resource )
	{
		log.log( LoggerChannel.LT_INFORMATION, "Resource deleted:".concat(resource.getName()));
				
		Download	dl = (Download)resource_dl_map.get(resource);
		
		if ( dl != null ){
			
			try{
				download_being_removed	= dl;
				
					// the resource has gone! stop torrent if running to permit 
					// deletion
				
				try{
					dl.stop();
					
				}catch( Throwable e ){
					
					// ignore this as it might already be stopped
				}
				
				dl.remove();
				
			}catch( Throwable e ){
				
				Debug.printStackTrace( e );
				
			}finally{
				
				download_being_removed	= null;
			}
			
			resource_dl_map.remove( resource );
		}	
		
		TrackerTorrent	tt = (TrackerTorrent)resource_tt_map.get(resource);
		
		if ( tt != null ){
			
			try{
				torrent_being_removed	= tt;
				
				tt.remove();
				
			}catch( Throwable e ){
				
				Debug.printStackTrace( e );
				
			}finally{
				
				torrent_being_removed	= null;
			}
			
			resource_tt_map.remove( resource );
		}	
	}

	public void
	reportProgress(
		int		percent_complete )
	{
	}
	
	public void
	reportCurrentTask(
		String	task_description )
	{
		log.log( LoggerChannel.LT_INFORMATION, "Current Task:".concat(task_description) );
	}
}